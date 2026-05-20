package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"cloud.google.com/go/spanner"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	"github.com/urfave/cli/v3"
)

func executeTPCCBenchmark(ctx context.Context, cmd *cli.Command) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		cancel()
	}()

	cfg := parseGlobalConfig(cmd)
	warehouses := int(cmd.Int("warehouses"))
	clients := int(cmd.Int("clients"))
	items := int(cmd.Int("items"))

	latencyHistogram, _, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, cleanupMetrics, err := setupMetrics(runCtx, cfg.Project, cfg.Host)
	if err != nil {
		return fmt.Errorf("failed to initialize metrics: %w", err)
	}
	defer cleanupMetrics()

	client, err := createSpannerClient(runCtx, cfg.Project, cfg.Instance, cfg.Database, cfg.Host)
	if err != nil {
		return fmt.Errorf("failed to build Spanner client: %w", err)
	}
	defer client.Close()

	baseAttributeList := []attribute.KeyValue{
		attribute.String("benchmark_type", "tpcc"),
		attribute.Bool("for_alerting", cfg.ForAlerting),
		attribute.String("benchmark_name", cfg.BenchmarkName),
		attribute.String("client", "go-client"),
		attribute.Int("concurrent_clients", clients),
	}
	attributes := metric.WithAttributes(baseAttributeList...)

	attrNewOrder := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "new_order"))...)
	attrPayment := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "payment"))...)
	attrOrderStatus := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "order_status"))...)
	attrDelivery := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "delivery"))...)
	attrStockLevel := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "stock_level"))...)

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

	startResourceMonitoring(durationCtx, memoryUsageHistogram, cpuUtilizationHistogram, cfg.ResourceProbeInterval, attributes)

	fmt.Printf("Starting TPC-C Benchmark for %s, Scale Factor (Warehouses): %d, Parallel Clients: %d, Items: %d\n", cfg.DurationStr, warehouses, clients, items)

	// Assert database capacity
	singleIter := client.Single().Query(durationCtx, spanner.Statement{SQL: "SELECT COUNT(*) FROM warehouse"})
	row, err := singleIter.Next()
	singleIter.Stop()
	if err != nil {
		return fmt.Errorf("failed to query warehouse count: %w", err)
	}
	if row != nil {
		var warehouseCount int64
		if err := row.Column(0, &warehouseCount); err != nil {
			return fmt.Errorf("failed to parse warehouse count: %w", err)
		}
		if warehouseCount < int64(warehouses) {
			return fmt.Errorf("database capacity check failed: Required scale factor %d warehouses, but database only has %d", warehouses, warehouseCount)
		}
	}

	wg := &sync.WaitGroup{}

	for i := 0; i < clients; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-durationCtx.Done():
					return
				default:
					prob := rand.Intn(100)
					var err error
					var attr metric.MeasurementOption
					txType := "new_order"

					start := time.Now()
					if prob < 45 {
						txType = "new_order"
						attr = attrNewOrder
						err = executeNewOrder(durationCtx, client, warehouses, items)
					} else if prob < 88 {
						txType = "payment"
						attr = attrPayment
						err = executePayment(durationCtx, client, warehouses)
					} else if prob < 92 {
						txType = "order_status"
						attr = attrOrderStatus
						err = executeOrderStatus(durationCtx, client, warehouses)
					} else if prob < 96 {
						txType = "delivery"
						attr = attrDelivery
						err = executeDelivery(durationCtx, client, warehouses)
					} else {
						txType = "stock_level"
						attr = attrStockLevel
						err = executeStockLevel(durationCtx, client, warehouses)
					}

					if err != nil {
						log.Printf("TPC-C transaction %s failed: %v", txType, err)
						errorCounter.Add(runCtx, 1, attr)
					} else {
						latencyHistogram.Record(runCtx, float64(time.Since(start).Microseconds()), attr)
					}
					operationCounter.Add(runCtx, 1, attr)
				}
			}
		}()
	}

	wg.Wait()
	fmt.Println("TPC-C benchmark execution complete.")
	return nil
}
