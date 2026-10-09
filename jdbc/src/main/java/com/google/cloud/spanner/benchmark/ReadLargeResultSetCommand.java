package com.google.cloud.spanner.benchmark;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.time.Duration;
import picocli.CommandLine.Command;

@Command(name = "read-large-result-set", description = "Runs read large result set benchmark.")
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
  protected AbstractBenchmark createBenchmark(
      ConnectionSupplier connectionSupplier,
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
    return new ReadLargeResultSetBenchmark(
        connectionSupplier,
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
        numRows,
        loadType,
        AbstractBenchmark.parseDuration(cycleDuration),
        peakFactor,
        burstFactor,
        burstDuration,
        burstFraction);
  }
}
