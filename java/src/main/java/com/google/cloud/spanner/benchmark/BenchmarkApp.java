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
        PointSelectCommand.class, SelectAndUpdateCommand.class })
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

    public static final String METER_NAME = "spanner-benchmark";
    public static final String LATENCY_NAME = "SpannerBenchmark/latency";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BenchmarkApp()).execute(args);
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
                                                        java.util.List.of(1000.0, 2500.0, 5000.0, 7500.0, 10000.0,
                                                                15000.0, 20000.0, 25000.0, 30000.0, 40000.0, 50000.0,
                                                                75000.0, 100000.0,
                                                                150000.0, 200000.0)))
                                        .build())
                        .build())
                .buildAndRegisterGlobal();
    }
}
