package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class PointSelectBenchmark extends AbstractBenchmark {

    public PointSelectBenchmark(DatabaseClient client, LongHistogram latencyHistogram, LongCounter operationCounter, LongCounter errorCounter, String tableName, long minId, long maxId, double tps, int threads, Duration duration, boolean forAlerting, String benchmarkName, LoadType loadType, Duration cycleDuration, double peakFactor, double burstFactor, double burstDuration, double burstFraction) {
        super(client, latencyHistogram, operationCounter, errorCounter, tableName, minId, maxId, tps, threads, duration, forAlerting, benchmarkName, loadType, cycleDuration, peakFactor, burstFactor, burstDuration, burstFraction);
    }

    @Override
    protected void executeOperation() throws Exception {
        long randomId = ThreadLocalRandom.current().nextLong(minId, maxId + 1);
        String sql = "SELECT * FROM " + tableName + " WHERE id = @id";
        Statement statement = Statement.newBuilder(sql)
                .bind("id").to(randomId)
                .build();

        int dummy = 0;
        try (ResultSet resultSet = client.singleUse().executeQuery(statement)) {
            while (resultSet.next()) {
                dummy += java.util.Objects.hashCode(resultSet.getValue(0));
            }
        }
        if (dummy == 0xDEADBEEF) {
            System.out.println("This should rarely happen: " + dummy);
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
