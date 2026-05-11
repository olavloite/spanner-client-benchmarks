package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.common.Attributes;
import javax.annotation.Nonnull;
import java.time.Duration;

public class ReadLargeResultSetBenchmark extends AbstractBenchmark {

    private static final String SQL = "SELECT\n" +
            "  MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2) = 0 AS random_bool,\n" +
            "  CAST(GENERATE_UUID() AS BYTES) AS random_bytes,\n" +
            "  DATE_FROM_UNIX_DATE(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2932896))) AS random_date,\n" +
            "  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT32) AS random_float32,\n" +
            "  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT64) AS random_float64,\n" +
            "  MAKE_INTERVAL(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 10)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 12)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 28)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 24)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60))) AS random_interval,\n" +
            "  TO_JSON('{\"key\": \"' || GENERATE_UUID() || '\"}') AS random_json,\n" +
            "  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64,\n" +
            "  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS NUMERIC) AS random_numeric,\n" +
            "  GENERATE_UUID() AS random_string,\n" +
            "  TIMESTAMP_MICROS(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 1230219000000000))) AS random_timestamp,\n" +
            "  NEW_UUID() AS random_uuid\n" +
            "FROM UNNEST(GENERATE_ARRAY(1, @num_rows)) AS n";

    private final Statement statement;
    private final Attributes customAttributes;

    public ReadLargeResultSetBenchmark(DatabaseClient client, LongHistogram latencyHistogram, LongCounter operationCounter, LongCounter errorCounter, String tableName, long minId, long maxId, double tps, int threads, Duration duration, boolean forAlerting, long numRows, LoadType loadType, Duration cycleDuration, double peakFactor, double burstFactor, double burstDuration, double burstFraction) {
        super(client, latencyHistogram, operationCounter, errorCounter, tableName, minId, maxId, tps, threads, duration, forAlerting, loadType, cycleDuration, peakFactor, burstFactor, burstDuration, burstFraction);
        this.customAttributes = super.getAttributes().toBuilder()
                .put("num_rows", numRows)
                .build();
        this.statement = Statement.newBuilder(SQL)
                .bind("num_rows").to(numRows)
                .build();
    }

    @Override
    @Nonnull
    protected Attributes getAttributes() {
        return this.customAttributes;
    }

    @Override
    protected boolean shouldMeasureEntireMethod() {
        return false;
    }

    @Override
    protected void executeOperation() throws Exception {
        try (ResultSet resultSet = client.singleUse().executeQuery(statement)) {
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

    private int decodeRow(ResultSet resultSet) {
        int h = 0;
        h = 31 * h + Boolean.hashCode(resultSet.getBoolean(0));
        h = 31 * h + resultSet.getBytes(1).length();
        h = 31 * h + java.util.Objects.hashCode(resultSet.getDate(2));
        h = 31 * h + Float.hashCode(resultSet.getFloat(3));
        h = 31 * h + Double.hashCode(resultSet.getDouble(4));
        h = 31 * h + java.util.Objects.hashCode(resultSet.getInterval(5));
        h = 31 * h + resultSet.getJson(6).length();
        h = 31 * h + Long.hashCode(resultSet.getLong(7));
        h = 31 * h + java.util.Objects.hashCode(resultSet.getBigDecimal(8));
        h = 31 * h + resultSet.getString(9).length();
        h = 31 * h + java.util.Objects.hashCode(resultSet.getTimestamp(10));
        h = 31 * h + java.util.Objects.hashCode(resultSet.getUuid(11));
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
