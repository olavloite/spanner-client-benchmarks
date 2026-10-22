package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.google.cloud.spanner.benchmark.LoadType.BenchmarkYamlConfig;
import com.google.cloud.spanner.benchmark.LoadType.StepConfig;
import com.google.cloud.spanner.benchmark.PhaseLatencyRecorder.PhaseSummary;
import com.google.spanner.v1.ExecuteSqlRequest;
import io.grpc.Status;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
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

    BenchmarkYamlConfig builtIn =
        BenchmarkYamlConfig.fromFileOrDefault("default", null, null, null, null, null, null);
    assertEquals(1, builtIn.getMinChannels());
    assertEquals(3, builtIn.getMaxChannels());
    assertEquals(4, builtIn.getSteps().size());
    assertEquals(50, builtIn.getPhaseConcurrencies().get(ChannelPoolPhase.WARMUP).intValue());
    assertEquals(210, builtIn.getPhaseConcurrencies().get(ChannelPoolPhase.SPIKE_70).intValue());
    assertEquals(300, builtIn.getPhaseConcurrencies().get(ChannelPoolPhase.SATURATION_100).intValue());
    assertEquals(120, builtIn.getPhaseConcurrencies().get(ChannelPoolPhase.RECOVERY_40).intValue());

    BenchmarkYamlConfig scaledSix =
        BenchmarkYamlConfig.fromFileOrDefault(
            "default", LoadType.SPIKY, 2, 6, Duration.ZERO, 500L, true);
    assertEquals(2, scaledSix.getMinChannels());
    assertEquals(6, scaledSix.getMaxChannels());
    assertEquals(100, scaledSix.getPhaseConcurrencies().get(ChannelPoolPhase.WARMUP).intValue());
    assertEquals(420, scaledSix.getPhaseConcurrencies().get(ChannelPoolPhase.SPIKE_70).intValue());
    assertEquals(600, scaledSix.getPhaseConcurrencies().get(ChannelPoolPhase.SATURATION_100).intValue());
    assertEquals(240, scaledSix.getPhaseConcurrencies().get(ChannelPoolPhase.RECOVERY_40).intValue());
    assertEquals(500L, scaledSix.getDeltaMicros());
    assertTrue(scaledSix.isEnforceGates());
  }

  @Test
  public void testGenericYamlConfigAcrossAllLoadTypes() throws Exception {
    String multiLoadYaml =
        "channelPool:\n"
            + "  minChannels: 1\n"
            + "  maxChannels: 2\n"
            + "steps:\n"
            + "  - name: steady_baseline\n"
            + "    loadType: steady\n"
            + "    tps: 50\n"
            + "    threads: 8\n"
            + "    duration: 2s\n"
            + "  - name: spiky_burst\n"
            + "    loadType: spiky\n"
            + "    tps: 100\n"
            + "    threads: 8\n"
            + "    burstFactor: 3.0\n"
            + "    burstDuration: 1.0\n"
            + "    burstFraction: 0.2\n"
            + "    duration: 2s\n"
            + "  - name: gradual_ramp\n"
            + "    loadType: gradual\n"
            + "    tps: 80\n"
            + "    threads: 8\n"
            + "    cycleDuration: 10s\n"
            + "    peakFactor: 2.5\n"
            + "    duration: 2s\n"
            + "  - name: closed_loop_peak\n"
            + "    loadType: closed-loop\n"
            + "    concurrency: 50%\n"
            + "    duration: 2s\n";
    BenchmarkYamlConfig parsed = BenchmarkYamlConfig.fromYaml(multiLoadYaml);
    List<StepConfig> steps = parsed.getSteps();
    assertEquals(4, steps.size());
    assertEquals(LoadType.STEADY, steps.get(0).getLoadType());
    assertEquals(50, steps.get(0).getTps());
    assertEquals(8, steps.get(0).getConcurrency());
    assertFalse(steps.get(0).isClosedLoopStep());
    assertEquals(LoadType.SPIKY, steps.get(1).getLoadType());
    assertEquals(3.0, steps.get(1).getBurstFactor(), 0.001);
    assertEquals(LoadType.GRADUAL, steps.get(2).getLoadType());
    assertEquals(2.5, steps.get(2).getPeakFactor(), 0.001);
    assertEquals(LoadType.CLOSED_LOOP, steps.get(3).getLoadType());
    assertEquals(100, steps.get(3).getConcurrency());
    assertTrue(steps.get(3).isClosedLoopStep());

    String singleStepGradualYaml =
        "steps:\n"
            + "  - name: gradual_run\n"
            + "    loadType: gradual\n"
            + "    tps: 120\n"
            + "    threads: 16\n"
            + "    cycleDuration: 30m\n"
            + "    peakFactor: 3.0\n"
            + "    duration: 5m\n";
    Path tempSingle = Files.createTempFile("single-gradual-", ".yaml");
    try {
      Files.writeString(tempSingle, singleStepGradualYaml, StandardCharsets.UTF_8);
      PointSelectCommand cmd = new PointSelectCommand();
      cmd.configFile = tempSingle.toString();
      cmd.validateOptions();
      BenchmarkYamlConfig singleParsed = cmd.buildYamlConfig();
      assertEquals(1, singleParsed.getSteps().size());
      assertEquals(LoadType.GRADUAL, singleParsed.getSteps().get(0).getLoadType());
      assertEquals(120, singleParsed.getSteps().get(0).getTps());
      assertEquals(16, singleParsed.getSteps().get(0).getConcurrency());
      assertEquals(Duration.ofMinutes(5), singleParsed.getSteps().get(0).getDuration());
      assertEquals(Duration.ofMinutes(30), singleParsed.getSteps().get(0).getCycleDuration());
      assertEquals(3.0, singleParsed.getSteps().get(0).getPeakFactor(), 0.001);
    } finally {
      Files.deleteIfExists(tempSingle);
    }
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
                "spiky",
                "--config",
                "steps:\n  - warmup:2:1s\n  - spike_70:4:1s\n  - saturation_100:6:1s\n  - recovery_40:3:1s\n",
                "--min-channels",
                "1",
                "--max-channels",
                "3",
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
            assertEquals("spiky", point.getAttributes().get(loadTypeKey));
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
            assertEquals("spiky", point.getAttributes().get(loadTypeKey));
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
                "SPIKY",
                "--config",
                "verification:\n  enforceGates: true\nsteps:\n  - warmup:2:1s\n  - spike_70:3:1s\n  - saturation_100:4:1s\n  - recovery_40:2:1s\n",
                "--min-channels",
                "1",
                "--max-channels",
                "3",
                "--enforce-gates=false",
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
  public void testMultiStepMixedLoadTypesEndToEnd() throws Exception {
    Path yamlFile = Files.createTempFile("mixed-load-steps-", ".yaml");
    Files.writeString(
        yamlFile,
        "steps:\n"
            + "  - name: steady_step\n"
            + "    loadType: steady\n"
            + "    tps: 20\n"
            + "    threads: 2\n"
            + "    duration: 1s\n"
            + "  - name: spiky_step\n"
            + "    loadType: spiky\n"
            + "    concurrency: 3\n"
            + "    duration: 1s\n"
            + "  - name: closed_loop_step\n"
            + "    loadType: closed-loop\n"
            + "    concurrency: 2\n"
            + "    duration: 1s\n",
        StandardCharsets.UTF_8);
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
                "--config",
                yamlFile.toString(),
                "--post-test-wait",
                "0s");
    Files.deleteIfExists(yamlFile);
    assertEquals(0, exitCode);
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
              LoadType.SPIKY,
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
    assertThrows(
        IllegalArgumentException.class,
        () -> LoadType.parseStepConcurrency("0%", 3, "step_1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LoadType.parseStepConcurrency("NaN%", 3, "step_1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LoadType.parseStepConcurrency("Infinity%", 3, "step_1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LoadType.parseStepConcurrency("20000", 3, "step_1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BenchmarkYamlConfig.fromYaml("loadType: steady\nsteps:\n  - 10:1s\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BenchmarkYamlConfig.fromYaml("threads: 8\nsteps:\n  - 10:1s\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BenchmarkYamlConfig.fromYaml("channelPool:\n  minChannels: 1\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BenchmarkYamlConfig.fromYaml("verification:\n  minChannels: 2\nsteps:\n  - 10:1s\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BenchmarkYamlConfig.fromYaml("channelPool:\n  minChannels: 1\n  minChannels: 2\nsteps:\n  - 10:1s\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BenchmarkYamlConfig.fromYaml("steps:\n  - warmup:10:1s\n  - warmup:20:1s\n"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BenchmarkYamlConfig.fromYaml("verification:\n  enforceGates: true\nsteps:\n  - custom:10:1s\n"));

    PointSelectCommand cmd = new PointSelectCommand();
    cmd.loadType = LoadType.SPIKY;
    cmd.configFile = "default";
    cmd.burstFactor = 2.0;
    assertThrows(IllegalArgumentException.class, cmd::validateOptions);

    PointSelectCommand blankConfigCmd = new PointSelectCommand();
    blankConfigCmd.configFile = "   ";
    assertThrows(IllegalArgumentException.class, blankConfigCmd::validateOptions);
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
              LoadType.SPIKY,
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
                "spiky",
                "--config",
                "steps:\n  - warmup:1:0s\n  - spike_70:1:1s\n  - saturation_100:1:0s\n  - recovery_40:1:0s\n",
                "--post-test-wait",
                "0s",
                "--enforce-gates");
    assertNotEquals(0, exitCode);
  }
}
