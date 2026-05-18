package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.DoubleHistogram;
import picocli.CommandLine.Command;
import java.time.Duration;

@Command(name = "read-large-result-set", description = "Runs large result set iteration benchmark")
public class ReadLargeResultSetCommand extends AbstractBenchmarkCommand {
    public ReadLargeResultSetCommand() {
        this.numRows = 100000;
        this.tps = 0.05;
    }

    @Override
    protected String getMetricName() {
        return "spanner_client_benchmarks/read_latency";
    }

    @Override
    protected AbstractBenchmark createBenchmark(DatabaseClient client, LongHistogram latencyHistogram,
            LongCounter operationCounter, LongCounter errorCounter, LongHistogram memoryUsageHistogram, DoubleHistogram cpuUtilizationHistogram, String resourceProbeInterval, Duration duration, boolean forAlerting, String benchmarkName) {
        return new ReadLargeResultSetBenchmark(client, latencyHistogram, operationCounter, errorCounter, memoryUsageHistogram, cpuUtilizationHistogram, resourceProbeInterval, tableName, 1,
                numRows, tps, threads, duration, forAlerting, benchmarkName, numRows, loadType, AbstractBenchmark.parseDuration(cycleDuration), peakFactor, burstFactor, burstDuration, burstFraction);
    }
}
