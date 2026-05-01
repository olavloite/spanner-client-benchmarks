package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import com.google.cloud.NoCredentials;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.time.Duration;

import static com.google.cloud.spanner.benchmark.BenchmarkApp.LATENCY_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.METER_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.initializeOpenTelemetry;

public abstract class AbstractBenchmarkCommand implements Runnable {
    @ParentCommand
    protected BenchmarkApp parent;

    @Option(names = {"-t", "--table"}, description = "Table name", required = true)
    protected String tableName;

    @Option(names = {"--min-id"}, description = "Minimum ID value", defaultValue = "1")
    protected long minId;

    @Option(names = {"--max-id"}, description = "Maximum ID value", defaultValue = "1000000")
    protected long maxId;

    @Option(names = {"--tps"}, description = "Target transactions per second", defaultValue = "1")
    protected double tps;

    @Option(names = {"--threads"}, description = "Number of threads in the pool", defaultValue = "100")
    protected int threads;

    @Override
    public void run() {
        try {
            // Initialize OpenTelemetry
            OpenTelemetry openTelemetry = initializeOpenTelemetry(parent.getProjectId(), parent.getHost());
            Meter meter = openTelemetry.getMeter(METER_NAME);

            LongHistogram latencyHistogram = meter.histogramBuilder(LATENCY_NAME)
                    .ofLongs()
                    .setDescription("Query latency in microseconds")
                    .setUnit("us")
                    .build();

            // Initialize Spanner
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
                AbstractBenchmark benchmark = createBenchmark(client, latencyHistogram, duration, forAlerting);
                benchmark.run();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected abstract AbstractBenchmark createBenchmark(DatabaseClient client, LongHistogram latencyHistogram, Duration duration, boolean forAlerting);
}
