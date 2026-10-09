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
import java.util.Objects;
import javax.annotation.Nonnull;

public class ReadLargeResultSetBenchmark extends AbstractBenchmark {

  private static final String SQL =
      "SELECT\n"
          + "  MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2) = 0 AS random_bool,\n"
          + "  CAST(GENERATE_UUID() AS BYTES) AS random_bytes,\n"
          + "  DATE_FROM_UNIX_DATE(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2932896))) AS random_date,\n"
          + "  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT32) AS random_float32,\n"
          + "  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT64) AS random_float64,\n"
          + "  MAKE_INTERVAL(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 10)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 12)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 28)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 24)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60))) AS random_interval,\n"
          + "  TO_JSON('{\"key\": \"' || GENERATE_UUID() || '\"}') AS random_json,\n"
          + "  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64,\n"
          + "  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS NUMERIC) AS random_numeric,\n"
          + "  GENERATE_UUID() AS random_string,\n"
          + "  TIMESTAMP_MICROS(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 1230219000000000))) AS random_timestamp,\n"
          + "  NEW_UUID() AS random_uuid\n"
          + "FROM UNNEST(GENERATE_ARRAY(1, ?)) AS n";

  private final long numRows;
  private final Attributes customAttributes;

  public ReadLargeResultSetBenchmark(
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
    h = 31 * h + Boolean.hashCode(resultSet.getBoolean(1));
    byte[] bytes = resultSet.getBytes(2);
    h = 31 * h + (bytes != null ? bytes.length : 0);
    h = 31 * h + Objects.hashCode(resultSet.getDate(3));
    h = 31 * h + Float.hashCode(resultSet.getFloat(4));
    h = 31 * h + Double.hashCode(resultSet.getDouble(5));
    h = 31 * h + Objects.hashCode(resultSet.getString(6));
    String json = resultSet.getString(7);
    h = 31 * h + (json != null ? json.length() : 0);
    h = 31 * h + Long.hashCode(resultSet.getLong(8));
    h = 31 * h + Objects.hashCode(resultSet.getBigDecimal(9));
    String str = resultSet.getString(10);
    h = 31 * h + (str != null ? str.length() : 0);
    h = 31 * h + Objects.hashCode(resultSet.getTimestamp(11));
    h = 31 * h + Objects.hashCode(resultSet.getObject(12));
    return h;
  }

  @Override
  protected String getBenchmarkName() {
    return "Read Large Result Set Benchmark";
  }

  @Override
  protected String getBenchmarkType() {
    return "read-large-result-set";
  }
}
