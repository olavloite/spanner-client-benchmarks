package com.google.cloud.spanner.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class RegressionReporter {
  private final String summaryFilePath;
  private final String clientType;
  private final String benchmarkType;

  public RegressionReporter(String summaryFilePath, String clientType, String benchmarkType) {
    this.summaryFilePath = summaryFilePath;
    this.clientType = clientType;
    this.benchmarkType = benchmarkType;
  }

  public void appendRegression(
      int percentile,
      double targetP,
      double baselineP,
      double ratio,
      double threshold,
      String baselineLabel,
      boolean isRegression) {
    if (summaryFilePath == null || summaryFilePath.isEmpty()) {
      return;
    }

    Path path = Paths.get(summaryFilePath);
    boolean exists = Files.exists(path);

    StringBuilder sb = new StringBuilder();
    if (!exists) {
      sb.append(
          "| Client | Benchmark | Percentile | Comparison | Baseline | Target (Today) | Ratio | Threshold | Status |\n");
      sb.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
    }

    String cl = clientType != null && !clientType.isEmpty() ? clientType : "all-clients";
    String bench =
        benchmarkType != null && !benchmarkType.isEmpty() ? benchmarkType : "all-benchmarks";
    String status = isRegression ? "❌ ALERT" : "✅ OK";

    sb.append(
        String.format(
            "| %s | %s | P%d | %s | %.2f us | %.2f us | %.2f | %.2f | %s |\n",
            cl, bench, percentile, baselineLabel, baselineP, targetP, ratio, threshold, status));

    try {
      Files.writeString(path, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      System.err.println("Failed to write to summary file: " + e.getMessage());
    }
  }
}
