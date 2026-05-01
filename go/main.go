package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"math"
	"math/rand"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"cloud.google.com/go/spanner"
	mexporter "github.com/GoogleCloudPlatform/opentelemetry-operations-go/exporter/metric"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/metric/noop"
	"google.golang.org/api/option"
	"google.golang.org/grpc"
)

const (
	meterName   = "spanner-benchmark"
	latencyName = "SpannerBenchmark/latency"
)

type Benchmark interface {
	Execute(ctx context.Context, client *spanner.Client, tableName string, minId, maxId int64) error
	Name() string
	Type() string
}

func main() {
	ctx := context.Background()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("BenchmarkApp", flag.ContinueOnError)

	project := fs.String("project", "", "Google Cloud Project ID (Required)")
	instance := fs.String("instance", "", "Spanner Instance ID (Required)")
	database := fs.String("database", "", "Spanner Database ID (Required)")
	table := fs.String("table", "", "Table name (Required)")
	durationStr := fs.String("duration", "inf", "Duration of the benchmark (e.g. 60s, 5m, inf). Defaults to infinite.")
	forAlerting := fs.Bool("for-alerting", false, "Marks the benchmark for alerting purposes.")
	tps := fs.Float64("tps", 1.0, "Target transactions per second")
	threads := fs.Int("threads", 100, "Number of parallel workers allowed")
	host := fs.String("host", "", "Custom Spanner host endpoint")
	minId := fs.Int64("min-id", 1, "Minimum ID value")
	maxId := fs.Int64("max-id", 1000000, "Maximum ID value")

	if err := fs.Parse(args); err != nil {
		return err
	}

	if *project == "" || *instance == "" || *database == "" || *table == "" {
		return fmt.Errorf("missing required flags: project, instance, database, and table must be provided")
	}

	benchmarkType := "point-select"
	if len(fs.Args()) > 0 {
		benchmarkType = fs.Args()[0]
	}

	var b Benchmark
	switch benchmarkType {
	case "point-select":
		b = &PointSelectBenchmark{}
	case "select-update":
		b = &SelectAndUpdateBenchmark{}
	default:
		return fmt.Errorf("unknown benchmark type: %s", benchmarkType)
	}

	// Duration setup
	durationCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	duration := parseDuration(*durationStr)
	if duration > 0 {
		time.AfterFunc(duration, func() {
			fmt.Println("Benchmark duration reached. Stopping...")
			cancel()
		})
	}

	// Signal handler
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		cancel()
	}()

	// 1. Setup Metrics
	latencyHistogram, cleanupMetrics, err := setupMetrics(durationCtx, *project)
	if err != nil {
		return fmt.Errorf("failed to initialize metrics: %w", err)
	}
	defer cleanupMetrics()

	// 2. Setup client
	client, err := createSpannerClient(durationCtx, *project, *instance, *database, *host)
	if err != nil {
		return fmt.Errorf("failed to build Spanner client: %w", err)
	}
	defer client.Close()

	attributes := metric.WithAttributes(
		attribute.String("benchmark_type", b.Type()),
		attribute.Float64("tps", *tps),
		attribute.Bool("for_alerting", *forAlerting),
		attribute.String("client", "go-client"),
	)

	fmt.Printf("Starting %s for %s, target TPS: %.1f, workers: %d\n", b.Name(), *durationStr, *tps, *threads)

	// 3. Run loop
	runBenchmark(durationCtx, b, client, latencyHistogram, *table, *tps, *threads, *minId, *maxId, attributes)

	return nil
}

func setupMetrics(ctx context.Context, projectID string) (metric.Float64Histogram, func(), error) {
	if os.Getenv("SPANNER_EMULATOR_HOST") != "" {
		h, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		return h, func() {}, nil
	}
	exporter, err := mexporter.New(mexporter.WithProjectID(projectID))
	if err != nil {
		return nil, nil, err
	}
	meterProvider := sdkmetric.NewMeterProvider(
		sdkmetric.WithReader(sdkmetric.NewPeriodicReader(exporter, sdkmetric.WithInterval(60*time.Second))),
	)
	otel.SetMeterProvider(meterProvider)

	meter := meterProvider.Meter(meterName)
	latencyHistogram, err := meter.Float64Histogram(latencyName,
		metric.WithDescription("Query latency in microseconds"),
		metric.WithUnit("us"),
		metric.WithExplicitBucketBoundaries(1000.0, 2500.0, 5000.0, 7500.0, 10000.0, 15000.0, 20000.0, 25000.0, 30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0),
	)

	cleanup := func() {
		meterProvider.Shutdown(ctx)
	}

	return latencyHistogram, cleanup, err
}

func createSpannerClient(ctx context.Context, project, instance, database, host string) (*spanner.Client, error) {
	databaseName := fmt.Sprintf("projects/%s/instances/%s/databases/%s", project, instance, database)
	var clientOpts []option.ClientOption
	if host != "" {
		clientOpts = append(clientOpts, option.WithEndpoint(host), option.WithGRPCDialOption(grpc.WithInsecure()), option.WithoutAuthentication())
	}
	return spanner.NewClient(ctx, databaseName, clientOpts...)
}

func runBenchmark(ctx context.Context, b Benchmark, client *spanner.Client, latencyHistogram metric.Float64Histogram, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption) {
	semaphore := make(chan struct{}, concurrentThreads)
	wg := &sync.WaitGroup{}

	generatorTicker := time.NewTicker(1 * time.Microsecond) // minimal tick for poisson calculation
	defer generatorTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			wg.Wait()
			return
		case <-generatorTicker.C:
			semaphore <- struct{}{} // Acquire worker slot
			wg.Add(1)
			go func() {
				defer wg.Done()
				defer func() { <-semaphore }() // Release worker slot

				start := time.Now()
				err := b.Execute(ctx, client, tableName, minId, maxId)
				if err != nil {
					log.Printf("Operation failed: %v", err)
					return
				}
				latencyHistogram.Record(ctx, float64(time.Since(start).Microseconds()), attributes)
			}()
			time.Sleep(calculatePoissonDelay(targetTPS))
		}
	}
}

func parseDuration(d string) time.Duration {
	if d == "inf" || d == "infinite" {
		return 0
	}
	duration, err := time.ParseDuration(d)
	if err != nil {
		return 0
	}
	return duration
}

func calculatePoissonDelay(rate float64) time.Duration {
	u := rand.Float64()
	delaySeconds := -math.Log(1.0-u) / rate
	return time.Duration(delaySeconds * float64(time.Second))
}
