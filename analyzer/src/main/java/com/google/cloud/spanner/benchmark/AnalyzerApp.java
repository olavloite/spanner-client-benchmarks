package com.google.cloud.spanner.benchmark;

import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.util.Timestamps;
import com.google.api.gax.rpc.ApiException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;

@Command(name = "performance-analyzer", mixinStandardHelpOptions = true, version = "1.0",
        description = "Analyzes daily Spanner benchmarks for regression alerts using safe timeSeries.list method.")
public class AnalyzerApp implements Callable<Integer> {

    @Option(names = {"-p", "--project"}, description = "Google Cloud Project ID", required = true)
    private String projectId;

    @Option(names = {"--threshold-p50"}, description = "Alert threshold factor for P50 (e.g., 1.1 for 10%)", defaultValue = "1.1")
    private double thresholdP50;

    @Option(names = {"--threshold-p99"}, description = "Alert threshold factor for P99 (e.g., 1.2 for 20%)", defaultValue = "1.2")
    private double thresholdP99;

    @Option(names = {"--test-mode"}, description = "Run in test mode, print current metrics only.")
    private boolean testMode;

    @Option(names = {"-b", "--benchmark-type"}, description = "Filter by benchmark type (e.g., point-select)")
    private String benchmarkType;

    @Option(names = {"-t", "--tps"}, description = "Filter by target transactions per second (e.g., 1.0)")
    private String tps;

    @Option(names = {"-c", "--client"}, description = "Filter by client type (e.g., java-client)")
    private String clientType;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AnalyzerApp()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try (MetricServiceClient client = MetricServiceClient.create()) {
            System.out.println("Analyzing baseline for project: " + projectId);

            if (testMode) {
                executeTestMode(client);
                return 0;
            }

            boolean regressionFound = false;
            int[] percentiles = {50, 99};
            int[] offsetsInDays = {1, 7};

            for (int p : percentiles) {
                for (int offset : offsetsInDays) {
                    double threshold = (p == 50) ? thresholdP50 : thresholdP99;
                    boolean regression = analyzeRegression(client, p, offset, threshold);
                    if (regression) {
                        regressionFound = true;
                    }
                }
            }

            if (regressionFound) {
                System.err.println("ALERT: Performance regression detected!");
                return 2;
            }

        } catch (Exception e) {
            System.err.println("Analysis failed with an unhandled error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }

        System.out.println("All benchmarks within tolerance limits. Clean.");
        return 0;
    }

    private void executeTestMode(MetricServiceClient client) {
        System.out.println("Running in test mode. Extracting current percentiles...");
        TimeInterval today = getDayInterval(0);

        System.out.printf("Current P50: %.2f us\n", getMetricsPercentile(client, today, 50));
        System.out.printf("Current P99: %.2f us\n", getMetricsPercentile(client, today, 99));
    }

    private boolean analyzeRegression(MetricServiceClient client, int percentile, int offsetDays, double threshold) {
        TimeInterval todayInterval = getDayInterval(0);
        TimeInterval baselineInterval = getDayInterval(offsetDays);

        double todayP = getMetricsPercentile(client, todayInterval, percentile);
        double baselineP = getMetricsPercentile(client, baselineInterval, percentile);

        if (todayP > 0 && baselineP > 0) {
            double ratio = todayP / baselineP;
            System.out.printf("Calculated P%d offset comparison: today=%.2f us, baseline=%.2f us -> factor=%.2f\n",
                    percentile, todayP, baselineP, ratio);

            if (ratio > threshold) {
                System.err.printf("ALERT: P%d deviation too large (factor: %.2f, limit threshold: %.2f) vs %d-day baseline\n",
                        percentile, ratio, threshold, offsetDays);
                return true;
            }
        }

        return false;
    }

    private TimeInterval getDayInterval(int offsetDays) {
        Instant now = Instant.now();
        LocalDate localDate = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(offsetDays);

        Instant startOfDay = localDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay = localDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        return TimeInterval.newBuilder()
                .setStartTime(Timestamps.fromMillis(startOfDay.toEpochMilli()))
                .setEndTime(Timestamps.fromMillis(endOfDay.toEpochMilli()))
                .build();
    }

    private double getMetricsPercentile(MetricServiceClient client, TimeInterval interval, int targetPercentile) {
        StringBuilder filterBuilder = new StringBuilder("metric.type=\"workload.googleapis.com/SpannerBenchmark/latency\" AND metric.labels.for_alerting=\"true\"");

        if (benchmarkType != null && !benchmarkType.isEmpty()) {
            filterBuilder.append(" AND metric.labels.benchmark_type=\"").append(benchmarkType).append("\"");
        }
        if (tps != null && !tps.isEmpty()) {
            filterBuilder.append(" AND metric.labels.tps=\"").append(tps).append("\"");
        }
        if (clientType != null && !clientType.isEmpty()) {
            filterBuilder.append(" AND metric.labels.client=\"").append(clientType).append("\"");
        }

        ListTimeSeriesRequest request = ListTimeSeriesRequest.newBuilder()
                .setName("projects/" + projectId)
                .setFilter(filterBuilder.toString())
                .setInterval(interval)
                .setView(ListTimeSeriesRequest.TimeSeriesView.FULL)
                .build();

        try {
            for (TimeSeries ts : client.listTimeSeries(request).iterateAll()) {
                if (ts.getPointsCount() > 0) {
                    var point = ts.getPoints(0); // current point
                    if (point.getValue().hasDistributionValue()) {
                        return computePercentile(point.getValue().getDistributionValue(), targetPercentile);
                    }
                }
            }
        } catch (ApiException e) {
            System.err.println("Failure fetching time series: " + e.getMessage());
        }

        return -1.0;
    }

    private double computePercentile(com.google.api.Distribution dist, int targetPercentile) {
        long total = dist.getCount();
        if (total == 0) {
            return -1.0;
        }

        long targetSum = (long) (total * (targetPercentile / 100.0));
        long runningSum = 0;
        long prevSum = 0;

        for (int i = 0; i < dist.getBucketCountsCount(); i++) {
            long count = dist.getBucketCounts(i);
            runningSum += count;
            if (runningSum >= targetSum) {
                double lowerBound = (i == 0) ? 0.0 : dist.getBucketOptions().getExplicitBuckets().getBounds(i - 1);
                double upperBound = (i < dist.getBucketOptions().getExplicitBuckets().getBoundsCount()) ?
                        dist.getBucketOptions().getExplicitBuckets().getBounds(i) : dist.getMean();

                double fraction = (count == 0) ? 0.0 : (double) (targetSum - prevSum) / count;
                return lowerBound + fraction * (upperBound - lowerBound);
            }
            prevSum = runningSum;
        }
        return dist.getMean();
    }
}
