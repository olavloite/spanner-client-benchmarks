package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import picocli.CommandLine.Command;

import java.time.Duration;

@Command(name = "select-update", description = "Runs select and update benchmark")
public class SelectAndUpdateCommand extends AbstractBenchmarkCommand {
    @Override
    protected AbstractBenchmark createBenchmark(DatabaseClient client, LongHistogram latencyHistogram, LongCounter operationCounter, LongCounter errorCounter, Duration duration, boolean forAlerting) {
        return new SelectAndUpdateBenchmark(client, latencyHistogram, operationCounter, errorCounter, tableName, 1, numRows, tps, threads, duration, forAlerting);
    }
}
