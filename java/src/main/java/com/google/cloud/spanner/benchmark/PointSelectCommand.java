package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import io.opentelemetry.api.metrics.LongHistogram;
import picocli.CommandLine.Command;

import java.time.Duration;

@Command(name = "point-select", description = "Runs point select benchmark")
public class PointSelectCommand extends AbstractBenchmarkCommand {
    @Override
    protected AbstractBenchmark createBenchmark(DatabaseClient client, LongHistogram latencyHistogram, Duration duration, boolean forAlerting) {
        return new PointSelectBenchmark(client, latencyHistogram, tableName, minId, maxId, tps, threads, duration, forAlerting);
    }
}
