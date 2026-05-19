package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.DoubleHistogram;
import picocli.CommandLine.Command;

import java.time.Duration;

@Command(name = "point-select", description = "Runs point select benchmark")
public class PointSelectCommand extends AbstractBenchmarkCommand {
    @Override
    protected AbstractBenchmark createBenchmark(DatabaseClient client, LongHistogram latencyHistogram, LongCounter operationCounter, LongCounter errorCounter, LongHistogram memoryUsageHistogram, DoubleHistogram cpuUtilizationHistogram, String resourceProbeInterval, Duration duration, boolean forAlerting, String benchmarkName) {
        return new PointSelectBenchmark(client, latencyHistogram, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, resourceProbeInterval, tableName, 1, numRows, tps, threads, duration, forAlerting, benchmarkName, loadType, AbstractBenchmark.parseDuration(cycleDuration), peakFactor, burstFactor, burstDuration, burstFraction);
    }
}
