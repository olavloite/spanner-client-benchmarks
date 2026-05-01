package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import io.opentelemetry.api.metrics.LongHistogram;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class PointSelectBenchmark extends AbstractBenchmark {

    public PointSelectBenchmark(DatabaseClient client, LongHistogram latencyHistogram, String tableName, long minId, long maxId, double tps, int threads, Duration duration, boolean forAlerting) {
        super(client, latencyHistogram, tableName, minId, maxId, tps, threads, duration, forAlerting);
    }

    @Override
    protected void executeOperation() throws Exception {
        long randomId = ThreadLocalRandom.current().nextLong(minId, maxId + 1);
        String sql = "SELECT * FROM " + tableName + " WHERE id = @id";
        Statement statement = Statement.newBuilder(sql)
                .bind("id").to(randomId)
                .build();

        try (ResultSet resultSet = client.singleUse().executeQuery(statement)) {
            while (resultSet.next()) {
                resultSet.getValue(0);
            }
        }
    }

    @Override
    protected String getBenchmarkName() {
        return "Point Select Benchmark";
    }

    @Override
    protected String getBenchmarkType() {
        return "point-select";
    }
}
