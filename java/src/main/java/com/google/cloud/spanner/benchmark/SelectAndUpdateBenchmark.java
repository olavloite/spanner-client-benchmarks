package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import io.opentelemetry.api.metrics.LongHistogram;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class SelectAndUpdateBenchmark extends AbstractBenchmark {

    public SelectAndUpdateBenchmark(DatabaseClient client, LongHistogram latencyHistogram, String tableName, long minId, long maxId, double tps, int threads, Duration duration, boolean forAlerting) {
        super(client, latencyHistogram, tableName, minId, maxId, tps, threads, duration, forAlerting);
    }

    @Override
    protected void executeOperation() throws Exception {
        long randomId = ThreadLocalRandom.current().nextLong(minId, maxId + 1);
        
        client.readWriteTransaction().run(transaction -> {
            String sql = "SELECT id FROM " + tableName + " WHERE id = @id";
            Statement statement = Statement.newBuilder(sql)
                    .bind("id").to(randomId)
                    .build();
            
            boolean exists = false;
            try (ResultSet resultSet = transaction.executeQuery(statement)) {
                if (resultSet.next()) {
                    exists = true;
                }
            }
            
            String randomValue = generateRandomString(ThreadLocalRandom.current().nextInt(75, 151));
            
            if (exists) {
                String updateSql = "UPDATE " + tableName + " SET value = @value WHERE id = @id";
                transaction.executeUpdate(Statement.newBuilder(updateSql)
                        .bind("value").to(randomValue)
                        .bind("id").to(randomId)
                        .build());
            } else {
                String insertSql = "INSERT INTO " + tableName + " (id, value) VALUES (@id, @value)";
                transaction.executeUpdate(Statement.newBuilder(insertSql)
                        .bind("id").to(randomId)
                        .bind("value").to(randomValue)
                        .build());
            }
            return null;
        });
    }

    @Override
    protected String getBenchmarkName() {
        return "Select and Update Benchmark";
    }

    @Override
    protected String getBenchmarkType() {
        return "select-update";
    }

    private static String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }
}
