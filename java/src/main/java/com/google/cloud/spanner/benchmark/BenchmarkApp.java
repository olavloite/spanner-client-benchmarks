package com.google.cloud.spanner.benchmark;

import io.opentelemetry.api.OpenTelemetry;
import com.google.cloud.opentelemetry.metric.GoogleCloudMetricExporter;
import com.google.cloud.opentelemetry.metric.MetricConfiguration;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.Aggregation;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "BenchmarkApp", mixinStandardHelpOptions = true, version = "1.0", description = "Runs Spanner client benchmarks.", subcommands = {
        PointSelectCommand.class, SelectAndUpdateCommand.class, ReadLargeResultSetCommand.class })
public class BenchmarkApp implements Runnable {

    @Option(names = { "-p", "--project" }, description = "Google Cloud Project ID", required = true)
    private String projectId;

    @Option(names = { "-i", "--instance" }, description = "Spanner Instance ID", required = true)
    private String instanceId;

    @Option(names = { "-d", "--database" }, description = "Spanner Database ID", required = true)
    private String databaseId;

    @Option(names = { "--host" }, description = "Custom Spanner host endpoint")
    private String host;

    @Option(names = { "--duration" }, description = "Duration of the benchmark (e.g. 60s, 5m, inf for infinite). Defaults to infinite if not specified.")
    private String duration;

    @Option(names = { "--for-alerting" }, description = "Marks the benchmark for alerting purposes.")
    private boolean forAlerting;

    @Option(names = { "--benchmark-name" }, description = "Optional name to identify this benchmark run in metrics.")
    private String benchmarkName;

    @Option(names = { "--resource-probe-interval" }, description = "Interval for probing resource usage (e.g. 10s, 1m). Set to 0 to disable.", defaultValue = "10s")
    private String resourceProbeInterval;

    public static final String METER_NAME = "spanner-benchmark";
    public static final String LATENCY_NAME = "spanner_client_benchmarks/latency";
    public static final String OPERATION_COUNT_NAME = "spanner_client_benchmarks/operation_count";
    public static final String ERROR_COUNT_NAME = "spanner_client_benchmarks/error_count";
    public static final String MEMORY_USAGE_NAME = "spanner_client_benchmarks/memory_usage";
    public static final String CPU_UTILIZATION_NAME = "spanner_client_benchmarks/cpu_utilization";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BenchmarkApp())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        System.out.println("Please specify a benchmark to run. Use --help for usage.");
    }

    // Getters for subcommands
    public String getProjectId() {
        return projectId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getDatabaseId() {
        return databaseId;
    }

    public String getHost() {
        return host;
    }

    public String getDuration() {
        return duration;
    }

    public boolean isForAlerting() {
        return forAlerting;
    }

    public String getBenchmarkName() {
        return benchmarkName;
    }

    public String getResourceProbeInterval() {
        return resourceProbeInterval;
    }

    // Package-private so subcommands can access it
    static OpenTelemetry initializeOpenTelemetry(String projectId, String host) {
        if (host != null && host.startsWith("http://localhost:")) {
            return OpenTelemetry.noop();
        }

        return OpenTelemetrySdk.builder()
                .setMeterProvider(SdkMeterProvider.builder()
                        .registerMetricReader(PeriodicMetricReader.create(
                                GoogleCloudMetricExporter.createWithConfiguration(MetricConfiguration.builder()
                                        .setProjectId(projectId)
                                        .build())))
                        .registerView(
                                InstrumentSelector.builder()
                                        .setName(LATENCY_NAME)
                                        .build(),
                                View.builder()
                                        .setAggregation(
                                                Aggregation.explicitBucketHistogram(
                                                        java.util.List.of(
                                                                500.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0, 4000.0, 4500.0, 5000.0,
                                                                6000.0, 7000.0, 8000.0, 9000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0, 20000.0,
                                                                25000.0, 30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0
                                                        )))
                                        .build())
                        .registerView(
                                InstrumentSelector.builder()
                                        .setName("spanner_client_benchmarks/read_latency")
                                        .build(),
                                View.builder()
                                        .setAggregation(
                                                Aggregation.explicitBucketHistogram(
                                                        java.util.List.of(
                                                                50000.0, 100000.0, 250000.0, 500000.0, 750000.0,
                                                                1000000.0, 1250000.0, 1500000.0, 1750000.0, 2000000.0, 2250000.0, 2500000.0, 2750000.0, 3000000.0, 3250000.0, 3500000.0, 3750000.0, 4000000.0, 4250000.0, 4500000.0, 4750000.0, 5000000.0,
                                                                5500000.0, 6000000.0, 6500000.0, 7000000.0, 7500000.0, 8000000.0, 8500000.0, 9000000.0, 9500000.0, 10000000.0,
                                                                12500000.0, 15000000.0, 20000000.0, 30000000.0
                                                        )))
                                        .build())
                        .registerView(
                                InstrumentSelector.builder()
                                        .setName(MEMORY_USAGE_NAME)
                                        .build(),
                                View.builder()
                                        .setAggregation(
                                                Aggregation.explicitBucketHistogram(
                                                        java.util.List.of(
                                                                10_000_000.0, 25_000_000.0, 50_000_000.0, 100_000_000.0, 200_000_000.0, 300_000_000.0, 400_000_000.0, 500_000_000.0, 750_000_000.0, 1_000_000_000.0, 1_500_000_000.0, 2_000_000_000.0, 3_000_000_000.0, 5_000_000_000.0, 10_000_000_000.0
                                                        )))
                                        .build())
                        .registerView(
                                InstrumentSelector.builder()
                                        .setName(CPU_UTILIZATION_NAME)
                                        .build(),
                                View.builder()
                                        .setAggregation(
                                                Aggregation.explicitBucketHistogram(
                                                        java.util.List.of(
                                                                0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 1.0
                                                        )))
                                        .build())
                        .build())
                .buildAndRegisterGlobal();
    }
}
