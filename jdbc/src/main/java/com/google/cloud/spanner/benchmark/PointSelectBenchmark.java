package com.google.cloud.spanner.benchmark;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public class PointSelectBenchmark extends AbstractBenchmark {

  private final boolean isMock;

  public PointSelectBenchmark(
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
      double burstFraction,
      boolean isMock) {
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
        isMock);
    this.isMock = isMock;
  }

  @Override
  protected void executeOperation(Connection connection) throws Exception {
    long id = isMock ? 1L : ThreadLocalRandom.current().nextLong(minId, maxId + 1);
    String sql = "SELECT * FROM " + tableName + " WHERE id = ?";

    int dummy = 0;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, id);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          dummy += Objects.hashCode(resultSet.getObject(1));
        }
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
