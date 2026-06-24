package com.google.cloud.spanner.benchmark.tpcc;

import static com.google.cloud.spanner.benchmark.BenchmarkApp.LATENCY_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.METER_NAME;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.BatchClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.benchmark.AbstractBenchmark;
import com.google.cloud.spanner.benchmark.BenchmarkApp;
import com.google.cloud.spanner.benchmark.BenchmarkMetrics;
import com.google.cloud.spanner.benchmark.MockServerUtil;
import io.grpc.Server;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import java.time.Duration;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "tpcc", description = "Runs closed-loop TPC-C benchmark against Spanner.")
public class TpccCommand implements Runnable {

  @ParentCommand private BenchmarkApp parent;

  @Option(
      names = {"--warehouses"},
      description = "Scale factor (number of warehouses)",
      defaultValue = "1")
  private int warehouses;

  @Option(
      names = {"--clients"},
      description = "Number of parallel worker clients",
      defaultValue = "10")
  private int clients;

  @Option(
      names = {"--items"},
      description = "Number of items in catalog",
      defaultValue = "100000")
  private int items;

  @Option(
      names = {"--extended"},
      description = "Run TPC-C benchmark with extended coverage of client library features",
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
          BenchmarkApp.initializeOpenTelemetry(
              parent.getProjectId(),
              parent.getHost(),
              parent.getBenchmarkName(),
              parent.isNoMetrics());
      Meter meter = openTelemetry.getMeter(METER_NAME);
      BenchmarkMetrics metrics = BenchmarkApp.createBenchmarkMetrics(meter, LATENCY_NAME);

      SpannerOptions.Builder spannerOptionsBuilder =
          SpannerOptions.newBuilder().setProjectId(parent.getProjectId());
      if (parent.getHost() != null) {
        spannerOptionsBuilder.setHost(parent.getHost());
        spannerOptionsBuilder.setChannelConfigurator(builder -> builder.usePlaintext());
        spannerOptionsBuilder.setCredentials(NoCredentials.getInstance());
      }
      SpannerOptions spannerOptions = spannerOptionsBuilder.build();
      try (Spanner spanner = spannerOptions.getService()) {
        DatabaseId databaseId =
            DatabaseId.of(parent.getProjectId(), parent.getInstanceId(), parent.getDatabaseId());
        DatabaseClient client = spanner.getDatabaseClient(databaseId);
        BatchClient batchClient = spanner.getBatchClient(databaseId);

        Duration duration = AbstractBenchmark.parseDuration(parent.getDuration());
        boolean forAlerting = parent.isForAlerting();
        String benchmarkName = parent.getBenchmarkName();

        TpccBenchmark benchmark =
            new TpccBenchmark(
                client,
                batchClient,
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
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (server != null) {
        server.shutdown();
      }
    }
  }
}
