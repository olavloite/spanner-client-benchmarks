package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"cloud.google.com/go/spanner"
	"github.com/urfave/cli/v3"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"spanner-go-benchmark/ycsb"
)

// YcsbBenchmarkAdapter adapts YcsbBenchmarkState to the general Benchmark interface.
type YcsbBenchmarkAdapter struct {
	state *ycsb.YcsbBenchmarkState
}

func (a *YcsbBenchmarkAdapter) Name() string {
	return fmt.Sprintf("YCSB Benchmark (%s)", a.state.Workload())
}

func (a *YcsbBenchmarkAdapter) Type() string {
	return fmt.Sprintf("ycsb-%s", strings.ToLower(string(a.state.Workload())))
}

func (a *YcsbBenchmarkAdapter) ShouldMeasureEntireMethod() bool {
	return true
}

func (a *YcsbBenchmarkAdapter) Execute(ctx context.Context, client *spanner.Client, tableName string, minId, maxId int64) error {
	_, _, err := a.state.RunStep(ctx, client)
	return err
}

func executeYCSBBenchmark(ctx context.Context, cmd *cli.Command) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		cancel()
	}()

	cfg := parseGlobalConfig(cmd)
	if cfg.Mock {
		mockSrv, host, mockCleanup := startMockServer()
		defer mockCleanup()
		registerMockResults(mockSrv)
		cfg.Host = host
	}

	workloadStr := cmd.String("workload")
	workload, err := ycsb.ParseWorkload(workloadStr)
	if err != nil {
		return err
	}

	distStr := cmd.String("distribution")
	distribution, err := ycsb.ParseDistribution(distStr)
	if err != nil {
		return err
	}

	recordCount := int64(cmd.Int("record-count"))
	zeroPadding := int(cmd.Int("zero-padding"))
	fieldCount := int(cmd.Int("field-count"))
	fieldLength := int(cmd.Int("field-length"))
	useReadRow := cmd.Bool("use-read-row")
	tps := cmd.Float("tps")
	tableName := cfg.Table
	if tableName == "" {
		tableName = "usertable"
	}
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

	err = ValidateAndApplyDefaults(cmd, loadType, &cycleDurationStr, &peakFactor, &burstFactor, &burstDuration, &burstFraction)
	if err != nil {
		return err
	}

	latencyHistogram, _, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, cleanupMetrics, err := setupMetrics(runCtx, cfg.Project, cfg.Host, cfg.BenchmarkName, cfg.NoMetrics)
	if err != nil {
		return fmt.Errorf("failed to initialize metrics: %w", err)
	}
	defer cleanupMetrics()

	client, err := createSpannerClient(runCtx, cfg.Project, cfg.Instance, cfg.Database, cfg.Host)
	if err != nil {
		return fmt.Errorf("failed to build Spanner client: %w", err)
	}
	defer client.Close()

	state := ycsb.NewYcsbBenchmarkState(
		workload,
		distribution,
		recordCount,
		zeroPadding,
		fieldCount,
		fieldLength,
		useReadRow,
		cfg.Mock,
		tableName,
	)

	benchmarkTypeAttr := "ycsb"
	if cfg.Mock {
		benchmarkTypeAttr = "ycsb-mock"
	}

	attributeList := []attribute.KeyValue{
		attribute.String("benchmark_type", benchmarkTypeAttr),
		attribute.String("workload", string(workload)),
		attribute.String("transaction_type", "ycsb-"+strings.ToLower(string(workload))),
		attribute.String("tps", fmt.Sprintf("%.1f", tps)),
		attribute.Bool("for_alerting", cfg.ForAlerting),
		attribute.String("benchmark_name", cfg.BenchmarkName),
		attribute.String("client", "go-client"),
		attribute.String("load_type", loadType.String()),
		attribute.Float64("burst_factor", burstFactor),
		attribute.Float64("burst_duration", burstDuration),
		attribute.Float64("burst_fraction", burstFraction),
		attribute.Int64("cycle_duration_ms", ParseDuration(cycleDurationStr).Milliseconds()),
		attribute.Float64("peak_factor", peakFactor),
	}
	attributes := metric.WithAttributes(attributeList...)

	runCtx = context.WithValue(runCtx, MetricAttributesKey, attributes)

	fmt.Printf("Starting YCSB Benchmark (%s) for %s, target TPS: %.2f, threads: %d\n", workload, cfg.DurationStr, tps, cfg.Threads)

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

	rm := NewResourceMonitor(memoryUsageHistogram, cpuUtilizationHistogram, cfg.ResourceProbeInterval, attributes)
	rm.Start(durationCtx)
	defer rm.Stop()

	adapter := &YcsbBenchmarkAdapter{state: state}
	runBenchmark(durationCtx, adapter, client, latencyHistogram, operationCounter, errorCounter, tableName, tps, cfg.Threads, 0, 0, attributes, loadType, cycleDurationStr, peakFactor, burstFactor, burstDuration, burstFraction)

	state.PrintSummary()

	return nil
}

func executeYCSBInit(ctx context.Context, cmd *cli.Command) error {
	cfg := parseGlobalConfig(cmd)
	if cfg.Mock {
		mockSrv, host, mockCleanup := startMockServer()
		defer mockCleanup()
		registerMockResults(mockSrv)
		cfg.Host = host
	}

	tableName := cfg.Table
	if tableName == "" {
		tableName = "usertable"
	}
	recordCount := int64(cmd.Int("record-count"))
	zeroPadding := int(cmd.Int("zero-padding"))
	fieldCount := int(cmd.Int("field-count"))
	fieldLength := int(cmd.Int("field-length"))
	batchSize := int(cmd.Int("batch-size"))
	threads := int(cmd.Int("threads"))
	skipSchema := cmd.Bool("skip-schema")
	skipData := cmd.Bool("skip-data")

	client, err := createSpannerClient(ctx, cfg.Project, cfg.Instance, cfg.Database, cfg.Host)
	if err != nil {
		return fmt.Errorf("failed to create Spanner client: %w", err)
	}
	defer client.Close()

	if !skipSchema {
		exists := false
		if !cfg.Mock {
			var checkErr error
			exists, checkErr = ycsb.TableExists(ctx, client, tableName)
			if checkErr != nil {
				log.Printf("Warning: failed to check table existence: %v", checkErr)
			}
		}

		if exists {
			log.Printf("Table %s already exists, skipping schema creation.", tableName)
		} else if !cfg.Mock {
			log.Printf("Creating schema for table %s...", tableName)
			ddl := ycsb.GenerateSchemaDDL(tableName, fieldCount)
			if err := ycsb.InitSchema(ctx, cfg.Project, cfg.Instance, cfg.Database, cfg.Host, []string{ddl}); err != nil {
				return fmt.Errorf("failed to initialize schema: %w", err)
			}
			log.Printf("Schema created successfully.")
		}
	}

	if !skipData {
		if err := ycsb.PopulateData(ctx, client, tableName, recordCount, zeroPadding, fieldCount, fieldLength, batchSize, threads); err != nil {
			return fmt.Errorf("failed to populate data: %w", err)
		}
	}

	return nil
}
