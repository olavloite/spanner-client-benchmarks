package com.google.cloud.spanner.benchmark;

import com.google.api.Distribution;
import com.google.monitoring.v3.TimeInterval;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PerformanceAnalyzer {
  private final MetricsService metricsService;
  private final RegressionReporter reporter;

  public PerformanceAnalyzer(MetricsService metricsService, RegressionReporter reporter) {
    this.metricsService = metricsService;
    this.reporter = reporter;
  }

  public boolean analyzeRegression(
      int percentile, int offsetDays, double threshold, LocalDate targetDate) {
    TimeInterval todayInterval = metricsService.getDayInterval(0, targetDate);
    TimeInterval baselineInterval = metricsService.getDayInterval(offsetDays, targetDate);
    return analyzeRegression(
        percentile, todayInterval, baselineInterval, threshold, offsetDays + "-day baseline");
  }

  public boolean analyzeRegression(
      int percentile,
      TimeInterval targetInterval,
      TimeInterval baselineInterval,
      double threshold,
      String baselineLabel) {
    double targetP = getMetricsPercentile(targetInterval, percentile);
    double baselineP = getMetricsPercentile(baselineInterval, percentile);

    if (targetP > 0 && baselineP > 0) {
      double ratio = targetP / baselineP;
      boolean isRegression = ratio > threshold;
      System.out.printf(
          "Calculated P%d comparison: target=%.2f us, baseline (%s)=%.2f us -> factor=%.2f\n",
          percentile, targetP, baselineLabel, baselineP, ratio);

      if (isRegression) {
        System.err.printf(
            "ALERT: P%d deviation too large (factor: %.2f, limit threshold: %.2f) vs %s\n",
            percentile, ratio, threshold, baselineLabel);
      }
      reporter.appendRegression(
          percentile, targetP, baselineP, ratio, threshold, baselineLabel, isRegression);
      return isRegression;
    } else {
      System.out.printf(
          "Could not retrieve metrics for P%d comparison: target=%.2f us, baseline (%s)=%.2f us\n",
          percentile, targetP, baselineLabel, baselineP);
    }

    return false;
  }

  public double getMetricsPercentile(TimeInterval interval, int targetPercentile) {
    List<Distribution> distributions = metricsService.fetchDistributions(interval, null);

    if (!distributions.isEmpty()) {
      Distribution merged = mergeDistributions(distributions);
      if (merged != null) {
        return computePercentile(merged, targetPercentile);
      }
    }
    return -1.0;
  }

  public Distribution mergeDistributions(List<Distribution> distributions) {
    if (distributions.isEmpty()) return null;

    Distribution targetConfigDist = null;
    int maxBounds = -1;
    for (Distribution d : distributions) {
      int bounds =
          d.hasBucketOptions() && d.getBucketOptions().hasExplicitBuckets()
              ? d.getBucketOptions().getExplicitBuckets().getBoundsCount()
              : 0;
      if (bounds > maxBounds) {
        maxBounds = bounds;
        targetConfigDist = d;
      }
    }

    if (targetConfigDist == null) {
      targetConfigDist = distributions.get(0);
    }

    Distribution.Builder mergedBuilder = Distribution.newBuilder(targetConfigDist);

    long totalCount = 0;
    double totalWeightedMean = 0.0;
    List<Long> mergedBuckets = new ArrayList<>();
    int bucketCount = targetConfigDist.getBucketCountsCount();
    for (int i = 0; i < bucketCount; i++) {
      mergedBuckets.add(0L);
    }

    Distribution.BucketOptions targetOptions = targetConfigDist.getBucketOptions();

    for (Distribution d : distributions) {
      if (!d.getBucketOptions().equals(targetOptions)) {
        continue;
      }

      totalCount += d.getCount();
      totalWeightedMean += d.getMean() * d.getCount();

      for (int j = 0; j < Math.min(bucketCount, d.getBucketCountsCount()); j++) {
        mergedBuckets.set(j, mergedBuckets.get(j) + d.getBucketCounts(j));
      }
    }

    double mergedMean = totalCount == 0 ? 0.0 : totalWeightedMean / totalCount;

    mergedBuilder.setCount(totalCount);
    mergedBuilder.setMean(mergedMean);
    mergedBuilder.clearBucketCounts();
    mergedBuilder.addAllBucketCounts(mergedBuckets);

    return mergedBuilder.build();
  }

  public double computePercentile(Distribution dist, int targetPercentile) {
    long total = dist.getCount();
    if (total == 0) {
      return -1.0;
    }

    long targetSum = (long) (total * (targetPercentile / 100.0));
    long runningSum = 0;
    long prevSum = 0;

    int bucketCount = dist.getBucketCountsCount();
    for (int i = 0; i < bucketCount; i++) {
      long count = dist.getBucketCounts(i);
      runningSum += count;

      if (runningSum >= targetSum) {
        double lowerBound = 0.0;
        int boundsCount = dist.getBucketOptions().getExplicitBuckets().getBoundsCount();
        if (i > 0 && boundsCount > 0 && (i - 1) < boundsCount) {
          lowerBound = dist.getBucketOptions().getExplicitBuckets().getBounds(i - 1);
        }

        double upperBound = dist.getMean();
        if (boundsCount > 0 && i < boundsCount) {
          upperBound = dist.getBucketOptions().getExplicitBuckets().getBounds(i);
        }

        double fraction = (count == 0) ? 0.0 : (double) (targetSum - prevSum) / count;
        return lowerBound + fraction * (upperBound - lowerBound);
      }
      prevSum = runningSum;
    }
    return dist.getMean();
  }
}
