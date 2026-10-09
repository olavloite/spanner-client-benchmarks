package com.google.cloud.spanner.benchmark;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import javax.annotation.Nonnull;

public class ReadNarrowResultSetBenchmark extends AbstractBenchmark {

  private static final String SQL =
      "SELECT\n"
          + "  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64_1,\n"
          + "  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64_2\n"
          + "FROM UNNEST(GENERATE_ARRAY(1, ?)) AS n";

  private final long numRows;
  private final Attributes customAttributes;

  public ReadNarrowResultSetBenchmark(
      ConnectionSupplier connectionSupplier,
      LongHistogram latencyHistogram,
      LongCounter operationCounter,
      LongCounter errorCounter,
      LongHistogram memoryUsageHistogram,
      DoubleHistogram cpuUtilizationHistogram,
      String resourceProbeInterval,
      String tableName,
      long minId,
      long maxId,
      double tps,
      int threads,
      Duration duration,
      boolean forAlerting,
      String benchmarkName,
      long numRows,
      LoadType loadType,
      Duration cycleDuration,
      double peakFactor,
      double burstFactor,
      double burstDuration,
      double burstFraction) {
    super(
        connectionSupplier,
        latencyHistogram,
        operationCounter,
        errorCounter,
        memoryUsageHistogram,
        cpuUtilizationHistogram,
        resourceProbeInterval,
        tableName,
        minId,
        maxId,
        tps,
        threads,
        duration,
        forAlerting,
        benchmarkName,
        loadType,
        cycleDuration,
        peakFactor,
        burstFactor,
        burstDuration,
        burstFraction,
        false);
    this.numRows = numRows;
    this.customAttributes = super.getAttributes().toBuilder().put("num_rows", numRows).build();
  }

  @Override
  @Nonnull
  protected Attributes getAttributes() {
    return this.customAttributes;
  }

  // INTENTIONAL: Do not change shouldMeasureEntireMethod to return true.
  // We intentionally exclude the initial query execution and the first row fetch
  // to measure purely the iteration and decoding latency of the remaining rows.
  @Override
  protected boolean shouldMeasureEntireMethod() {
    return false;
  }

  @Override
  protected void executeOperation(Connection connection) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(SQL)) {
      statement.setLong(1, numRows);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          // Decode first row fully
          int dummy = decodeRow(resultSet);

          // Measure iteration of remaining rows
          long startTime = System.nanoTime();
          while (resultSet.next()) {
            dummy += decodeRow(resultSet);
          }
          long endTime = System.nanoTime();
          long latencyNs = endTime - startTime;
          long latencyUs = latencyNs / 1000;
          latencyHistogram.record(latencyUs, getAttributes());

          // Use dummy to prevent optimization
          if (dummy == 0xDEADBEEF) {
            System.out.println("This should rarely happen: " + dummy);
          }
        }
      }
    }
  }

  private int decodeRow(ResultSet resultSet) throws SQLException {
    int h = 0;
    h = 31 * h + Long.hashCode(resultSet.getLong(1));
    h = 31 * h + Long.hashCode(resultSet.getLong(2));
    return h;
  }

  @Override
  protected String getBenchmarkName() {
    return "Read Narrow Result Set Benchmark";
  }

  @Override
  protected String getBenchmarkType() {
    return "read-narrow-result-set";
  }
}
