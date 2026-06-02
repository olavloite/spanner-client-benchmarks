package com.google.cloud.spanner.benchmark.tpcc;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.benchmark.AbstractBenchmark;
import com.google.cloud.spanner.benchmark.BenchmarkApp;
import com.google.cloud.spanner.benchmark.BenchmarkMetrics;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import java.time.Duration;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.LATENCY_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.METER_NAME;

@Command(name = "tpcc", description = "Runs closed-loop TPC-C benchmark against Spanner.")
public class TpccCommand implements Runnable {

    @ParentCommand
    private BenchmarkApp parent;

    @Option(names = {"--warehouses"}, description = "Scale factor (number of warehouses)", defaultValue = "1")
    private int warehouses;

    @Option(names = {"--clients"}, description = "Number of parallel worker clients", defaultValue = "10")
    private int clients;

    @Option(names = {"--items"}, description = "Number of items in catalog", defaultValue = "100000")
    private int items;

    @Override
    public void run() {
        try {
            OpenTelemetry openTelemetry = BenchmarkApp.initializeOpenTelemetry(parent.getProjectId(), parent.getHost(), parent.getBenchmarkName());
            Meter meter = openTelemetry.getMeter(METER_NAME);
            BenchmarkMetrics metrics = BenchmarkApp.createBenchmarkMetrics(meter, LATENCY_NAME);

            SpannerOptions.Builder spannerOptionsBuilder = SpannerOptions.newBuilder().setProjectId(parent.getProjectId());
            if (parent.getHost() != null) {
                spannerOptionsBuilder.setHost(parent.getHost());
                spannerOptionsBuilder.setChannelConfigurator(builder -> builder.usePlaintext());
                spannerOptionsBuilder.setCredentials(NoCredentials.getInstance());
            }
            SpannerOptions spannerOptions = spannerOptionsBuilder.build();
            try (Spanner spanner = spannerOptions.getService()) {
                DatabaseClient client = spanner.getDatabaseClient(DatabaseId.of(parent.getProjectId(), parent.getInstanceId(), parent.getDatabaseId()));

                Duration duration = AbstractBenchmark.parseDuration(parent.getDuration());
                boolean forAlerting = parent.isForAlerting();
                String benchmarkName = parent.getBenchmarkName();

                TpccBenchmark benchmark = new TpccBenchmark(client, metrics.latencyHistogram, metrics.operationCounter, metrics.errorCounter,
                        metrics.memoryUsageHistogram, metrics.cpuUtilizationHistogram, warehouses, clients, items, duration, forAlerting, benchmarkName);
                benchmark.run();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
