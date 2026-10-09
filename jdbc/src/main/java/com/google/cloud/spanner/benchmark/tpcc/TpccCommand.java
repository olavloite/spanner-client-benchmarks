package com.google.cloud.spanner.benchmark.tpcc;

import static com.google.cloud.spanner.benchmark.BenchmarkApp.LATENCY_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.METER_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.initializeOpenTelemetry;

import com.google.cloud.spanner.benchmark.AbstractBenchmark;
import com.google.cloud.spanner.benchmark.BenchmarkApp;
import com.google.cloud.spanner.benchmark.BenchmarkMetrics;
import com.google.cloud.spanner.benchmark.ConnectionSupplier;
import com.google.cloud.spanner.benchmark.MockServerUtil;
import com.google.cloud.spanner.jdbc.JdbcDriver;
import io.grpc.Server;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import java.time.Duration;
import java.util.Properties;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "tpcc", description = "Runs TPC-C benchmark against Spanner.")
public class TpccCommand implements Runnable {

  @ParentCommand private BenchmarkApp parent;

  @Option(
      names = {"--warehouses"},
      description = "Scale factor (number of warehouses)",
      defaultValue = "1")
  private int warehouses;

  @Option(
      names = {"--clients"},
      description = "Number of parallel workers",
      defaultValue = "10")
  private int clients;

  @Option(
      names = {"--items"},
      description = "Number of items in catalog",
      defaultValue = "100000")
  private int items;

  @Option(
      names = {"--extended"},
      description = "Execute extended variant with read API, mutations, and partitioned queries.",
      defaultValue = "false")
  private boolean extended;

  @Override
  public void run() {
    Server server = null;
    if (parent.isMock()) {
      server = MockServerUtil.startMockSpannerServer(parent, null);
    }

    try {
      OpenTelemetry openTelemetry =
          initializeOpenTelemetry(
              parent.getProjectId(),
              parent.getHost(),
              parent.getBenchmarkName(),
              parent.isNoMetrics());
      Meter meter = openTelemetry.getMeter(METER_NAME);
      BenchmarkMetrics metrics = BenchmarkApp.createBenchmarkMetrics(meter, LATENCY_NAME);

      String url;
      if (parent.getHost() != null) {
        String cleanHost = parent.getHost().replaceFirst("^https?://", "");
        url =
            String.format(
                "jdbc:cloudspanner://%s/projects/%s/instances/%s/databases/%s?usePlainText=true",
                cleanHost, parent.getProjectId(), parent.getInstanceId(), parent.getDatabaseId());
      } else {
        url =
            String.format(
                "jdbc:cloudspanner:/projects/%s/instances/%s/databases/%s",
                parent.getProjectId(), parent.getInstanceId(), parent.getDatabaseId());
      }

      Properties info = new Properties();
      info.put(JdbcDriver.OPEN_TELEMETRY_PROPERTY_KEY, openTelemetry);

      String numChannelsStr = System.getenv("SPANNER_NUM_CHANNELS");
      if (numChannelsStr != null && !numChannelsStr.isEmpty()) {
        try {
          int numChannels = Integer.parseInt(numChannelsStr);
          info.setProperty("numChannels", String.valueOf(numChannels));
          System.out.println("Configured Spanner JDBC driver with " + numChannels + " channels.");
        } catch (NumberFormatException e) {
          System.err.println("Invalid SPANNER_NUM_CHANNELS value: " + numChannelsStr);
        }
      }

      if ("true".equalsIgnoreCase(System.getenv("GOOGLE_SPANNER_ENABLE_DIRECT_ACCESS"))) {
        System.out.println(
            "Configured Spanner JDBC client with DirectPath (direct access) enabled.");
      }

      ConnectionSupplier connectionSupplier = new ConnectionSupplier(url, info);

      Duration duration = AbstractBenchmark.parseDuration(parent.getDuration());
      boolean forAlerting = parent.isForAlerting();
      String benchmarkName = parent.getBenchmarkName();

      TpccBenchmark benchmark =
          new TpccBenchmark(
              connectionSupplier,
              metrics.latencyHistogram,
              metrics.operationCounter,
              metrics.errorCounter,
              metrics.memoryUsageHistogram,
              metrics.cpuUtilizationHistogram,
              parent.getResourceProbeInterval(),
              warehouses,
              clients,
              items,
              duration,
              forAlerting,
              benchmarkName,
              extended);
      benchmark.run();
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (server != null) {
        server.shutdown();
      }
    }
  }
}
