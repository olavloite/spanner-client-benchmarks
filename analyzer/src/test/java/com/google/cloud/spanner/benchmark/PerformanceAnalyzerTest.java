package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.api.Distribution;
import com.google.monitoring.v3.TimeInterval;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;

public class PerformanceAnalyzerTest {

  private static class FakeMetricsService extends MetricsService {
    private final List<Distribution> todayDistributions;
    private final List<Distribution> baselineDistributions;

    public FakeMetricsService(
        List<Distribution> todayDistributions, List<Distribution> baselineDistributions) {
      super(null, "p", "workload", "lt", "10", "client", false);
      this.todayDistributions = todayDistributions;
      this.baselineDistributions = baselineDistributions;
    }

    @Override
    public List<Distribution> fetchDistributions(TimeInterval interval, String specificClient) {
      // 0 means today, any other value represents baseline
      if (interval.getStartTime().getSeconds() == 0) {
        return todayDistributions;
      }
      return baselineDistributions;
    }

    @Override
    public TimeInterval getDayInterval(int offsetDays, LocalDate targetDate) {
      // Return a dummy interval where start time represents the day identifier
      return TimeInterval.newBuilder()
          .setStartTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(offsetDays).build())
          .setEndTime(com.google.protobuf.Timestamp.newBuilder().setSeconds(offsetDays + 1).build())
          .build();
    }
  }

  private Distribution createFakeDistribution(
      long count, double mean, List<Double> bounds, List<Long> bucketCounts) {
    Distribution.BucketOptions bucketOptions =
        Distribution.BucketOptions.newBuilder()
            .setExplicitBuckets(
                Distribution.BucketOptions.Explicit.newBuilder().addAllBounds(bounds).build())
            .build();
    return Distribution.newBuilder()
        .setCount(count)
        .setMean(mean)
        .setBucketOptions(bucketOptions)
        .addAllBucketCounts(bucketCounts)
        .build();
  }

  @Test
  public void testNoRegression() {
    List<Double> bounds = List.of(1.0, 2.0, 5.0, 10.0);
    List<Long> buckets = List.of(10L, 20L, 50L, 20L, 0L);

    // Today and baseline are identical
    Distribution today = createFakeDistribution(100, 4.0, bounds, buckets);
    Distribution baseline = createFakeDistribution(100, 4.0, bounds, buckets);

    FakeMetricsService metricsService = new FakeMetricsService(List.of(today), List.of(baseline));
    RegressionReporter reporter = new RegressionReporter(null, "client", "workload");
    PerformanceAnalyzer analyzer = new PerformanceAnalyzer(metricsService, reporter);

    boolean regression = analyzer.analyzeRegression(90, 1, 1.10, LocalDate.now());
    assertFalse("Should not detect regression when today is identical to baseline", regression);
  }

  @Test
  public void testRegressionDetected() {
    List<Double> bounds = List.of(1.0, 2.0, 5.0, 10.0);

    // Baseline has mostly low latencies (P90 is around 5.0 us)
    List<Long> baselineBuckets = List.of(40L, 40L, 10L, 10L, 0L); // total 100
    Distribution baseline = createFakeDistribution(100, 2.0, bounds, baselineBuckets);

    // Today has higher latencies (P90 is around 10.0 us)
    List<Long> todayBuckets = List.of(10L, 10L, 40L, 30L, 10L); // total 100
    Distribution today = createFakeDistribution(100, 6.0, bounds, todayBuckets);

    FakeMetricsService metricsService = new FakeMetricsService(List.of(today), List.of(baseline));
    RegressionReporter reporter = new RegressionReporter(null, "client", "workload");
    PerformanceAnalyzer analyzer = new PerformanceAnalyzer(metricsService, reporter);

    // Threshold is 1.20 (20% increase limit). Actual ratio is ~2.0
    boolean regression = analyzer.analyzeRegression(90, 1, 1.20, LocalDate.now());
    assertTrue("Should detect regression when P90 is significantly higher today", regression);
  }
}
