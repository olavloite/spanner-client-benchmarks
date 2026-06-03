package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BenchmarkAppTest extends AbstractBenchmarkTest {

  @Test
  public void testPointSelectBenchmarkRuns() throws Exception {
    Thread thread =
        new Thread(
            () -> {
              try {
                new picocli.CommandLine(new BenchmarkApp())
                    .execute(
                        new String[] {
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
                          "1000",
                          "--threads",
                          "10"
                        });
              } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
              }
            });

    thread.start();

    waitForRequest(r -> r.getSql().contains("SELECT * FROM my_table"));

    // Interrupt the thread to stop the infinite loop
    thread.interrupt();
    thread.join(5000); // Wait for it to finish
    assertTrue("Thread should have finished", !thread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testSelectAndUpdateBenchmarkRuns() throws Exception {
    Thread thread =
        new Thread(
            () -> {
              try {
                new picocli.CommandLine(new BenchmarkApp())
                    .execute(
                        new String[] {
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
                          "1000",
                          "--threads",
                          "10"
                        });
              } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
              }
            });

    thread.start();

    waitForRequest(
        r -> r.getSql().contains("UPDATE my_table") || r.getSql().contains("INSERT INTO my_table"));

    // Interrupt the thread to stop the infinite loop
    thread.interrupt();
    thread.join(5000); // Wait for it to finish
    assertTrue("Thread should have finished", !thread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testPointSelectBenchmarkSpikyRuns() throws Exception {
    Thread thread =
        new Thread(
            () -> {
              try {
                new picocli.CommandLine(new BenchmarkApp())
                    .execute(
                        new String[] {
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
                          "5000",
                          "--threads",
                          "10",
                          "--load-type",
                          "SPIKY",
                          "--burst-factor",
                          "2.0",
                          "--burst-duration",
                          "0.5",
                          "--burst-fraction",
                          "0.2"
                        });
              } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
              }
            });

    thread.start();

    waitForRequest(r -> r.getSql().contains("SELECT * FROM my_table"));

    // Interrupt the thread to stop the infinite loop
    thread.interrupt();
    thread.join(5000); // Wait for it to finish
    assertTrue("Thread should have finished", !thread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testPointSelectBenchmarkGradualRuns() throws Exception {
    Thread thread =
        new Thread(
            () -> {
              try {
                new picocli.CommandLine(new BenchmarkApp())
                    .execute(
                        new String[] {
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
                          "1000",
                          "--threads",
                          "10",
                          "--load-type",
                          "GRADUAL",
                          "--cycle-duration",
                          "10s",
                          "--peak-factor",
                          "2.0"
                        });
              } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
              }
            });

    thread.start();

    waitForRequest(r -> r.getSql().contains("SELECT * FROM my_table"));

    // Interrupt the thread to stop the infinite loop
    thread.interrupt();
    thread.join(5000); // Wait for it to finish
    assertTrue("Thread should have finished", !thread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testReadLargeResultSetBenchmarkRuns() throws Exception {
    Thread thread =
        new Thread(
            () -> {
              try {
                new picocli.CommandLine(new BenchmarkApp())
                    .execute(
                        new String[] {
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
                          "1000",
                          "--threads",
                          "5"
                        });
              } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
              }
            });

    thread.start();

    waitForRequest(r -> r.getSql().contains("SELECT\n  MOD(FARM_FINGERPRINT"));

    // Interrupt the thread to stop the infinite loop
    thread.interrupt();
    thread.join(5000); // Wait for it to finish
    assertTrue("Thread should have finished", !thread.isAlive());
    assertNoErrors();
  }

  @Test
  public void testTpccBenchmarkRuns() throws Exception {
    Thread thread =
        new Thread(
            () -> {
              try {
                new picocli.CommandLine(new BenchmarkApp())
                    .execute(
                        new String[] {
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
                          "100"
                        });
              } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
              }
            });

    thread.start();

    waitForRequest(r -> r.getSql().contains("SELECT COUNT(*) FROM warehouse"));

    thread.join(10000); // Wait for TPCC to naturally finish because of the 2s duration
  }

  @Test
  public void testMetricsCollection() throws Exception {
    Thread thread =
        new Thread(
            () -> {
              try {
                new picocli.CommandLine(new BenchmarkApp())
                    .execute(
                        new String[] {
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
                          "1000",
                          "--threads",
                          "2"
                        });
              } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
              }
            });

    thread.start();

    // Let it run briefly to collect metrics
    long startTime = System.currentTimeMillis();
    boolean hasMetrics = false;
    boolean verifiedAttributes = false;
    boolean hasMemoryUsage = false;
    boolean hasCpuUtilization = false;

    while (System.currentTimeMillis() - startTime < 6000) {
      java.util.Collection<io.opentelemetry.sdk.metrics.data.MetricData> metricData =
          metricReader.collectAllMetrics();
      io.opentelemetry.sdk.metrics.data.MetricData opCount =
          metricData.stream()
              .filter(md -> md.getName().equals(BenchmarkApp.OPERATION_COUNT_NAME))
              .findFirst()
              .orElse(null);
      io.opentelemetry.sdk.metrics.data.MetricData mem =
          metricData.stream()
              .filter(md -> md.getName().equals(BenchmarkApp.MEMORY_USAGE_NAME))
              .findFirst()
              .orElse(null);
      io.opentelemetry.sdk.metrics.data.MetricData cpu =
          metricData.stream()
              .filter(md -> md.getName().equals(BenchmarkApp.CPU_UTILIZATION_NAME))
              .findFirst()
              .orElse(null);

      if (mem != null && !mem.getData().getPoints().isEmpty()) {
        hasMemoryUsage = true;
      }
      if (cpu != null && !cpu.getData().getPoints().isEmpty()) {
        hasCpuUtilization = true;
      }

      if (opCount != null && !opCount.getData().getPoints().isEmpty()) {
        io.opentelemetry.sdk.metrics.data.PointData firstPoint =
            (io.opentelemetry.sdk.metrics.data.PointData)
                opCount.getData().getPoints().iterator().next();
        String client =
            firstPoint
                .getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("client"));
        String benchmarkType =
            firstPoint
                .getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey("benchmark_type"));

        if ("java-client".equals(client) && "point-select".equals(benchmarkType)) {
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

    thread.interrupt();
    thread.join(5000);
    assertNoErrors();
  }
}
