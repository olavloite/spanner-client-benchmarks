package main

import (
	"context"
	"fmt"
	"log"

	"os"
	"os/signal"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"

	"cloud.google.com/go/spanner"
	mexporter "github.com/GoogleCloudPlatform/opentelemetry-operations-go/exporter/metric"
	"github.com/google/uuid"
	"github.com/urfave/cli/v3"
	"go.opentelemetry.io/otel/sdk/resource"
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
	memoryUsageName    = "spanner_client_benchmarks/memory_usage"
	cpuUtilizationName = "spanner_client_benchmarks/cpu_utilization"
)

type Benchmark interface {
	Execute(ctx context.Context, client *spanner.Client, tableName string, minId, maxId int64) error
	Name() string
	Type() string
	ShouldMeasureEntireMethod() bool
}

func main() {
	ctx := context.Background()
	if err := run(ctx, os.Args); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	app := &cli.Command{
		Name:  "BenchmarkApp",
		Usage: "Spanner client library benchmark tool for Go.",
		Flags: []cli.Flag{
			&cli.StringFlag{Name: "project", Required: true, Usage: "Google Cloud Project ID"},
			&cli.StringFlag{Name: "instance", Required: true, Usage: "Spanner Instance ID"},
			&cli.StringFlag{Name: "database", Required: true, Usage: "Spanner Database ID"},
			&cli.StringFlag{Name: "table", Usage: "Table name (required for non-tpcc benchmarks)"},
			&cli.StringFlag{Name: "duration", Value: "inf", Usage: "Duration of the benchmark (e.g. 60s, 5m, inf)"},
			&cli.BoolFlag{Name: "for-alerting", Value: false, Usage: "Marks the benchmark for alerting purposes"},
			&cli.StringFlag{Name: "benchmark-name", Usage: "Optional name to identify this benchmark run in metrics"},
			&cli.StringFlag{Name: "host", Usage: "Custom Spanner host endpoint override"},
			&cli.StringFlag{Name: "resource-probe-interval", Value: "10s", Usage: "Interval for probing resource usage (e.g. 10s, 1m). Set to 0 to disable"},
			&cli.IntFlag{Name: "threads", Value: 100, Usage: "Number of parallel workers allowed"},
			&cli.StringFlag{Name: "load-type", Value: "steady", Usage: "Load type (steady, spiky, gradual)"},
			&cli.StringFlag{Name: "cycle-duration", Usage: "Duration of a full cycle for gradual load"},
			&cli.FloatFlag{Name: "peak-factor", Usage: "Ratio of peak rate to average rate for gradual load"},
			&cli.FloatFlag{Name: "burst-factor", Usage: "Ratio of burst rate to average rate"},
			&cli.FloatFlag{Name: "burst-duration", Usage: "Average duration of a burst in seconds"},
			&cli.FloatFlag{Name: "burst-fraction", Usage: "Fraction of total time spent in the burst state"},
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
			{
				Name:  "tpcc",
				Usage: "Runs closed-loop TPC-C benchmark",
				Flags: []cli.Flag{
					&cli.IntFlag{Name: "warehouses", Value: 1, Usage: "Scale factor (number of warehouses)"},
					&cli.IntFlag{Name: "clients", Value: 10, Usage: "Number of parallel worker clients"},
					&cli.IntFlag{Name: "items", Value: 100000, Usage: "Number of items in catalog"},
				},
				Action: func(ctx context.Context, cmd *cli.Command) error {
					return executeTPCCBenchmark(ctx, cmd)
				},
			},
		},
	}

	return app.Run(ctx, args)
}

type GlobalConfig struct {
	Project               string
	Instance              string
	Database              string
	Table                 string
	DurationStr           string
	ForAlerting           bool
	BenchmarkName         string
	ResourceProbeInterval string
	Host                  string
	Threads               int
}

func parseGlobalConfig(cmd *cli.Command) GlobalConfig {
	return GlobalConfig{
		Project:               cmd.String("project"),
		Instance:              cmd.String("instance"),
		Database:              cmd.String("database"),
		Table:                 cmd.String("table"),
		DurationStr:           cmd.String("duration"),
		ForAlerting:           cmd.Bool("for-alerting"),
		BenchmarkName:         cmd.String("benchmark-name"),
		ResourceProbeInterval: cmd.String("resource-probe-interval"),
		Host:                  cmd.String("host"),
		Threads:               int(cmd.Int("threads")),
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
	cfg := parseGlobalConfig(cmd)
	tps := cmd.Float("tps")
	numRows := int64(cmd.Int("num-rows"))
	loadTypeStr := cmd.String("load-type")
	cycleDurationStr := cmd.String("cycle-duration")
	peakFactor := cmd.Float("peak-factor")
	burstFactor := cmd.Float("burst-factor")
	burstDuration := cmd.Float("burst-duration")
	burstFraction := cmd.Float("burst-fraction")

	loadType, err := ParseLoadType(loadTypeStr)
	if err != nil {
		return err
	}

	// Validation
	err = ValidateAndApplyDefaults(cmd, loadType, &cycleDurationStr, &peakFactor, &burstFactor, &burstDuration, &burstFraction)
	if err != nil {
		return err
	}

	// Setup Metrics
	latencyHistogram, readLatencyHistogram, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, cleanupMetrics, err := setupMetrics(runCtx, cfg.Project, cfg.Host, cfg.BenchmarkName)
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
	client, err := createSpannerClient(runCtx, cfg.Project, cfg.Instance, cfg.Database, cfg.Host)
	if err != nil {
		return fmt.Errorf("failed to build Spanner client: %w", err)
	}
	defer client.Close()

	attributeList := []attribute.KeyValue{
		attribute.String("benchmark_type", b.Type()),
		attribute.Float64("tps", tps),
		attribute.Bool("for_alerting", cfg.ForAlerting),
		attribute.String("benchmark_name", cfg.BenchmarkName),
		attribute.String("client", "go-client"),
		attribute.String("load_type", loadType.String()),
		attribute.Float64("burst_factor", burstFactor),
		attribute.Float64("burst_duration", burstDuration),
		attribute.Float64("burst_fraction", burstFraction),
		attribute.Int64("cycle_duration_ms", ParseDuration(cycleDurationStr).Milliseconds()),
		attribute.Float64("peak_factor", peakFactor),
		attribute.String("transaction_type", "none"),
	}
	if benchmarkType == "read-large-result-set" {
		attributeList = append(attributeList, attribute.Int64("num_rows", numRows))
	}
	attributes := metric.WithAttributes(attributeList...)

	runCtx = context.WithValue(runCtx, MetricAttributesKey, attributes)

	fmt.Printf("Starting %s for %s, target TPS: %.2f, workers: %d\n", b.Name(), cfg.DurationStr, tps, cfg.Threads)

	// Setup duration context timeout if not infinite
	var durationCtx context.Context
	if cfg.DurationStr != "inf" && cfg.DurationStr != "infinite" {
		duration := ParseDuration(cfg.DurationStr)
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
	runBenchmark(durationCtx, b, client, latencyHistogram, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, cfg.ResourceProbeInterval, cfg.Table, tps, cfg.Threads, 1, numRows, attributes, loadType, cycleDurationStr, peakFactor, burstFactor, burstDuration, burstFraction)

	return nil
}

func getLatencyBuckets() []float64 {
	buckets := make([]float64, 0, 120)
	for i := 50.0; i <= 5000.0; i += 50.0 {
		buckets = append(buckets, i)
	}
	buckets = append(buckets, 6000.0, 7000.0, 8000.0, 9000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0, 20000.0, 25000.0, 30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0)
	return buckets
}

var testingMeterProvider metric.MeterProvider

func setupMetrics(ctx context.Context, projectID string, host string, benchmarkName string) (metric.Float64Histogram, metric.Float64Histogram, metric.Int64Counter, metric.Int64Counter, metric.Float64Histogram, metric.Float64Histogram, func(), error) {
	var meterProvider metric.MeterProvider
	cleanup := func() {}

	if testingMeterProvider != nil {
		meterProvider = testingMeterProvider
	} else if os.Getenv("SPANNER_EMULATOR_HOST") != "" || (host != "" && (strings.Contains(host, "localhost:") || strings.Contains(host, "127.0.0.1:"))) {
		h, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		rh, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		o, _ := noop.NewMeterProvider().Meter("").Int64Counter("")
		e, _ := noop.NewMeterProvider().Meter("").Int64Counter("")
		mh, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		ch, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		return h, rh, o, e, mh, ch, func() {}, nil
	} else {
		exporter, err := mexporter.New(mexporter.WithProjectID(projectID))
		if err != nil {
			return nil, nil, nil, nil, nil, nil, nil, err
		}

		svcName := benchmarkName
		if svcName == "" {
			svcName = "spanner-benchmark"
		}
		res, err := resource.Merge(
			resource.Default(),
			resource.NewWithAttributes(
				"",
				attribute.String("service.name", svcName),
				attribute.String("service.instance.id", uuid.New().String()),
			),
		)
		if err != nil {
			return nil, nil, nil, nil, nil, nil, nil, err
		}

		sdkProvider := sdkmetric.NewMeterProvider(
			sdkmetric.WithResource(res),
			sdkmetric.WithReader(sdkmetric.NewPeriodicReader(exporter, sdkmetric.WithInterval(60*time.Second))),
		)
		otel.SetMeterProvider(sdkProvider)
		meterProvider = sdkProvider
		cleanup = func() {
			_ = sdkProvider.Shutdown(ctx)
		}
	}

	meter := meterProvider.Meter(meterName)
	latencyHistogram, err := meter.Float64Histogram(latencyName,
		metric.WithDescription("Query latency in microseconds"),
		metric.WithUnit("us"),
		metric.WithExplicitBucketBoundaries(getLatencyBuckets()...),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, nil, nil, err
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
		return nil, nil, nil, nil, nil, nil, nil, err
	}

	operationCounter, err := meter.Int64Counter(operationCountName,
		metric.WithDescription("Total number of benchmark operations executed"),
		metric.WithUnit("1"),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, nil, nil, err
	}

	errorCounter, err := meter.Int64Counter(errorCountName,
		metric.WithDescription("Total number of benchmark operations that failed with an error"),
		metric.WithUnit("1"),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, nil, nil, err
	}

	MB := 1024.0 * 1024.0
	memoryUsageHistogram, err := meter.Float64Histogram(memoryUsageName,
		metric.WithDescription("Active memory usage in bytes"),
		metric.WithUnit("By"),
		metric.WithExplicitBucketBoundaries(2.5*MB, 5.0*MB, 7.5*MB, 10.0*MB, 20.0*MB, 30.0*MB, 40.0*MB, 50.0*MB, 60.0*MB, 70.0*MB, 80.0*MB, 90.0*MB, 100.0*MB, 200.0*MB, 300.0*MB, 400.0*MB, 500.0*MB, 750.0*MB, 1000.0*MB, 1500.0*MB, 2000.0*MB, 3000.0*MB, 5000.0*MB, 10000.0*MB),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, nil, nil, err
	}

	cpuUtilizationHistogram, err := meter.Float64Histogram(cpuUtilizationName,
		metric.WithDescription("Process CPU utilization"),
		metric.WithUnit("1"),
		metric.WithExplicitBucketBoundaries(0.01, 0.02, 0.03, 0.04, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 1.0),
	)
	if err != nil {
		return nil, nil, nil, nil, nil, nil, nil, err
	}

	return latencyHistogram, readLatencyHistogram, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, cleanup, nil
}

func createSpannerClient(ctx context.Context, project, instance, database, host string) (*spanner.Client, error) {
	databaseName := fmt.Sprintf("projects/%s/instances/%s/databases/%s", project, instance, database)
	var clientOpts []option.ClientOption
	if host != "" {
		clientOpts = append(clientOpts, option.WithEndpoint(host), option.WithGRPCDialOption(grpc.WithInsecure()), option.WithoutAuthentication())
	}
	return spanner.NewClient(ctx, databaseName, clientOpts...)
}

func runBenchmark(ctx context.Context, b Benchmark, client *spanner.Client, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, memoryUsageHistogram metric.Float64Histogram, cpuUtilizationHistogram metric.Float64Histogram, resourceProbeIntervalStr string, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption, loadType LoadType, cycleDurationStr string, peakFactor, burstFactor, burstDuration, burstFraction float64) {
	tasks := make(chan struct{}, 1000000) // large buffered channel to simulate unbounded queue
	wg := &sync.WaitGroup{}

	startResourceMonitoring(ctx, memoryUsageHistogram, cpuUtilizationHistogram, resourceProbeIntervalStr, attributes)

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
						if ctx.Err() == nil {
							log.Printf("Operation failed: %v", err)
							errorCounter.Add(ctx, 1, attributes)
						}
					}
				}
			}
		}()
	}

	generator, ok := generators[loadType]
	if !ok {
		log.Fatalf("No generator found for load type: %v", loadType)
	}

	generator.Run(ctx, b, client, latencyHistogram, operationCounter, errorCounter, tableName, targetTPS, concurrentThreads, minId, maxId, attributes, cycleDurationStr, peakFactor, burstFactor, burstDuration, burstFraction, tasks, wg)
}

func startResourceMonitoring(ctx context.Context, memoryUsageHistogram metric.Float64Histogram, cpuUtilizationHistogram metric.Float64Histogram, resourceProbeIntervalStr string, attributes metric.MeasurementOption) {
	if resourceProbeIntervalStr != "0" && resourceProbeIntervalStr != "0s" && resourceProbeIntervalStr != "" {
		interval := ParseDuration(resourceProbeIntervalStr)
		if interval > 0 {
			go func() {
				ticker := time.NewTicker(interval)
				defer ticker.Stop()
				var lastUtime int64
				var lastStime int64
				var lastWall time.Time
				initialized := false

				for {
					select {
					case <-ctx.Done():
						return
					case now := <-ticker.C:
						probeResourceUsage(ctx, memoryUsageHistogram, cpuUtilizationHistogram, attributes, now, &lastUtime, &lastStime, &lastWall, &initialized)
					}
				}
			}()
		}
	}
}

func probeResourceUsage(ctx context.Context, memoryUsageHistogram metric.Float64Histogram, cpuUtilizationHistogram metric.Float64Histogram, attributes metric.MeasurementOption, now time.Time, lastUtime *int64, lastStime *int64, lastWall *time.Time, initialized *bool) {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	memoryUsageHistogram.Record(ctx, float64(m.Alloc), attributes)

	var rusage syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &rusage); err == nil {
		utime := int64(rusage.Utime.Sec)*1e6 + int64(rusage.Utime.Usec)
		stime := int64(rusage.Stime.Sec)*1e6 + int64(rusage.Stime.Usec)
		if *initialized {
			elapsedWall := now.Sub(*lastWall).Seconds()
			if elapsedWall > 0 {
				cpuUtil := (float64((utime-*lastUtime)+(stime-*lastStime)) / 1e6) / elapsedWall
				cpuUtilizationHistogram.Record(ctx, cpuUtil, attributes)
			}
		}
		*lastUtime = utime
		*lastStime = stime
		*lastWall = now
		*initialized = true
	}
}


