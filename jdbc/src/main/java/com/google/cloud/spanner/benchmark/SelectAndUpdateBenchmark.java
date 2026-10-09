package com.google.cloud.spanner.benchmark;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class SelectAndUpdateBenchmark extends AbstractBenchmark {

  public SelectAndUpdateBenchmark(
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
  }

  @Override
  protected void executeOperation(Connection connection) throws Exception {
    long randomId = ThreadLocalRandom.current().nextLong(minId, maxId + 1);

    try {
      connection.setAutoCommit(false);
      String selectSql = "SELECT id FROM " + tableName + " WHERE id = ?";
      boolean exists = false;
      try (PreparedStatement selectStatement = connection.prepareStatement(selectSql)) {
        selectStatement.setLong(1, randomId);
        try (ResultSet resultSet = selectStatement.executeQuery()) {
          if (resultSet.next()) {
            exists = true;
          }
        }
      }

      String randomValue = generateRandomString(ThreadLocalRandom.current().nextInt(75, 151));

      if (exists) {
        String updateSql = "UPDATE " + tableName + " SET value = ? WHERE id = ?";
        try (PreparedStatement updateStatement = connection.prepareStatement(updateSql)) {
          updateStatement.setString(1, randomValue);
          updateStatement.setLong(2, randomId);
          updateStatement.executeUpdate();
        }
      } else {
        String insertSql = "INSERT INTO " + tableName + " (id, value) VALUES (?, ?)";
        try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
          insertStatement.setLong(1, randomId);
          insertStatement.setString(2, randomValue);
          insertStatement.executeUpdate();
        }
      }
      connection.commit();
    } catch (Exception e) {
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      throw e;
    }
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
    StringBuilder stringBuilder = new StringBuilder();
    for (int i = 0; i < length; i++) {
      stringBuilder.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
    }
    return stringBuilder.toString();
  }
}
