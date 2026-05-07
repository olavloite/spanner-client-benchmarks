package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
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

    public ReadLargeResultSetBenchmark(DatabaseClient client, LongHistogram latencyHistogram, LongCounter operationCounter, LongCounter errorCounter, String tableName, long minId, long maxId, double tps, int threads, Duration duration, boolean forAlerting, long numRows) {
        super(client, latencyHistogram, operationCounter, errorCounter, tableName, minId, maxId, tps, threads, duration, forAlerting);
        this.customAttributes = super.getAttributes().toBuilder()
                .put(AttributeKey.longKey("num_rows"), numRows)
                .build();
        this.statement = Statement.newBuilder(SQL)
                .bind("num_rows").to(numRows)
                .build();
    }

    @Override
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
                decodeRow(resultSet);

                // Measure iteration of remaining rows
                long startTime = System.nanoTime();
                while (resultSet.next()) {
                    decodeRow(resultSet);
                }
                long endTime = System.nanoTime();
                long latencyNs = endTime - startTime;
                long latencyUs = latencyNs / 1000;
                latencyHistogram.record(latencyUs, getAttributes());
            }
        }
    }

    private void decodeRow(ResultSet resultSet) {
        resultSet.getBoolean(0);
        resultSet.getBytes(1);
        resultSet.getDate(2);
        resultSet.getFloat(3);
        resultSet.getDouble(4);
        resultSet.getInterval(5);
        resultSet.getJson(6);
        resultSet.getLong(7);
        resultSet.getBigDecimal(8);
        resultSet.getString(9);
        resultSet.getTimestamp(10);
        resultSet.getUuid(11);
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
