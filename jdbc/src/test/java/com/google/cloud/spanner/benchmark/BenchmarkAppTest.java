package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import java.util.Collection;
import org.junit.Test;
import picocli.CommandLine;

public class BenchmarkAppTest extends AbstractBenchmarkTest {

  @Test
  public void testPointSelectBenchmarkRuns() throws Exception {
    Thread applicationThread =
        new Thread(
            () -> {
              try {
                new CommandLine(new BenchmarkApp())
                    .execute(
                        "-p",
                        "my-project",
                        "-i",
                        "my-instance",
                        "-d",
                        "my-database",
                        "--host",
                        "http://localhost:" + port,
                        "point-select",
                        "-t",
                        "my_table",
                        "--tps",
                        "10",
                        "--threads",
                        "2");
              } catch (Exception exception) {
                System.out.println("App terminated: " + exception.getMessage());
              }
            });

    applicationThread.start();

    waitForRequest(
        request -> request.getSql().contains("SELECT * FROM my_table"), applicationThread);

    applicationThread.interrupt();
    applicationThread.join(5000);
    assertTrue("Application thread should have finished", !applicationThread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testSelectAndUpdateBenchmarkRuns() throws Exception {
    Thread applicationThread =
        new Thread(
            () -> {
              try {
                new CommandLine(new BenchmarkApp())
                    .execute(
                        "-p",
                        "my-project",
                        "-i",
                        "my-instance",
                        "-d",
                        "my-database",
                        "--host",
                        "http://localhost:" + port,
                        "select-update",
                        "-t",
                        "my_table",
                        "--tps",
                        "10",
                        "--threads",
                        "2");
              } catch (Exception exception) {
                System.out.println("App terminated: " + exception.getMessage());
              }
            });

    applicationThread.start();

    waitForRequest(
        request ->
            request.getSql().contains("UPDATE my_table")
                || request.getSql().contains("INSERT INTO my_table"),
        applicationThread);

    applicationThread.interrupt();
    applicationThread.join(5000);
    assertTrue("Application thread should have finished", !applicationThread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testPointSelectBenchmarkSpikyRuns() throws Exception {
    Thread applicationThread =
        new Thread(
            () -> {
              try {
                new CommandLine(new BenchmarkApp())
                    .execute(
                        "-p",
                        "my-project",
                        "-i",
                        "my-instance",
                        "-d",
                        "my-database",
                        "--host",
                        "http://localhost:" + port,
                        "point-select",
                        "-t",
                        "my_table",
                        "--tps",
                        "10",
                        "--threads",
                        "2",
                        "--load-type",
                        "SPIKY",
                        "--burst-factor",
                        "2.0",
                        "--burst-duration",
                        "0.5",
                        "--burst-fraction",
                        "0.2");
              } catch (Exception exception) {
                System.out.println("App terminated: " + exception.getMessage());
              }
            });

    applicationThread.start();

    waitForRequest(
        request -> request.getSql().contains("SELECT * FROM my_table"), applicationThread);

    applicationThread.interrupt();
    applicationThread.join(5000);
    assertTrue("Application thread should have finished", !applicationThread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testPointSelectBenchmarkGradualRuns() throws Exception {
    Thread applicationThread =
        new Thread(
            () -> {
              try {
                new CommandLine(new BenchmarkApp())
                    .execute(
                        "-p",
                        "my-project",
                        "-i",
                        "my-instance",
                        "-d",
                        "my-database",
                        "--host",
                        "http://localhost:" + port,
                        "point-select",
                        "-t",
                        "my_table",
                        "--tps",
                        "10",
                        "--threads",
                        "2",
                        "--load-type",
                        "GRADUAL",
                        "--cycle-duration",
                        "10s",
                        "--peak-factor",
                        "2.0");
              } catch (Exception exception) {
                System.out.println("App terminated: " + exception.getMessage());
              }
            });

    applicationThread.start();

    waitForRequest(
        request -> request.getSql().contains("SELECT * FROM my_table"), applicationThread);

    applicationThread.interrupt();
    applicationThread.join(5000);
    assertTrue("Application thread should have finished", !applicationThread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testReadLargeResultSetBenchmarkRuns() throws Exception {
    Thread applicationThread =
        new Thread(
            () -> {
              try {
                new CommandLine(new BenchmarkApp())
                    .execute(
                        "-p",
                        "my-project",
                        "-i",
                        "my-instance",
                        "-d",
                        "my-database",
                        "--host",
                        "http://localhost:" + port,
                        "read-large-result-set",
                        "-t",
                        "my_table",
                        "--num-rows",
                        "10",
                        "--tps",
                        "10",
                        "--threads",
                        "2");
              } catch (Exception exception) {
                System.out.println("App terminated: " + exception.getMessage());
              }
            });

    applicationThread.start();

    waitForRequest(
        request -> request.getSql().contains("SELECT\n  MOD(FARM_FINGERPRINT"), applicationThread);

    applicationThread.interrupt();
    applicationThread.join(5000);
    assertTrue("Application thread should have finished", !applicationThread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testReadNarrowResultSetBenchmarkRuns() throws Exception {
    Thread applicationThread =
        new Thread(
            () -> {
              try {
                new CommandLine(new BenchmarkApp())
                    .execute(
                        "-p",
                        "my-project",
                        "-i",
                        "my-instance",
                        "-d",
                        "my-database",
                        "--host",
                        "http://localhost:" + port,
                        "read-narrow-result-set",
                        "-t",
                        "my_table",
                        "--num-rows",
                        "10",
                        "--tps",
                        "10",
                        "--threads",
                        "2");
              } catch (Exception exception) {
                System.out.println("App terminated: " + exception.getMessage());
              }
            });

    applicationThread.start();

    waitForRequest(request -> request.getSql().contains("AS random_int64_1"), applicationThread);

    applicationThread.interrupt();
    applicationThread.join(5000);
    assertTrue("Application thread should have finished", !applicationThread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testTpccBenchmarkRuns() throws Exception {
    Thread applicationThread =
        new Thread(
            () -> {
              try {
                new CommandLine(new BenchmarkApp())
                    .execute(
                        "-p",
                        "my-project",
                        "-i",
                        "my-instance",
                        "-d",
                        "my-database",
                        "--duration",
                        "2s",
                        "--host",
                        "http://localhost:" + port,
                        "tpcc",
                        "--warehouses",
                        "1",
                        "--clients",
                        "2",
                        "--items",
                        "100");
              } catch (Exception exception) {
                System.out.println("App terminated: " + exception.getMessage());
              }
            });

    applicationThread.start();

    waitForRequest(
        request -> request.getSql().contains("SELECT COUNT(*) FROM warehouse"), applicationThread);

    applicationThread.join(10000);
  }

  @Test
  public void testMetricsCollection() throws Exception {
    Thread applicationThread =
        new Thread(
            () -> {
              try {
                new CommandLine(new BenchmarkApp())
                    .execute(
                        "-p",
                        "my-project",
                        "-i",
                        "my-instance",
                        "-d",
                        "my-database",
                        "--host",
                        "http://localhost:" + port,
                        "--resource-probe-interval",
                        "1s",
                        "point-select",
                        "-t",
                        "my_table",
                        "--tps",
                        "10",
                        "--threads",
                        "2");
              } catch (Exception exception) {
                System.out.println("App terminated: " + exception.getMessage());
              }
            });

    applicationThread.start();

    long startTime = System.currentTimeMillis();
    boolean hasMetrics = false;
    boolean verifiedAttributes = false;
    boolean hasMemoryUsage = false;
    boolean hasCpuUtilization = false;

    while (System.currentTimeMillis() - startTime < 6000) {
      Collection<MetricData> metricData = metricReader.collectAllMetrics();
      MetricData operationCount =
          metricData.stream()
              .filter(metric -> metric.getName().equals(BenchmarkApp.OPERATION_COUNT_NAME))
              .findFirst()
              .orElse(null);
      MetricData memoryUsage =
          metricData.stream()
              .filter(metric -> metric.getName().equals(BenchmarkApp.MEMORY_USAGE_NAME))
              .findFirst()
              .orElse(null);
      MetricData cpuUtilization =
          metricData.stream()
              .filter(metric -> metric.getName().equals(BenchmarkApp.CPU_UTILIZATION_NAME))
              .findFirst()
              .orElse(null);

      if (memoryUsage != null && !memoryUsage.getData().getPoints().isEmpty()) {
        hasMemoryUsage = true;
      }
      if (cpuUtilization != null && !cpuUtilization.getData().getPoints().isEmpty()) {
        hasCpuUtilization = true;
      }

      if (operationCount != null && !operationCount.getData().getPoints().isEmpty()) {
        PointData firstPoint = (PointData) operationCount.getData().getPoints().iterator().next();
        String client = firstPoint.getAttributes().get(AttributeKey.stringKey("client"));
        String benchmarkType =
            firstPoint.getAttributes().get(AttributeKey.stringKey("benchmark_type"));

        if ("java-jdbc".equals(client) && "point-select".equals(benchmarkType)) {
          verifiedAttributes = true;
        }
      }

      if (verifiedAttributes && hasMemoryUsage && hasCpuUtilization) {
        hasMetrics = true;
        break;
      }
      Thread.sleep(100);
    }

    assertTrue("Should have collected and verified operation count attributes", verifiedAttributes);
    assertTrue("Should have collected memory usage metric", hasMemoryUsage);
    assertTrue("Should have collected CPU utilization metric", hasCpuUtilization);
    assertTrue("Should have verified all telemetry metrics correctly", hasMetrics);

    applicationThread.interrupt();
    applicationThread.join(5000);
    assertNoErrors();
  }

  @Test
  public void testSdkMetricsDropped() {
    SimpleMetricReader reader = new SimpleMetricReader();
    SdkMeterProvider provider =
        SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .registerView(
                InstrumentSelector.builder().setName("otel.sdk.*").build(),
                View.builder().setAggregation(Aggregation.drop()).build())
            .build();
    Meter meter = provider.get("io.opentelemetry.sdk.metrics");
    meter.histogramBuilder("otel.sdk.metric_reader.collection.duration").build().record(10.0);
    assertTrue(
        "otel.sdk.* metrics should be dropped by the view configuration",
        reader.collectAllMetrics().isEmpty());
  }
}
