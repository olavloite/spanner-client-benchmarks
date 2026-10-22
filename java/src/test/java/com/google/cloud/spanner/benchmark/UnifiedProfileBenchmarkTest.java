package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.cloud.NoCredentials;
import com.google.cloud.grpc.GcpManagedChannelOptions.GcpChannelPoolOptions;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.benchmark.PhaseLatencyRecorder.PhaseSummary;
import com.google.spanner.v1.ExecuteSqlRequest;
import io.grpc.Status;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import picocli.CommandLine;

public class UnifiedProfileBenchmarkTest extends AbstractBenchmarkTest {

  @Test
  public void testWorkloadSizingFormulaDefaultsForThreeChannels() {
    assertEquals(50, UnifiedProfileConfig.defaultWarmupConcurrency(3));
    assertEquals(210, UnifiedProfileConfig.defaultSpikeConcurrency(3));
    assertEquals(300, UnifiedProfileConfig.defaultSaturationConcurrency(3));
    assertEquals(120, UnifiedProfileConfig.defaultRecoveryConcurrency(3));
  }

  @Test
  public void testChannelPoolOptionsBuilderSetsMinInitAndMaxSize() {
    GcpChannelPoolOptions options = UnifiedProfileConfig.buildChannelPoolOptions(1, 3);
    assertEquals(1, options.getMinSize());
    assertEquals(1, options.getInitSize());
    assertEquals(3, options.getMaxSize());
  }

  @Test
  public void testPhaseLatencyRecorderPercentilesEmptyPopulatedAndExcludesErrors() {
    PhaseLatencyRecorder recorder = new PhaseLatencyRecorder();
    assertEquals(0L, recorder.getPercentile(50));
    assertEquals(0L, recorder.getPercentile(90));
    assertEquals(0L, recorder.getPercentile(99));

    for (long latencyUs = 1; latencyUs <= 100; latencyUs++) {
      recorder.recordOperation(latencyUs, false);
    }
    for (int i = 0; i < 50; i++) {
      recorder.recordOperation(1L, true);
    }

    PhaseSummary summary = recorder.toSummary(ChannelPoolPhase.WARMUP, 50);
    assertEquals(150L, summary.getOperationCount());
    assertEquals(50L, summary.getErrorCount());
    assertEquals(50L, summary.getP50Micros());
    assertEquals(90L, summary.getP90Micros());
    assertEquals(99L, summary.getP99Micros());

    for (int i = 0; i < 1_000_000; i++) {
      recorder.recordOperation(250L, false);
    }
    assertEquals(PhaseLatencyRecorder.TOTAL_BUCKETS, recorder.getBucketArrayLength());
    assertEquals(12_392, recorder.getBucketArrayLength());
  }

  @Test
  public void testVerificationReportPassAndThunderingHerdDetection() {
    Map<ChannelPoolPhase, PhaseSummary> passSummaries = new EnumMap<>(ChannelPoolPhase.class);
    passSummaries.put(
        ChannelPoolPhase.WARMUP,
        new PhaseSummary(ChannelPoolPhase.WARMUP, 50, 100L, 0L, 2500L, 4000L, 5500L));
    passSummaries.put(
        ChannelPoolPhase.SPIKE_70,
        new PhaseSummary(ChannelPoolPhase.SPIKE_70, 210, 200L, 0L, 3000L, 5000L, 6500L));
    passSummaries.put(
        ChannelPoolPhase.SATURATION_100,
        new PhaseSummary(ChannelPoolPhase.SATURATION_100, 300, 300L, 0L, 4000L, 7000L, 9500L));
    passSummaries.put(
        ChannelPoolPhase.RECOVERY_40,
        new PhaseSummary(ChannelPoolPhase.RECOVERY_40, 120, 150L, 0L, 2600L, 4200L, 5800L));

    ChannelPoolVerificationReport passReport =
        new ChannelPoolVerificationReport(passSummaries, 1000L, 100_000_000L, 102_000_000L);
    assertTrue(passReport.isSpikeLatencyPassed());
    assertFalse(passReport.isThunderingHerdDetected());
    assertTrue(passReport.isSpikeErrorRatePassed());
    assertTrue(passReport.isRecoveryLatencyPassed());
    assertTrue(passReport.isRecoveryErrorRatePassed());
    assertTrue(passReport.isMemoryLeakGatePassed());
    assertTrue(passReport.isAllGatesPassed());
    assertEquals(1000L, passReport.getDeltaMicros());
    assertEquals(105_000_000L, passReport.getMaxAllowedHeapBytes());
    String passSummary = passReport.formatSummary();
    assertTrue(passSummary.contains("Spike (70%) Latency Gate (P90 <= T + delta): PASS"));
    assertTrue(passSummary.contains("Spike (70%) Thundering Herd Check (P90 < 2*T): PASS"));
    assertTrue(passSummary.contains("Spike (70%) Error Rate Gate (errors == 0): PASS"));
    assertTrue(passSummary.contains("Recovery (40%) Latency Gate (P90 <= T + delta): PASS"));
    assertTrue(passSummary.contains("Recovery (40%) Error Rate Gate (errors == 0): PASS"));
    assertTrue(passSummary.contains("Post-Test Memory Leak Gate (heap <= 1.05x baseline): PASS"));
    assertTrue(passSummary.contains("Overall Verification Verdict: PASS"));

    Map<ChannelPoolPhase, PhaseSummary> failSummaries = new EnumMap<>(passSummaries);
    failSummaries.put(
        ChannelPoolPhase.SPIKE_70,
        new PhaseSummary(ChannelPoolPhase.SPIKE_70, 210, 200L, 0L, 5000L, 8000L, 12000L));

    ChannelPoolVerificationReport thunderingHerdReport =
        new ChannelPoolVerificationReport(failSummaries, 1000L, 100_000_000L, 102_000_000L);
    assertFalse(thunderingHerdReport.isSpikeLatencyPassed());
    assertTrue(thunderingHerdReport.isThunderingHerdDetected());
    assertFalse(thunderingHerdReport.isAllGatesPassed());
    String herdSummary = thunderingHerdReport.formatSummary();
    assertTrue(herdSummary.contains("Spike (70%) Latency Gate (P90 <= T + delta): FAIL"));
    assertTrue(herdSummary.contains("Spike (70%) Thundering Herd Check (P90 < 2*T): DETECTED"));
    assertTrue(herdSummary.contains("Overall Verification Verdict: FAIL"));
  }

  @Test
  public void testVerificationReportMemoryLeakGateExactBoundary() {
    Map<ChannelPoolPhase, PhaseSummary> summaries = new EnumMap<>(ChannelPoolPhase.class);
    summaries.put(
        ChannelPoolPhase.WARMUP,
        new PhaseSummary(ChannelPoolPhase.WARMUP, 50, 100L, 0L, 2000L, 4000L, 5000L));
    summaries.put(
        ChannelPoolPhase.SPIKE_70,
        new PhaseSummary(ChannelPoolPhase.SPIKE_70, 210, 100L, 0L, 2200L, 4500L, 5500L));
    summaries.put(
        ChannelPoolPhase.SATURATION_100,
        new PhaseSummary(ChannelPoolPhase.SATURATION_100, 300, 100L, 0L, 3000L, 6000L, 8000L));
    summaries.put(
        ChannelPoolPhase.RECOVERY_40,
        new PhaseSummary(ChannelPoolPhase.RECOVERY_40, 120, 100L, 0L, 2100L, 4300L, 5200L));

    ChannelPoolVerificationReport atBoundary =
        new ChannelPoolVerificationReport(summaries, 1000L, 100_000_000L, 105_000_000L);
    assertTrue(atBoundary.isMemoryLeakGatePassed());
    assertTrue(atBoundary.isAllGatesPassed());

    ChannelPoolVerificationReport overBoundary =
        new ChannelPoolVerificationReport(summaries, 1000L, 100_000_000L, 105_000_001L);
    assertFalse(overBoundary.isMemoryLeakGatePassed());
    assertFalse(overBoundary.isAllGatesPassed());
  }

  @Test
  public void testPointSelectWithUnifiedProfileExecutesAllPhasesAndEmitsPhaseMetrics() {
    int exitCode =
        new CommandLine(new BenchmarkApp())
            .execute(
                "-p",
                "my-project",
                "-i",
                "my-instance",
                "-d",
                "my-database",
                "--host",
                "http://localhost:" + port,
                "point-select",
                "-t",
                "my_table",
                "--load-type",
                "unified-profile",
                "--min-channels",
                "1",
                "--max-channels",
                "3",
                "--warmup-cr",
                "2",
                "--spike-cr",
                "4",
                "--saturation-cr",
                "6",
                "--recovery-cr",
                "3",
                "--warmup-duration",
                "1s",
                "--spike-duration",
                "1s",
                "--saturation-duration",
                "1s",
                "--recovery-duration",
                "1s",
                "--post-test-wait",
                "0s");
    assertEquals(0, exitCode);
    assertTrue(
        "Should have received SELECT * FROM my_table WHERE id = @id",
        mockSpanner.getRequestsOfType(ExecuteSqlRequest.class).stream()
            .anyMatch(r -> r.getSql().contains("SELECT * FROM my_table WHERE id = @id")));
    assertNoErrors();

    Collection<MetricData> metrics = metricReader.collectAllMetrics();
    Set<String> opPhases = new HashSet<>();
    Set<String> latencyPhases = new HashSet<>();
    AttributeKey<String> phaseKey = AttributeKey.stringKey("phase");
    AttributeKey<String> benchmarkTypeKey = AttributeKey.stringKey("benchmark_type");
    AttributeKey<String> loadTypeKey = AttributeKey.stringKey("load_type");

    for (MetricData metricData : metrics) {
      if (BenchmarkApp.OPERATION_COUNT_NAME.equals(metricData.getName())) {
        for (PointData point : metricData.getData().getPoints()) {
          if (point instanceof LongPointData && ((LongPointData) point).getValue() > 0) {
            assertEquals("point-select", point.getAttributes().get(benchmarkTypeKey));
            assertEquals("unified_profile", point.getAttributes().get(loadTypeKey));
            String phase = point.getAttributes().get(phaseKey);
            if (phase != null) {
              opPhases.add(phase);
            }
          }
        }
      } else if (BenchmarkApp.LATENCY_NAME.equals(metricData.getName())) {
        for (PointData point : metricData.getData().getPoints()) {
          if (point instanceof HistogramPointData && ((HistogramPointData) point).getCount() > 0) {
            assertEquals("point-select", point.getAttributes().get(benchmarkTypeKey));
            assertEquals("unified_profile", point.getAttributes().get(loadTypeKey));
            String phase = point.getAttributes().get(phaseKey);
            if (phase != null) {
              latencyPhases.add(phase);
            }
          }
        }
      }
    }

    Set<String> expectedPhases = Set.of("warmup", "spike_70", "saturation_100", "recovery_40");
    assertEquals(expectedPhases, opPhases);
    assertEquals(expectedPhases, latencyPhases);
  }

  @Test
  public void testSelectAndUpdateWithUnifiedProfileExecutesTransactions() {
    int exitCode =
        new CommandLine(new BenchmarkApp())
            .execute(
                "-p",
                "my-project",
                "-i",
                "my-instance",
                "-d",
                "my-database",
                "--host",
                "http://localhost:" + port,
                "select-update",
                "-t",
                "my_table",
                "--load-type",
                "UNIFIED_PROFILE",
                "--min-channels",
                "1",
                "--max-channels",
                "3",
                "--warmup-cr",
                "2",
                "--spike-cr",
                "3",
                "--saturation-cr",
                "4",
                "--recovery-cr",
                "2",
                "--warmup-duration",
                "1s",
                "--spike-duration",
                "1s",
                "--saturation-duration",
                "1s",
                "--recovery-duration",
                "1s",
                "--post-test-wait",
                "0s");
    assertEquals(0, exitCode);
    assertTrue(
        "Should have received UPDATE or INSERT statements on my_table",
        mockSpanner.getRequestsOfType(ExecuteSqlRequest.class).stream()
            .anyMatch(
                r ->
                    r.getSql().contains("UPDATE my_table")
                        || r.getSql().contains("INSERT INTO my_table")));
    assertNoErrors();
  }

  @Test
  public void testWorkerSurvivesTransientCancelledException() throws Exception {
    SpannerOptions options =
        SpannerOptions.newBuilder()
            .setProjectId("my-project")
            .setHost("http://localhost:" + port)
            .setChannelConfigurator(builder -> builder.usePlaintext())
            .setCredentials(NoCredentials.getInstance())
            .build();

    Meter meter = meterProvider.get(BenchmarkApp.METER_NAME);
    BenchmarkMetrics metrics =
        BenchmarkApp.createBenchmarkMetrics(meter, BenchmarkApp.LATENCY_NAME);

    try (Spanner spanner = options.getService()) {
      DatabaseClient client =
          spanner.getDatabaseClient(DatabaseId.of("my-project", "my-instance", "my-database"));

      try (ResultSet rs =
          client
              .singleUse(TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS))
              .executeQuery(
                  Statement.newBuilder("SELECT * FROM my_table WHERE id = @id")
                      .bind("id")
                      .to(1L)
                      .build())) {
        while (rs.next()) {}
      }
      mockSpanner.addException(Status.CANCELLED.asRuntimeException());

      PointSelectBenchmark benchmark =
          new PointSelectBenchmark(
              client,
              metrics.latencyHistogram,
              metrics.operationCounter,
              metrics.errorCounter,
              metrics.memoryUsageHistogram,
              metrics.cpuUtilizationHistogram,
              "5s",
              "my_table",
              1L,
              1000L,
              10.0,
              1,
              Duration.ofSeconds(1),
              false,
              "",
              LoadType.UNIFIED_PROFILE,
              Duration.ofHours(1),
              2.0,
              1.0,
              1.0,
              0.1,
              false);
      UnifiedProfileConfig config =
          new UnifiedProfileConfig(
              1,
              3,
              1,
              1,
              1,
              1,
              Duration.ofMillis(200),
              Duration.ofMillis(200),
              Duration.ofMillis(200),
              Duration.ofMillis(200),
              Duration.ZERO,
              1000L,
              false);
      benchmark.setUnifiedProfileConfig(config);
      benchmark.run();

      ChannelPoolVerificationReport report = benchmark.getLastVerificationReport();
      assertNotNull(report);
      for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
        PhaseSummary summary = report.getPhaseSummaries().get(phase);
        assertNotNull(summary);
        assertTrue(
            "Phase " + phase + " should have completed operations after transient CANCELLED",
            summary.getOperationCount() > 0);
        assertEquals(0L, summary.getErrorCount());
      }
    }
    assertNoErrors();
  }

  @Test
  public void testUnifiedProfileValidationRejectsInvalidOptions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new UnifiedProfileConfig(
                0,
                3,
                50,
                210,
                300,
                120,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ZERO,
                1000L,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new UnifiedProfileConfig(
                2,
                1,
                50,
                210,
                300,
                120,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ZERO,
                1000L,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new UnifiedProfileConfig(
                1,
                3,
                50,
                210,
                300,
                120,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ZERO,
                -1L,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () -> UnifiedProfileConfig.validateConcurrency(0, "warmup-cr"));
    assertThrows(
        IllegalArgumentException.class,
        () -> UnifiedProfileConfig.parsePhaseDuration("inf", "warmup-duration"));
    assertThrows(
        IllegalArgumentException.class,
        () -> UnifiedProfileConfig.parsePhaseDuration("1.5m", "spike-duration"));

    PointSelectCommand cmd = new PointSelectCommand();
    cmd.loadType = LoadType.UNIFIED_PROFILE;
    cmd.burstFactor = 2.0;
    assertThrows(IllegalArgumentException.class, cmd::validateOptions);
  }

  @Test
  public void testEnforceGatesFailsWithNonZeroExitCodeWhenWarmupIsSkipped() throws Exception {
    SpannerOptions options =
        SpannerOptions.newBuilder()
            .setProjectId("my-project")
            .setHost("http://localhost:" + port)
            .setChannelConfigurator(builder -> builder.usePlaintext())
            .setCredentials(NoCredentials.getInstance())
            .build();

    Meter meter = meterProvider.get(BenchmarkApp.METER_NAME);
    BenchmarkMetrics metrics =
        BenchmarkApp.createBenchmarkMetrics(meter, BenchmarkApp.LATENCY_NAME);

    try (Spanner spanner = options.getService()) {
      DatabaseClient client =
          spanner.getDatabaseClient(DatabaseId.of("my-project", "my-instance", "my-database"));
      PointSelectBenchmark benchmark =
          new PointSelectBenchmark(
              client,
              metrics.latencyHistogram,
              metrics.operationCounter,
              metrics.errorCounter,
              metrics.memoryUsageHistogram,
              metrics.cpuUtilizationHistogram,
              "5s",
              "my_table",
              1L,
              1000L,
              10.0,
              1,
              Duration.ofSeconds(1),
              false,
              "",
              LoadType.UNIFIED_PROFILE,
              Duration.ofHours(1),
              2.0,
              1.0,
              1.0,
              0.1,
              false);
      UnifiedProfileConfig config =
          new UnifiedProfileConfig(
              1,
              3,
              1,
              1,
              1,
              1,
              Duration.ZERO,
              Duration.ofMillis(200),
              Duration.ZERO,
              Duration.ZERO,
              Duration.ZERO,
              1000L,
              true);
      assertThrows(
          IllegalStateException.class, () -> new UnifiedProfileRunner(benchmark, config).run());
    }

    int exitCode =
        new CommandLine(new BenchmarkApp())
            .execute(
                "-p",
                "my-project",
                "-i",
                "my-instance",
                "-d",
                "my-database",
                "--host",
                "http://localhost:" + port,
                "point-select",
                "-t",
                "my_table",
                "--load-type",
                "unified-profile",
                "--warmup-cr",
                "1",
                "--spike-cr",
                "1",
                "--saturation-cr",
                "1",
                "--recovery-cr",
                "1",
                "--warmup-duration",
                "0s",
                "--spike-duration",
                "1s",
                "--saturation-duration",
                "0s",
                "--recovery-duration",
                "0s",
                "--post-test-wait",
                "0s",
                "--enforce-gates");
    assertNotEquals(0, exitCode);
  }
}
