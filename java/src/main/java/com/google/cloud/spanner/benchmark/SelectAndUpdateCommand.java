package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.time.Duration;
import picocli.CommandLine.Command;

@Command(name = "select-update", description = "Runs select and update benchmark")
public class SelectAndUpdateCommand extends AbstractBenchmarkCommand {
  @Override
  protected AbstractBenchmark createBenchmark(
      DatabaseClient client,
      LongHistogram latencyHistogram,
      LongCounter operationCounter,
      LongCounter errorCounter,
      LongHistogram memoryUsageHistogram,
      DoubleHistogram cpuUtilizationHistogram,
      String resourceProbeInterval,
      Duration duration,
      boolean forAlerting,
      String benchmarkName,
      boolean isMock) {
    return new SelectAndUpdateBenchmark(
        client,
        latencyHistogram,
        operationCounter,
        errorCounter,
        memoryUsageHistogram,
        cpuUtilizationHistogram,
        resourceProbeInterval,
        tableName,
        1,
        numRows,
        tps,
        threads,
        duration,
        forAlerting,
        benchmarkName,
        loadType,
        AbstractBenchmark.parseDuration(cycleDuration),
        peakFactor,
        burstFactor,
        burstDuration,
        burstFraction);
  }
}
