package com.google.cloud.spanner.benchmark.tpcc;

import com.google.cloud.spanner.BatchClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.benchmark.AbstractBenchmark;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class TpccBenchmark {

  private final DatabaseClient client;
  private final BatchClient batchClient;
  private final LongHistogram latencyHistogram;
  private final LongCounter operationCounter;
  private final LongCounter errorCounter;
  private final int scaleFactor;
  private final int clients;
  private final int items;
  private final Duration duration;
  private final boolean extended;
  private final Attributes baseAttributes;
  private final Attributes newOrderAttributes;
  private final Attributes newOrderMutationsAttributes;
  private final Attributes paymentAttributes;
  private final Attributes paymentMutationsDirectAttributes;
  private final Attributes orderStatusAttributes;
  private final Attributes orderStatusReadsAttributes;
  private final Attributes deliveryAttributes;
  private final Attributes stockLevelAttributes;
  private final Attributes stockLevelPartitionedAttributes;

  private final LongHistogram memoryUsageHistogram;
  private final DoubleHistogram cpuUtilizationHistogram;
  private final String resourceProbeInterval;
  private java.util.concurrent.ScheduledExecutorService resourceMonitorExecutor;

  public TpccBenchmark(
      DatabaseClient client,
      BatchClient batchClient,
      LongHistogram latencyHistogram,
      LongCounter operationCounter,
      LongCounter errorCounter,
      LongHistogram memoryUsageHistogram,
      DoubleHistogram cpuUtilizationHistogram,
      String resourceProbeInterval,
      int scaleFactor,
      int clients,
      int items,
      Duration duration,
      boolean forAlerting,
      String benchmarkName,
      boolean extended) {
    this.client = client;
    this.batchClient = batchClient;
    this.latencyHistogram = latencyHistogram;
    this.operationCounter = operationCounter;
    this.errorCounter = errorCounter;
    this.memoryUsageHistogram = memoryUsageHistogram;
    this.cpuUtilizationHistogram = cpuUtilizationHistogram;
    this.resourceProbeInterval = resourceProbeInterval;
    this.scaleFactor = scaleFactor;
    this.clients = clients;
    this.items = items;
    this.duration = duration;
    this.extended = extended;

    AttributesBuilder baseAttributesBuilder =
        Attributes.builder()
            .put("benchmark_type", "tpcc")
            .put("for_alerting", forAlerting)
            .put("benchmark_name", benchmarkName != null ? benchmarkName : "")
            .put("client", "java-client")
            .put("concurrent_clients", clients);
    if (extended) {
      baseAttributesBuilder.put("extended", true);
    }
    this.baseAttributes = baseAttributesBuilder.build();

    this.newOrderAttributes =
        baseAttributes.toBuilder().put("transaction_type", "new_order").build();
    this.newOrderMutationsAttributes =
        baseAttributes.toBuilder().put("transaction_type", "new_order_mutations").build();
    this.paymentAttributes = baseAttributes.toBuilder().put("transaction_type", "payment").build();
    this.paymentMutationsDirectAttributes =
        baseAttributes.toBuilder().put("transaction_type", "payment_mutations_direct").build();
    this.orderStatusAttributes =
        baseAttributes.toBuilder().put("transaction_type", "order_status").build();
    this.orderStatusReadsAttributes =
        baseAttributes.toBuilder().put("transaction_type", "order_status_reads").build();
    this.deliveryAttributes =
        baseAttributes.toBuilder().put("transaction_type", "delivery").build();
    this.stockLevelAttributes =
        baseAttributes.toBuilder().put("transaction_type", "stock_level").build();
    this.stockLevelPartitionedAttributes =
        baseAttributes.toBuilder().put("transaction_type", "stock_level_partitioned").build();
  }

  public void run() {
    System.out.println(
        "Starting TPC-C Benchmark with Scale Factor (Warehouses): "
            + scaleFactor
            + ", Parallel Clients: "
            + clients
            + ", Items: "
            + items
            + (extended ? " [EXTENDED MODE]" : ""));

    startResourceMonitoring();

    // Assert database capacity
    try (ResultSet rs =
        client.singleUse().executeQuery(Statement.of("SELECT COUNT(*) FROM warehouse"))) {
      if (rs.next()) {
        long warehouseCount = rs.getLong(0);
        if (warehouseCount < scaleFactor) {
          throw new IllegalStateException(
              "Database capacity check failed: Required scale factor "
                  + scaleFactor
                  + " warehouses, but database only has "
                  + warehouseCount);
        }
      }
    }

    ExecutorService executor = Executors.newFixedThreadPool(clients);
    long startTimeMs = System.currentTimeMillis();

    for (int c = 0; c < clients; c++) {
      executor.submit(
          () -> {
            while (!Thread.currentThread().isInterrupted()) {
              if (duration != null
                  && (System.currentTimeMillis() - startTimeMs) >= duration.toMillis()) {
                break;
              }

              int prob = ThreadLocalRandom.current().nextInt(100);
              Attributes currentAttributes = baseAttributes;
              long startNs = System.nanoTime();
              boolean success = false;

              try {
                if (extended) {
                  if (prob < 25) {
                    currentAttributes = newOrderAttributes;
                    TpccTransactions.executeNewOrder(client, scaleFactor, items, true);
                  } else if (prob < 45) {
                    currentAttributes = newOrderMutationsAttributes;
                    TpccTransactions.executeNewOrderMutations(client, scaleFactor, items);
                  } else if (prob < 78) {
                    currentAttributes = paymentAttributes;
                    TpccTransactions.executePayment(client, scaleFactor, true);
                  } else if (prob < 88) {
                    currentAttributes = paymentMutationsDirectAttributes;
                    TpccTransactions.executePaymentMutationsDirect(client, scaleFactor);
                  } else if (prob < 90) {
                    currentAttributes = orderStatusAttributes;
                    TpccTransactions.executeOrderStatus(client, scaleFactor, true);
                  } else if (prob < 92) {
                    currentAttributes = orderStatusReadsAttributes;
                    TpccTransactions.executeOrderStatusReads(client, scaleFactor);
                  } else if (prob < 96) {
                    currentAttributes = deliveryAttributes;
                    TpccTransactions.executeDelivery(client, scaleFactor, true);
                  } else if (prob < 98) {
                    currentAttributes = stockLevelAttributes;
                    TpccTransactions.executeStockLevel(client, scaleFactor, true);
                  } else {
                    currentAttributes = stockLevelPartitionedAttributes;
                    TpccTransactions.executeStockLevelPartitioned(batchClient, scaleFactor);
                  }
                } else {
                  if (prob < 45) {
                    currentAttributes = newOrderAttributes;
                    TpccTransactions.executeNewOrder(client, scaleFactor, items, false);
                  } else if (prob < 88) {
                    currentAttributes = paymentAttributes;
                    TpccTransactions.executePayment(client, scaleFactor, false);
                  } else if (prob < 92) {
                    currentAttributes = orderStatusAttributes;
                    TpccTransactions.executeOrderStatus(client, scaleFactor, false);
                  } else if (prob < 96) {
                    currentAttributes = deliveryAttributes;
                    TpccTransactions.executeDelivery(client, scaleFactor, false);
                  } else {
                    currentAttributes = stockLevelAttributes;
                    TpccTransactions.executeStockLevel(client, scaleFactor, false);
                  }
                }
                success = true;
              } catch (Exception e) {
                e.printStackTrace();
                errorCounter.add(1, currentAttributes);
              } finally {
                if (success) {
                  long latencyUs = (System.nanoTime() - startNs) / 1000;
                  latencyHistogram.record(latencyUs, currentAttributes);
                }
                operationCounter.add(1, currentAttributes);
              }
            }
          });
    }

    try {
      if (duration != null) {
        Thread.sleep(duration.toMillis());
      } else {
        Thread.sleep(Long.MAX_VALUE);
      }
      System.out.println("TPC-C duration complete. Shutting down pool...");
      if (resourceMonitorExecutor != null) {
        resourceMonitorExecutor.shutdownNow();
      }
      executor.shutdownNow();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (resourceMonitorExecutor != null) {
        resourceMonitorExecutor.shutdownNow();
      }
      executor.shutdownNow();
    }
  }

  private void startResourceMonitoring() {
    if (resourceProbeInterval != null && !resourceProbeInterval.isEmpty()) {
      Duration probeDuration = AbstractBenchmark.parseDuration(resourceProbeInterval);
      if (probeDuration != null && probeDuration.toMillis() > 0) {
        resourceMonitorExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        resourceMonitorExecutor.scheduleAtFixedRate(
            this::probeResourceUsage, 0, probeDuration.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
  }

  private void probeResourceUsage() {
    try {
      long usedMemory =
          java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      if (memoryUsageHistogram != null) {
        memoryUsageHistogram.record(usedMemory, baseAttributes);
      }
      java.lang.management.OperatingSystemMXBean osBean =
          java.lang.management.ManagementFactory.getOperatingSystemMXBean();
      if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
        double cpuLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
        if (cpuLoad >= 0 && cpuUtilizationHistogram != null) {
          cpuUtilizationHistogram.record(cpuLoad, baseAttributes);
        }
      }
    } catch (Exception e) {
      // Ignore exceptions in resource monitoring
    }
  }
}
