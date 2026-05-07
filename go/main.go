package main

import (
	"context"
	"fmt"
	"log"
	"math"
	"math/rand"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"cloud.google.com/go/spanner"
	mexporter "github.com/GoogleCloudPlatform/opentelemetry-operations-go/exporter/metric"
	"github.com/urfave/cli/v3"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/metric/noop"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"google.golang.org/api/option"
	"google.golang.org/grpc"
)

const (
	meterName          = "spanner-benchmark"
	latencyName        = "spanner_client_benchmarks/latency"
	readLatencyName    = "spanner_client_benchmarks/read_latency"
	operationCountName = "spanner_client_benchmarks/operation_count"
	errorCountName     = "spanner_client_benchmarks/error_count"
)

type Benchmark interface {
	Execute(ctx context.Context, client *spanner.Client, tableName string, minId, maxId int64) error
	Name() string
	Type() string
	ShouldMeasureEntireMethod() bool
}

func main() {
	ctx := context.Background()

	app := &cli.Command{
		Name:  "BenchmarkApp",
		Usage: "Spanner client library benchmark tool for Go.",
		Flags: []cli.Flag{
			&cli.StringFlag{Name: "project", Required: true, Usage: "Google Cloud Project ID"},
			&cli.StringFlag{Name: "instance", Required: true, Usage: "Spanner Instance ID"},
			&cli.StringFlag{Name: "database", Required: true, Usage: "Spanner Database ID"},
			&cli.StringFlag{Name: "table", Required: true, Usage: "Table name"},
			&cli.StringFlag{Name: "duration", Value: "inf", Usage: "Duration of the benchmark (e.g. 60s, 5m, inf)"},
			&cli.BoolFlag{Name: "for-alerting", Value: false, Usage: "Marks the benchmark for alerting purposes"},
			&cli.StringFlag{Name: "host", Usage: "Custom Spanner host endpoint override"},
			&cli.IntFlag{Name: "threads", Value: 100, Usage: "Number of parallel workers allowed"},
		},
		Commands: []*cli.Command{
			{
				Name:  "point-select",
				Usage: "Runs point select benchmark",
				Flags: []cli.Flag{
					&cli.FloatFlag{Name: "tps", Value: 10.0, Usage: "Target transactions per second"},
					&cli.IntFlag{Name: "num-rows", Value: 1000000, Usage: "Number of rows in target database table"},
				},
				Action: func(ctx context.Context, cmd *cli.Command) error {
					return executeBenchmark(ctx, cmd, "point-select")
				},
			},
			{
				Name:  "select-update",
				Usage: "Runs select and update benchmark",
				Flags: []cli.Flag{
					&cli.FloatFlag{Name: "tps", Value: 10.0, Usage: "Target transactions per second"},
					&cli.IntFlag{Name: "num-rows", Value: 1000000, Usage: "Number of rows in target database table"},
				},
				Action: func(ctx context.Context, cmd *cli.Command) error {
					return executeBenchmark(ctx, cmd, "select-update")
				},
			},
			{
				Name:  "read-large-result-set",
				Usage: "Runs large result set iteration benchmark",
				Flags: []cli.Flag{
					&cli.FloatFlag{Name: "tps", Value: 0.05, Usage: "Target transactions per second"},
					&cli.IntFlag{Name: "num-rows", Value: 100000, Usage: "Number of rows to dynamically generate"},
				},
				Action: func(ctx context.Context, cmd *cli.Command) error {
					return executeBenchmark(ctx, cmd, "read-large-result-set")
				},
			},
		},
	}

	if err := app.Run(ctx, os.Args); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func executeBenchmark(ctx context.Context, cmd *cli.Command, benchmarkType string) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	// Setup OS signal trap for graceful shutdown
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		cancel()
	}()

	// Retrieve global and subcommand-specific flags
	project := cmd.String("project")
	instance := cmd.String("instance")
	database := cmd.String("database")
	table := cmd.String("table")
	durationStr := cmd.String("duration")
	forAlerting := cmd.Bool("for-alerting")
	host := cmd.String("host")
	threads := cmd.Int("threads")
	tps := cmd.Float("tps")
	numRows := int64(cmd.Int("num-rows"))

	// Setup Metrics
	latencyHistogram, readLatencyHistogram, operationCounter, errorCounter, cleanupMetrics, err := setupMetrics(runCtx, project, host)
	if err != nil {
		return fmt.Errorf("failed to initialize metrics: %w", err)
	}
	defer cleanupMetrics()

	var b Benchmark
	switch benchmarkType {
	case "point-select":
		b = &PointSelectBenchmark{}
	case "select-update":
		b = &SelectAndUpdateBenchmark{}
	case "read-large-result-set":
		b = NewReadLargeResultSetBenchmark(readLatencyHistogram, numRows)
	default:
		return fmt.Errorf("unsupported benchmark type: %s", benchmarkType)
	}

	// Setup client
	client, err := createSpannerClient(runCtx, project, instance, database, host)
	if err != nil {
		return fmt.Errorf("failed to build Spanner client: %w", err)
	}
	defer client.Close()

	attributeList := []attribute.KeyValue{
		attribute.String("benchmark_type", b.Type()),
		attribute.Float64("tps", tps),
		attribute.Bool("for_alerting", forAlerting),
		attribute.String("client", "go-client"),
	}
	if benchmarkType == "read-large-result-set" {
		attributeList = append(attributeList, attribute.Int64("num_rows", numRows))
	}
	attributes := metric.WithAttributes(attributeList...)

	runCtx = context.WithValue(runCtx, MetricAttributesKey, attributes)

	fmt.Printf("Starting %s for %s, target TPS: %.2f, workers: %d\n", b.Name(), durationStr, tps, threads)

	// Setup duration context timeout if not infinite
	var durationCtx context.Context
	if durationStr != "inf" && durationStr != "infinite" {
		duration := parseDuration(durationStr)
		if duration > 0 {
			var durationCancel context.CancelFunc
			durationCtx, durationCancel = context.WithTimeout(runCtx, duration)
			defer durationCancel()
		} else {
			durationCtx = runCtx
		}
	} else {
		durationCtx = runCtx
	}

	// Run loop
	runBenchmark(durationCtx, b, client, latencyHistogram, operationCounter, errorCounter, table, tps, threads, 1, numRows, attributes)

	return nil
}

func setupMetrics(ctx context.Context, projectID string, host string) (metric.Float64Histogram, metric.Float64Histogram, metric.Int64Counter, metric.Int64Counter, func(), error) {
	if os.Getenv("SPANNER_EMULATOR_HOST") != "" || (host != "" && (strings.Contains(host, "localhost:") || strings.Contains(host, "127.0.0.1:"))) {
		h, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		rh, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		o, _ := noop.NewMeterProvider().Meter("").Int64Counter("")
		e, _ := noop.NewMeterProvider().Meter("").Int64Counter("")
		return h, rh, o, e, func() {}, nil
	}
	exporter, err := mexporter.New(mexporter.WithProjectID(projectID))
	if err != nil {
		return nil, nil, nil, nil, nil, err
	}
	meterProvider := sdkmetric.NewMeterProvider(
		sdkmetric.WithReader(sdkmetric.NewPeriodicReader(exporter, sdkmetric.WithInterval(60*time.Second))),
	)
	otel.SetMeterProvider(meterProvider)

	meter := meterProvider.Meter(meterName)
	latencyHistogram, err := meter.Float64Histogram(latencyName,
		metric.WithDescription("Query latency in microseconds"),
		metric.WithUnit("us"),
		metric.WithExplicitBucketBoundaries(500.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0, 4000.0, 4500.0, 5000.0, 6000.0, 7000.0, 8000.0, 9000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0, 20000.0, 25000.0, 30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, err
	}

	readLatencyHistogram, err := meter.Float64Histogram(readLatencyName,
		metric.WithDescription("Query latency in microseconds"),
		metric.WithUnit("us"),
		metric.WithExplicitBucketBoundaries(
			50000.0, 100000.0, 250000.0, 500000.0, 750000.0,
			1000000.0, 1250000.0, 1500000.0, 1750000.0, 2000000.0, 2250000.0, 2500000.0, 2750000.0, 3000000.0, 3250000.0, 3500000.0, 3750000.0, 4000000.0, 4250000.0, 4500000.0, 4750000.0, 5000000.0,
			5500000.0, 6000000.0, 6500000.0, 7000000.0, 7500000.0, 8000000.0, 8500000.0, 9000000.0, 9500000.0, 10000000.0,
			12500000.0, 15000000.0, 20000000.0, 30000000.0,
		),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, err
	}

	operationCounter, err := meter.Int64Counter(operationCountName,
		metric.WithDescription("Total number of benchmark operations executed"),
		metric.WithUnit("1"),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, err
	}

	errorCounter, err := meter.Int64Counter(errorCountName,
		metric.WithDescription("Total number of benchmark operations that failed with an error"),
		metric.WithUnit("1"),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, err
	}

	cleanup := func() {
		meterProvider.Shutdown(ctx)
	}

	return latencyHistogram, readLatencyHistogram, operationCounter, errorCounter, cleanup, nil
}

func createSpannerClient(ctx context.Context, project, instance, database, host string) (*spanner.Client, error) {
	databaseName := fmt.Sprintf("projects/%s/instances/%s/databases/%s", project, instance, database)
	var clientOpts []option.ClientOption
	if host != "" {
		clientOpts = append(clientOpts, option.WithEndpoint(host), option.WithGRPCDialOption(grpc.WithInsecure()), option.WithoutAuthentication())
	}
	return spanner.NewClient(ctx, databaseName, clientOpts...)
}

func runBenchmark(ctx context.Context, b Benchmark, client *spanner.Client, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption) {
	tasks := make(chan struct{}, 1000000) // large buffered channel to simulate unbounded queue
	wg := &sync.WaitGroup{}

	// Start worker goroutines
	for i := 0; i < concurrentThreads; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case _, ok := <-tasks:
					if !ok {
						return
					}
					start := time.Now()
					err := b.Execute(ctx, client, tableName, minId, maxId)

					if b.ShouldMeasureEntireMethod() {
						latencyHistogram.Record(ctx, float64(time.Since(start).Microseconds()), attributes)
					}
					operationCounter.Add(ctx, 1, attributes)
					if err != nil {
						log.Printf("Operation failed: %v", err)
						errorCounter.Add(ctx, 1, attributes)
					}
				}
			}
		}()
	}

	generatorTicker := time.NewTicker(1 * time.Microsecond) // minimal tick for poisson calculation
	defer generatorTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			close(tasks)
			wg.Wait()
			return
		case <-generatorTicker.C:
			select {
			case tasks <- struct{}{}: // push task
			default:
				log.Printf("Task dropped: workload queue is full (1M tasks)")
			}
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
