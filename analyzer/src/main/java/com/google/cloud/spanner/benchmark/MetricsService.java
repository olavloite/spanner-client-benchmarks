package com.google.cloud.spanner.benchmark;

import com.google.api.Distribution;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.util.Timestamps;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class MetricsService {
  private final MetricServiceClient client;
  private final String projectId;
  private final String benchmarkType;
  private final String loadType;
  private final String tps;
  private final String clientType;
  private final boolean forAlerting;

  public MetricsService(
      MetricServiceClient client,
      String projectId,
      String benchmarkType,
      String loadType,
      String tps,
      String clientType,
      boolean forAlerting) {
    this.client = client;
    this.projectId = projectId;
    this.benchmarkType = benchmarkType;
    this.loadType = loadType;
    this.tps = tps;
    this.clientType = clientType;
    this.forAlerting = forAlerting;
  }

  public List<Distribution> fetchDistributions(TimeInterval interval, String specificClient) {
    StringBuilder filterBuilder =
        new StringBuilder(
            "metric.type=\"workload.googleapis.com/spanner_client_benchmarks/latency\"");

    if (forAlerting) {
      filterBuilder.append(" AND metric.labels.for_alerting=\"true\"");
    }
    if (benchmarkType != null && !benchmarkType.isEmpty()) {
      filterBuilder
          .append(" AND metric.labels.benchmark_type=\"")
          .append(benchmarkType)
          .append("\"");
    }
    if (loadType != null && !loadType.isEmpty()) {
      filterBuilder.append(" AND metric.labels.load_type=\"").append(loadType).append("\"");
    }
    if (tps != null && !tps.isEmpty()) {
      filterBuilder.append(" AND metric.labels.tps=\"").append(tps).append("\"");
    }

    String finalClient = specificClient != null ? specificClient : clientType;
    if (finalClient != null && !finalClient.isEmpty()) {
      filterBuilder.append(" AND metric.labels.client=\"").append(finalClient).append("\"");
    }

    ListTimeSeriesRequest request =
        ListTimeSeriesRequest.newBuilder()
            .setName("projects/" + projectId)
            .setFilter(filterBuilder.toString())
            .setInterval(interval)
            .setView(ListTimeSeriesRequest.TimeSeriesView.FULL)
            .build();

    List<Distribution> distributions = new ArrayList<>();
    for (TimeSeries ts : client.listTimeSeries(request).iterateAll()) {
      for (com.google.monitoring.v3.Point point : ts.getPointsList()) {
        if (point.getValue().hasDistributionValue()) {
          distributions.add(point.getValue().getDistributionValue());
        }
      }
    }
    return distributions;
  }

  public void listTimeSeries(int days) {
    Instant now = Instant.now();
    Instant start = now.minus(java.time.Duration.ofDays(days));
    TimeInterval interval =
        TimeInterval.newBuilder()
            .setStartTime(Timestamps.fromMillis(start.toEpochMilli()))
            .setEndTime(Timestamps.fromMillis(now.toEpochMilli()))
            .build();

    System.out.printf("Listing timeseries for the last %d days (since %s)\n", days, start);

    ListTimeSeriesRequest request =
        ListTimeSeriesRequest.newBuilder()
            .setName("projects/" + projectId)
            .setFilter("metric.type=\"workload.googleapis.com/spanner_client_benchmarks/latency\"")
            .setInterval(interval)
            .setView(ListTimeSeriesRequest.TimeSeriesView.FULL)
            .build();

    int count = 0;
    System.out.printf(
        "%-15s | %-25s | %-32s | %-6s | %-12s | %-12s | %-12s | %-20s | %-20s\n",
        "Client",
        "Benchmark Type",
        "Benchmark Name",
        "TPS",
        "Load Type",
        "For Alerting",
        "Points",
        "Start Time",
        "End Time");
    System.out.println("-".repeat(190));

    for (TimeSeries ts : client.listTimeSeries(request).iterateAll()) {
      count++;
      String cl = ts.getMetric().getLabelsOrDefault("client", "N/A");
      String type = ts.getMetric().getLabelsOrDefault("benchmark_type", "N/A");
      String name = ts.getMetric().getLabelsOrDefault("benchmark_name", "N/A");
      String tp = ts.getMetric().getLabelsOrDefault("tps", "N/A");
      String lt = ts.getMetric().getLabelsOrDefault("load_type", "N/A");
      String fa = ts.getMetric().getLabelsOrDefault("for_alerting", "N/A");

      int pointsCount = ts.getPointsCount();
      String firstPointTime = "N/A";
      String lastPointTime = "N/A";
      if (pointsCount > 0) {
        firstPointTime =
            Timestamps.toString(ts.getPoints(pointsCount - 1).getInterval().getStartTime());
        lastPointTime = Timestamps.toString(ts.getPoints(0).getInterval().getEndTime());
      }

      System.out.printf(
          "%-15s | %-25s | %-32s | %-6s | %-12s | %-12s | %-12d | %-20s | %-20s\n",
          cl, type, name, tp, lt, fa, pointsCount, firstPointTime, lastPointTime);
    }
    System.out.printf("\nTotal timeseries found: %d\n", count);
  }

  public TimeInterval getDayInterval(int offsetDays, LocalDate targetDate) {
    LocalDate date = targetDate.minusDays(offsetDays);
    return getTimeIntervalForDate(date);
  }

  public TimeInterval getTimeIntervalForDate(LocalDate localDate) {
    Instant startOfDay = localDate.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant endOfDay = localDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

    return TimeInterval.newBuilder()
        .setStartTime(Timestamps.fromMillis(startOfDay.toEpochMilli()))
        .setEndTime(Timestamps.fromMillis(endOfDay.toEpochMilli()))
        .build();
  }
}
