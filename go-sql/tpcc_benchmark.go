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

	"github.com/urfave/cli/v3"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

func executeTPCCBenchmark(ctx context.Context, cmd *cli.Command) error {
	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-signals
		cancel()
	}()

	configuration := parseGlobalConfig(cmd)
	if configuration.Mock {
		mockServer, host, mockCleanup := startMockServer()
		defer mockCleanup()
		registerMockResults(mockServer)
		configuration.Host = host
	}
	warehouses := int(cmd.Int("warehouses"))
	clients := int(cmd.Int("clients"))
	items := int(cmd.Int("items"))
	extended := cmd.Bool("extended")

	latencyHistogram, _, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, cleanupMetrics, err := setupMetrics(runCtx, configuration.Project, configuration.Host, configuration.BenchmarkName, configuration.NoMetrics)
	if err != nil {
		return fmt.Errorf("failed to initialize metrics: %w", err)
	}
	defer cleanupMetrics()

	db, err := createDatabase(configuration.Project, configuration.Instance, configuration.Database, configuration.Host, clients)
	if err != nil {
		return fmt.Errorf("failed to build Spanner database/sql connection: %w", err)
	}
	connectionSupplier := NewConnectionSupplier(db, nil)
	defer connectionSupplier.Close()

	baseAttributeList := []attribute.KeyValue{
		attribute.String("benchmark_type", "tpcc"),
		attribute.Bool("for_alerting", configuration.ForAlerting),
		attribute.String("benchmark_name", configuration.BenchmarkName),
		attribute.String("client", "go-sql"),
		attribute.Int("concurrent_clients", clients),
	}
	if extended {
		baseAttributeList = append(baseAttributeList, attribute.Bool("extended", true))
	}
	attributes := metric.WithAttributes(baseAttributeList...)

	attrNewOrder := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "new_order"))...)
	attrNewOrderMutations := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "new_order_mutations"))...)
	attrPayment := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "payment"))...)
	attrPaymentMutationsDirect := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "payment_mutations_direct"))...)
	attrOrderStatus := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "order_status"))...)
	attrOrderStatusReads := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "order_status_reads"))...)
	attrDelivery := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "delivery"))...)
	attrStockLevel := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "stock_level"))...)
	attrStockLevelPartitioned := metric.WithAttributes(append(baseAttributeList, attribute.String("transaction_type", "stock_level_partitioned"))...)

	// Setup duration context timeout if not infinite
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

	extendedModeString := ""
	if extended {
		extendedModeString = " [EXTENDED MODE]"
	}
	fmt.Printf("Starting TPC-C Benchmark for %s, Scale Factor (Warehouses): %d, Parallel Clients: %d, Items: %d%s\n", configuration.DurationStr, warehouses, clients, items, extendedModeString)

	// Assert database capacity
	initialConnection, err := connectionSupplier.CreateConnection(durationCtx)
	if err != nil {
		return fmt.Errorf("failed to get connection for database capacity check: %w", err)
	}
	var warehouseCount int64
	row := initialConnection.QueryRowContext(durationCtx, "SELECT COUNT(*) FROM warehouse")
	if err := row.Scan(&warehouseCount); err != nil {
		_ = initialConnection.Close()
		return fmt.Errorf("failed to query warehouse count: %w", err)
	}
	_ = initialConnection.Close()

	if warehouseCount < int64(warehouses) {
		return fmt.Errorf("database capacity check failed: Required scale factor %d warehouses, but database only has %d", warehouses, warehouseCount)
	}

	waitGroup := &sync.WaitGroup{}

	for clientIndex := 0; clientIndex < clients; clientIndex++ {
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()

			connection, err := connectionSupplier.CreateConnection(durationCtx)
			if err != nil {
				log.Printf("Failed to create connection for TPC-C worker: %v", err)
				return
			}
			defer connection.Close()

			for {
				select {
				case <-durationCtx.Done():
					return
				default:
					probability := rand.Intn(100)
					var transactionError error
					var currentAttributes metric.MeasurementOption
					transactionType := "new_order"

					start := time.Now()
					if extended {
						if probability < 25 {
							transactionType = "new_order"
							currentAttributes = attrNewOrder
							transactionError = executeNewOrder(durationCtx, connection, warehouses, items, true)
						} else if probability < 45 {
							transactionType = "new_order_mutations"
							currentAttributes = attrNewOrderMutations
							transactionError = executeNewOrderMutations(durationCtx, connection, warehouses, items)
						} else if probability < 78 {
							transactionType = "payment"
							currentAttributes = attrPayment
							transactionError = executePayment(durationCtx, connection, warehouses, true)
						} else if probability < 88 {
							transactionType = "payment_mutations_direct"
							currentAttributes = attrPaymentMutationsDirect
							transactionError = executePaymentMutationsDirect(durationCtx, connection, warehouses)
						} else if probability < 90 {
							transactionType = "order_status"
							currentAttributes = attrOrderStatus
							transactionError = executeOrderStatus(durationCtx, connection, warehouses, true)
						} else if probability < 92 {
							transactionType = "order_status_reads"
							currentAttributes = attrOrderStatusReads
							transactionError = executeOrderStatusReads(durationCtx, connection, warehouses)
						} else if probability < 96 {
							transactionType = "delivery"
							currentAttributes = attrDelivery
							transactionError = executeDelivery(durationCtx, connection, warehouses, true)
						} else if probability < 98 {
							transactionType = "stock_level"
							currentAttributes = attrStockLevel
							transactionError = executeStockLevel(durationCtx, connection, warehouses, true)
						} else {
							transactionType = "stock_level_partitioned"
							currentAttributes = attrStockLevelPartitioned
							transactionError = executeStockLevelPartitioned(durationCtx, connection, warehouses)
						}
					} else {
						if probability < 45 {
							transactionType = "new_order"
							currentAttributes = attrNewOrder
							transactionError = executeNewOrder(durationCtx, connection, warehouses, items, false)
						} else if probability < 88 {
							transactionType = "payment"
							currentAttributes = attrPayment
							transactionError = executePayment(durationCtx, connection, warehouses, false)
						} else if probability < 92 {
							transactionType = "order_status"
							currentAttributes = attrOrderStatus
							transactionError = executeOrderStatus(durationCtx, connection, warehouses, false)
						} else if probability < 96 {
							transactionType = "delivery"
							currentAttributes = attrDelivery
							transactionError = executeDelivery(durationCtx, connection, warehouses, false)
						} else {
							transactionType = "stock_level"
							currentAttributes = attrStockLevel
							transactionError = executeStockLevel(durationCtx, connection, warehouses, false)
						}
					}

					if transactionError != nil {
						if !isContextDone(durationCtx, transactionError) {
							log.Printf("TPC-C transaction %s failed: %v", transactionType, transactionError)
							errorCounter.Add(runCtx, 1, currentAttributes)
						}
					} else {
						latencyHistogram.Record(runCtx, float64(time.Since(start).Microseconds()), currentAttributes)
					}
					operationCounter.Add(runCtx, 1, currentAttributes)
				}
			}
		}()
	}

	waitGroup.Wait()
	fmt.Println("TPC-C benchmark execution complete.")
	return nil
}
