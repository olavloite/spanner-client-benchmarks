package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"cloud.google.com/go/spanner"
	mexporter "github.com/GoogleCloudPlatform/opentelemetry-operations-go/exporter/metric"
	"github.com/google/uuid"
	"github.com/urfave/cli/v3"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/metric/noop"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	"google.golang.org/grpc/codes"
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
	Execute(ctx context.Context, connection *sql.Conn, tableName string, minId, maxId int64) error
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
		Usage: "Spanner database/sql driver benchmark tool for Go.",
		Flags: []cli.Flag{
			&cli.StringFlag{Name: "project", Aliases: []string{"p"}, Required: true, Usage: "Google Cloud Project ID"},
			&cli.StringFlag{Name: "instance", Aliases: []string{"i"}, Required: true, Usage: "Spanner Instance ID"},
			&cli.StringFlag{Name: "database", Aliases: []string{"d"}, Required: true, Usage: "Spanner Database ID"},
			&cli.StringFlag{Name: "table", Aliases: []string{"t"}, Usage: "Table name (required for non-tpcc benchmarks)"},
			&cli.StringFlag{Name: "duration", Value: "inf", Usage: "Duration of the benchmark (e.g. 60s, 5m, inf)"},
			&cli.BoolFlag{Name: "for-alerting", Value: false, Usage: "Marks the benchmark for alerting purposes"},
			&cli.StringFlag{Name: "benchmark-name", Usage: "Optional name to identify this benchmark run in metrics"},
			&cli.StringFlag{Name: "host", Usage: "Custom Spanner host endpoint override"},
			&cli.BoolFlag{Name: "mock", Value: false, Usage: "Use local mock Spanner server"},
			&cli.BoolFlag{Name: "no-metrics", Value: false, Usage: "Disable metrics exporting (for testing purposes)"},
			&cli.StringFlag{Name: "resource-probe-interval", Value: "10s", Usage: "Interval for probing resource usage (e.g. 10s, 1m). Set to 0 to disable"},
			&cli.IntFlag{Name: "threads", Value: 10, Usage: "Number of parallel workers allowed"},
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
				Name:  "read-narrow-result-set",
				Usage: "Runs narrow result set iteration benchmark",
				Flags: []cli.Flag{
					&cli.FloatFlag{Name: "tps", Value: 0.05, Usage: "Target transactions per second"},
					&cli.IntFlag{Name: "num-rows", Value: 200000, Usage: "Number of rows to dynamically generate"},
				},
				Action: func(ctx context.Context, cmd *cli.Command) error {
					return executeBenchmark(ctx, cmd, "read-narrow-result-set")
				},
			},
			{
				Name:  "tpcc",
				Usage: "Runs closed-loop TPC-C benchmark",
				Flags: []cli.Flag{
					&cli.IntFlag{Name: "warehouses", Value: 1, Usage: "Scale factor (number of warehouses)"},
					&cli.IntFlag{Name: "clients", Value: 10, Usage: "Number of parallel worker clients"},
					&cli.IntFlag{Name: "items", Value: 100000, Usage: "Number of items in catalog"},
					&cli.BoolFlag{Name: "extended", Value: false, Usage: "Run TPC-C benchmark with extended coverage of client library features"},
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
	Mock                  bool
	NoMetrics             bool
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
		Mock:                  cmd.Bool("mock"),
		NoMetrics:             cmd.Bool("no-metrics"),
	}
}

func executeBenchmark(ctx context.Context, cmd *cli.Command, benchmarkType string) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-signals
		cancel()
	}()

	configuration := parseGlobalConfig(cmd)
	tps := cmd.Float("tps")
	numRows := int64(cmd.Int("num-rows"))
	loadTypeString := cmd.String("load-type")
	cycleDurationString := cmd.String("cycle-duration")
	peakFactor := cmd.Float("peak-factor")
	burstFactor := cmd.Float("burst-factor")
	burstDuration := cmd.Float("burst-duration")
	burstFraction := cmd.Float("burst-fraction")

	if configuration.Mock {
		mockServer, host, mockCleanup := startMockServer()
		defer mockCleanup()
		registerMockResults(mockServer)
		configuration.Host = host
	}

	loadType, err := ParseLoadType(loadTypeString)
	if err != nil {
		return err
	}

	err = ValidateAndApplyDefaults(cmd, loadType, &cycleDurationString, &peakFactor, &burstFactor, &burstDuration, &burstFraction)
	if err != nil {
		return err
	}

	latencyHistogram, readLatencyHistogram, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, cleanupMetrics, err := setupMetrics(runCtx, configuration.Project, configuration.Host, configuration.BenchmarkName, configuration.NoMetrics)
	if err != nil {
		return fmt.Errorf("failed to initialize metrics: %w", err)
	}
	defer cleanupMetrics()

	var benchmark Benchmark
	var initializer ConnectionInitializer
	switch benchmarkType {
	case "point-select":
		benchmark = &PointSelectBenchmark{isMock: configuration.Mock}
		initializer = pointSelectConnectionInitializer
	case "select-update":
		benchmark = &SelectAndUpdateBenchmark{}
	case "read-large-result-set":
		benchmark = NewReadLargeResultSetBenchmark(readLatencyHistogram, numRows)
	case "read-narrow-result-set":
		benchmark = NewReadNarrowResultSetBenchmark(readLatencyHistogram, numRows)
	default:
		return fmt.Errorf("unsupported benchmark type: %s", benchmarkType)
	}

	db, err := createDatabase(configuration.Project, configuration.Instance, configuration.Database, configuration.Host, configuration.Threads)
	if err != nil {
		return fmt.Errorf("failed to build Spanner database/sql connection: %w", err)
	}
	connectionSupplier := NewConnectionSupplier(db, initializer)
	defer connectionSupplier.Close()

	benchmarkTypeAttribute := benchmark.Type()
	if configuration.Mock {
		benchmarkTypeAttribute = benchmarkTypeAttribute + "-mock"
	}

	attributeList := []attribute.KeyValue{
		attribute.String("benchmark_type", benchmarkTypeAttribute),
		attribute.String("tps", fmt.Sprintf("%.1f", tps)),
		attribute.Bool("for_alerting", configuration.ForAlerting),
		attribute.String("benchmark_name", configuration.BenchmarkName),
		attribute.String("client", "go-sql"),
		attribute.String("load_type", loadType.String()),
		attribute.Float64("burst_factor", burstFactor),
		attribute.Float64("burst_duration", burstDuration),
		attribute.Float64("burst_fraction", burstFraction),
		attribute.Int64("cycle_duration_ms", ParseDuration(cycleDurationString).Milliseconds()),
		attribute.Float64("peak_factor", peakFactor),
		attribute.String("transaction_type", "none"),
	}
	if benchmarkType == "read-large-result-set" || benchmarkType == "read-narrow-result-set" {
		attributeList = append(attributeList, attribute.Int64("num_rows", numRows))
	}
	attributes := metric.WithAttributes(attributeList...)

	runCtx = context.WithValue(runCtx, MetricAttributesKey, attributes)

	fmt.Printf("Starting %s for %s, target TPS: %.2f, workers: %d\n", benchmark.Name(), configuration.DurationStr, tps, configuration.Threads)

	var durationCtx context.Context
	if configuration.DurationStr != "inf" && configuration.DurationStr != "infinite" {
		duration := ParseDuration(configuration.DurationStr)
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

	resourceMonitor := NewResourceMonitor(memoryUsageHistogram, cpuUtilizationHistogram, configuration.ResourceProbeInterval, attributes)
	resourceMonitor.Start(durationCtx)
	defer resourceMonitor.Stop()

	runBenchmark(durationCtx, benchmark, connectionSupplier, latencyHistogram, operationCounter, errorCounter, configuration.Table, tps, configuration.Threads, 1, numRows, attributes, loadType, cycleDurationString, peakFactor, burstFactor, burstDuration, burstFraction)

	return nil
}

func getLatencyBuckets() []float64 {
	buckets := make([]float64, 0, 120)
	for bucket := 50.0; bucket <= 5000.0; bucket += 50.0 {
		buckets = append(buckets, bucket)
	}
	buckets = append(buckets, 6000.0, 7000.0, 8000.0, 9000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0, 20000.0, 25000.0, 30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0)
	return buckets
}

var testingMeterProvider metric.MeterProvider

func setupMetrics(ctx context.Context, projectID string, host string, benchmarkName string, noMetrics bool) (metric.Float64Histogram, metric.Float64Histogram, metric.Int64Counter, metric.Int64Counter, metric.Float64Histogram, metric.Float64Histogram, func(), error) {
	var meterProvider metric.MeterProvider
	cleanup := func() {}

	if testingMeterProvider != nil {
		meterProvider = testingMeterProvider
	} else if noMetrics || os.Getenv("SPANNER_EMULATOR_HOST") != "" || (host != "" && (strings.Contains(host, "localhost:") || strings.Contains(host, "127.0.0.1:"))) {
		histogram, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		readHistogram, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		operations, _ := noop.NewMeterProvider().Meter("").Int64Counter("")
		errorsCounter, _ := noop.NewMeterProvider().Meter("").Int64Counter("")
		memoryHistogram, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		cpuHistogram, _ := noop.NewMeterProvider().Meter("").Float64Histogram("")
		return histogram, readHistogram, operations, errorsCounter, memoryHistogram, cpuHistogram, func() {}, nil
	} else {
		exporter, err := mexporter.New(mexporter.WithProjectID(projectID))
		if err != nil {
			return nil, nil, nil, nil, nil, nil, nil, err
		}

		serviceName := benchmarkName
		if serviceName == "" {
			serviceName = "spanner-benchmark"
		}
		res, err := resource.Merge(
			resource.Default(),
			resource.NewWithAttributes(
				"",
				attribute.String("service.name", serviceName),
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

	megabyte := 1024.0 * 1024.0
	memoryUsageHistogram, err := meter.Float64Histogram(memoryUsageName,
		metric.WithDescription("Active memory usage in bytes"),
		metric.WithUnit("By"),
		metric.WithExplicitBucketBoundaries(2.5*megabyte, 5.0*megabyte, 7.5*megabyte, 10.0*megabyte, 20.0*megabyte, 30.0*megabyte, 40.0*megabyte, 50.0*megabyte, 60.0*megabyte, 70.0*megabyte, 80.0*megabyte, 90.0*megabyte, 100.0*megabyte, 200.0*megabyte, 300.0*megabyte, 400.0*megabyte, 500.0*megabyte, 750.0*megabyte, 1000.0*megabyte, 1500.0*megabyte, 2000.0*megabyte, 3000.0*megabyte, 5000.0*megabyte, 10000.0*megabyte),
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

func runBenchmark(ctx context.Context, benchmark Benchmark, supplier *ConnectionSupplier, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, tableName string, targetTPS float64, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption, loadType LoadType, cycleDurationStr string, peakFactor, burstFactor, burstDuration, burstFraction float64) {
	var statisticsMutex sync.Mutex
	var totalLatencyUs int64
	var totalOperations int64

	waitGroup := &sync.WaitGroup{}

	if loadType == LoadTypeClosedLoop {
		runClosedLoopBenchmark(ctx, benchmark, supplier, latencyHistogram, operationCounter, errorCounter, tableName, concurrentThreads, minId, maxId, attributes)
		return
	}

	tasks := make(chan struct{}, 1000000)

	for workerIndex := 0; workerIndex < concurrentThreads; workerIndex++ {
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()

			connection, err := supplier.CreateConnection(ctx)
			if err != nil {
				if !isContextDone(ctx, err) {
					log.Printf("Failed to create connection for worker: %v", err)
				}
				return
			}
			defer connection.Close()

			for {
				select {
				case <-ctx.Done():
					return
				case _, ok := <-tasks:
					if !ok {
						return
					}
					start := time.Now()
					err := benchmark.Execute(ctx, connection, tableName, minId, maxId)
					duration := time.Since(start)

					if err == nil {
						statisticsMutex.Lock()
						totalLatencyUs += duration.Microseconds()
						totalOperations++
						statisticsMutex.Unlock()
					}

					if benchmark.ShouldMeasureEntireMethod() {
						latencyHistogram.Record(ctx, float64(duration.Microseconds()), attributes)
					}
					operationCounter.Add(ctx, 1, attributes)
					if err != nil {
						if !isContextDone(ctx, err) {
							log.Printf("Operation failed: %v", err)
							errorCounter.Add(ctx, 1, attributes)
						}
					}
				}
			}
		}()
	}

	socketPath := os.Getenv("SPANNER_BENCHMARK_SOCKET")
	if socketPath != "" {
		runSocketTriggeredGenerator(ctx, socketPath, tasks)
	} else {
		generator, ok := generators[loadType]
		if !ok {
			log.Fatalf("No generator found for load type: %v", loadType)
		}
		generator.Run(ctx, targetTPS, cycleDurationStr, peakFactor, burstFactor, burstDuration, burstFraction, tasks, waitGroup)
	}

	waitGroup.Wait()
	if totalOperations > 0 {
		fmt.Printf("Local Run Complete - Total Ops: %d, Average Latency: %.2f us\n", totalOperations, float64(totalLatencyUs)/float64(totalOperations))
	}
}

func runClosedLoopBenchmark(ctx context.Context, benchmark Benchmark, supplier *ConnectionSupplier, latencyHistogram metric.Float64Histogram, operationCounter metric.Int64Counter, errorCounter metric.Int64Counter, tableName string, concurrentThreads int, minId, maxId int64, attributes metric.MeasurementOption) {
	var statisticsMutex sync.Mutex
	var totalLatencyUs int64
	var totalOperations int64

	waitGroup := &sync.WaitGroup{}

	for workerIndex := 0; workerIndex < concurrentThreads; workerIndex++ {
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()

			connection, err := supplier.CreateConnection(ctx)
			if err != nil {
				if !isContextDone(ctx, err) {
					log.Printf("Failed to create connection for closed-loop worker: %v", err)
				}
				return
			}
			defer connection.Close()

			for {
				select {
				case <-ctx.Done():
					return
				default:
					start := time.Now()
					err := benchmark.Execute(ctx, connection, tableName, minId, maxId)
					duration := time.Since(start)

					if err == nil {
						statisticsMutex.Lock()
						totalLatencyUs += duration.Microseconds()
						totalOperations++
						statisticsMutex.Unlock()
					}
					if benchmark.ShouldMeasureEntireMethod() {
						latencyHistogram.Record(ctx, float64(duration.Microseconds()), attributes)
					}
					operationCounter.Add(ctx, 1, attributes)
					if err != nil {
						if !isContextDone(ctx, err) {
							log.Printf("Operation failed: %v", err)
							errorCounter.Add(ctx, 1, attributes)
						}
					}
				}
			}
		}()
	}
	waitGroup.Wait()
	if totalOperations > 0 {
		fmt.Printf("Local Run Complete - Total Ops: %d, Average Latency: %.2f us\n", totalOperations, float64(totalLatencyUs)/float64(totalOperations))
	}
}

func runSocketTriggeredGenerator(ctx context.Context, socketPath string, tasks chan struct{}) {
	defer close(tasks)

	log.Printf("Connecting to workload generator sidecar socket: %s", socketPath)
	socketConnection, err := net.Dial("unix", socketPath)
	if err != nil {
		log.Fatalf("Failed to connect to sidecar socket: %v", err)
	}
	defer socketConnection.Close()

	// Send READY signal
	_, err = socketConnection.Write([]byte("READY\n"))
	if err != nil {
		log.Fatalf("Failed to send READY signal to sidecar: %v", err)
	}

	buffer := make([]byte, 1)
	for {
		_, err := socketConnection.Read(buffer)
		if err != nil {
			return
		}
		if buffer[0] == 0x01 {
			select {
			case tasks <- struct{}{}:
			case <-ctx.Done():
				return
			}
		}
	}
}

func isContextDone(ctx context.Context, err error) bool {
	if ctx.Err() != nil {
		return true
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	code := spanner.ErrCode(err)
	return code == codes.Canceled || code == codes.DeadlineExceeded
}
