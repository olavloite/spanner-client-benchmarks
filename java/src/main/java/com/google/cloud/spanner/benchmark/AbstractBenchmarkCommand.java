package com.google.cloud.spanner.benchmark;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
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

    @Option(names = {"--num-rows"}, description = "Number of rows to generate/select")
    protected long numRows = 1000000;

    @Option(names = {"--tps"}, description = "Target transactions per second")
    protected double tps = 10.0;

    @Option(names = {"--threads"}, description = "Number of threads in the pool", defaultValue = "100")
    protected int threads;

    @Option(names = {"--load-type"}, description = "Load type (STEADY, SPIKY, GRADUAL)", defaultValue = "STEADY")
    protected LoadType loadType = LoadType.STEADY;

    @Option(names = {"--cycle-duration"}, description = "Duration of a full cycle for gradual load")
    protected String cycleDuration;

    @Option(names = {"--peak-factor"}, description = "Ratio of peak rate to average rate for gradual load")
    protected Double peakFactor;

    @Option(names = {"--burst-factor"}, description = "Ratio of burst rate to average rate")
    protected Double burstFactor;

    @Option(names = {"--burst-duration"}, description = "Average duration of a burst in seconds")
    protected Double burstDuration;

    @Option(names = {"--burst-fraction"}, description = "Fraction of total time spent in the burst state")
    protected Double burstFraction;

    protected String getMetricName() {
        return LATENCY_NAME;
    }

    @Override
    public void run() {
        // Validation
        if (loadType == LoadType.STEADY) {
            if (cycleDuration != null || peakFactor != null || burstFactor != null || burstDuration != null || burstFraction != null) {
                throw new IllegalArgumentException("Cannot specify burst or gradual load options when load-type is steady");
            }
        } else if (loadType == LoadType.SPIKY) {
            if (cycleDuration != null || peakFactor != null) {
                throw new IllegalArgumentException("Cannot specify gradual load options when load-type is spiky");
            }
            // Set defaults if not specified
            if (burstFactor == null) burstFactor = 1.0;
            if (burstDuration == null) burstDuration = 1.0;
            if (burstFraction == null) burstFraction = 0.1;
        } else if (loadType == LoadType.GRADUAL) {
            if (burstFactor != null || burstDuration != null || burstFraction != null) {
                throw new IllegalArgumentException("Cannot specify burst load options when load-type is gradual");
            }
            // Set defaults if not specified
            if (cycleDuration == null) cycleDuration = "1h";
            if (peakFactor == null) peakFactor = 2.0;
        }

        // Set defaults for anything still null to avoid NPE during unboxing
        if (burstFactor == null) burstFactor = 1.0;
        if (burstDuration == null) burstDuration = 1.0;
        if (burstFraction == null) burstFraction = 0.1;
        if (cycleDuration == null) cycleDuration = "1h";
        if (peakFactor == null) peakFactor = 2.0;

        try {
            // Initialize OpenTelemetry
            OpenTelemetry openTelemetry = initializeOpenTelemetry(parent.getProjectId(), parent.getHost());
            Meter meter = openTelemetry.getMeter(METER_NAME);
            BenchmarkMetrics metrics = BenchmarkApp.createBenchmarkMetrics(meter, getMetricName());

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
                String benchmarkName = parent.getBenchmarkName();
                AbstractBenchmark benchmark = createBenchmark(client, metrics.latencyHistogram, metrics.operationCounter, metrics.errorCounter, metrics.memoryUsageHistogram, metrics.cpuUtilizationHistogram, parent.getResourceProbeInterval(), duration, forAlerting, benchmarkName);
                benchmark.run();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected abstract AbstractBenchmark createBenchmark(DatabaseClient client, LongHistogram latencyHistogram, LongCounter operationCounter, LongCounter errorCounter, LongHistogram memoryUsageHistogram, DoubleHistogram cpuUtilizationHistogram, String resourceProbeInterval, Duration duration, boolean forAlerting, String benchmarkName);
}
