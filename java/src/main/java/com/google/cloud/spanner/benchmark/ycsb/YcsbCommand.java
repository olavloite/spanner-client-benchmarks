package com.google.cloud.spanner.benchmark.ycsb;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.benchmark.AbstractBenchmark;
import com.google.cloud.spanner.benchmark.AbstractBenchmarkCommand;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.time.Duration;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "ycsb",
    description = "Runs standard YCSB benchmark workloads (A, B, C, D, E, F) against Spanner.",
    mixinStandardHelpOptions = true)
public class YcsbCommand extends AbstractBenchmarkCommand {

  @Option(
      names = {"-w", "--workload"},
      description = "YCSB workload to execute (A, B, C, D, E, F). Defaults to B.",
      defaultValue = "B")
  protected YcsbWorkload workload = YcsbWorkload.B;

  @Option(
      names = {"--distribution"},
      description =
          "Key distribution (SCRAMBLED_ZIPFIAN, ZIPFIAN, UNIFORM). Defaults to SCRAMBLED_ZIPFIAN.",
      defaultValue = "SCRAMBLED_ZIPFIAN")
  protected KeyDistribution distribution = KeyDistribution.SCRAMBLED_ZIPFIAN;

  @Option(
      names = {"--record-count"},
      description = "Total number of records in the database. Defaults to 100,000.",
      defaultValue = "100000")
  protected long recordCount = 100_000L;

  @Option(
      names = {"--zero-padding"},
      description =
          "Zero padding length for primary key generation (e.g. 12 -> user000000000001). Defaults to 12.",
      defaultValue = "12")
  protected int zeroPadding = 12;

  @Option(
      names = {"--field-count"},
      description = "Number of fields per record. Defaults to 10.",
      defaultValue = "10")
  protected int fieldCount = 10;

  @Option(
      names = {"--field-length"},
      description = "Length of each field value in bytes. Defaults to 100.",
      defaultValue = "100")
  protected int fieldLength = 100;

  @Option(
      names = {"--use-read-row"},
      description =
          "Use Spanner readRow API instead of SQL query for point reads. Defaults to false.")
  protected boolean useReadRow = false;

  public YcsbCommand() {
    this.tableName = "usertable";
  }

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
    return new YcsbBenchmark(
        client,
        latencyHistogram,
        operationCounter,
        errorCounter,
        memoryUsageHistogram,
        cpuUtilizationHistogram,
        resourceProbeInterval,
        tableName != null ? tableName : "usertable",
        workload,
        distribution,
        recordCount,
        zeroPadding,
        fieldCount,
        fieldLength,
        useReadRow,
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
        burstFraction,
        isMock);
  }
}
