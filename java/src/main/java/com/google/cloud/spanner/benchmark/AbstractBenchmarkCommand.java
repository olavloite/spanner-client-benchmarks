package com.google.cloud.spanner.benchmark;

import static com.google.cloud.spanner.benchmark.BenchmarkApp.LATENCY_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.METER_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.initializeOpenTelemetry;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import io.grpc.Server;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import java.time.Duration;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

public abstract class AbstractBenchmarkCommand implements Runnable {
  @ParentCommand protected BenchmarkApp parent;

  @Option(
      names = {"-t", "--table"},
      description = "Table name",
      required = true)
  protected String tableName;

  @Option(
      names = {"--num-rows"},
      description = "Number of rows to generate/select")
  protected long numRows = 1000000;

  @Option(
      names = {"--tps"},
      description = "Target transactions per second")
  protected double tps = 10.0;

  @Option(
      names = {"--threads"},
      description = "Number of threads in the pool",
      defaultValue = "10")
  protected int threads;

  @Option(
      names = {"--load-type"},
      description = "Load type (STEADY, SPIKY, GRADUAL, CLOSED_LOOP, UNIFIED_PROFILE)",
      defaultValue = "STEADY",
      converter = LoadTypeConverter.class)
  protected LoadType loadType = LoadType.STEADY;

  @Option(
      names = {"--cycle-duration"},
      description = "Duration of a full cycle for gradual load")
  protected String cycleDuration;

  @Option(
      names = {"--peak-factor"},
      description = "Ratio of peak rate to average rate for gradual load")
  protected Double peakFactor;

  @Option(
      names = {"--burst-factor"},
      description = "Ratio of burst rate to average rate")
  protected Double burstFactor;

  @Option(
      names = {"--burst-duration"},
      description = "Average duration of a burst in seconds")
  protected Double burstDuration;

  @Option(
      names = {"--burst-fraction"},
      description = "Fraction of total time spent in the burst state")
  protected Double burstFraction;

  @Option(
      names = {"--min-channels"},
      description =
          "Minimum (and initial) number of gRPC channels in the pool (for UNIFIED_PROFILE)",
      defaultValue = "1")
  protected int minChannels = 1;

  @Option(
      names = {"--max-channels"},
      description = "Maximum number of gRPC channels in the pool M (for UNIFIED_PROFILE)",
      defaultValue = "3")
  protected int maxChannels = 3;

  @Option(
      names = {"--warmup-duration"},
      description = "Phase 1 (Warmup at 50 CR) duration (for UNIFIED_PROFILE)",
      defaultValue = "5m")
  protected String warmupDuration = "5m";

  @Option(
      names = {"--spike-duration"},
      description = "Phase 2 (70%% Capacity Spike at 210 CR) duration (for UNIFIED_PROFILE)",
      defaultValue = "10m")
  protected String spikeDuration = "10m";

  @Option(
      names = {"--saturation-duration"},
      description =
          "Phase 3 Step 1 (100%% Capacity Saturation at 300 CR) duration (for UNIFIED_PROFILE)",
      defaultValue = "5m")
  protected String saturationDuration = "5m";

  @Option(
      names = {"--recovery-duration"},
      description =
          "Phase 3 Steps 2-3 (40%% Capacity Recovery at 120 CR) duration (for UNIFIED_PROFILE)",
      defaultValue = "15m")
  protected String recoveryDuration = "15m";

  @Option(
      names = {"--post-test-wait"},
      description = "Idle wait duration before post-test heap measurement (for UNIFIED_PROFILE)",
      defaultValue = "5m")
  protected String postTestWait = "5m";

  @Option(
      names = {"--warmup-cr"},
      description = "Override concurrent requests during Phase 1 Warmup (defaults to 50 for M=3)")
  protected Integer warmupCr;

  @Option(
      names = {"--spike-cr"},
      description = "Override concurrent requests during Phase 2 Spike (defaults to M * 70)")
  protected Integer spikeCr;

  @Option(
      names = {"--saturation-cr"},
      description = "Override concurrent requests during Phase 3 Saturation (defaults to M * 100)")
  protected Integer saturationCr;

  @Option(
      names = {"--recovery-cr"},
      description = "Override concurrent requests during Phase 3 Recovery (defaults to M * 40)")
  protected Integer recoveryCr;

  @Option(
      names = {"--delta-us"},
      description =
          "Allowed client-side P90 latency overhead buffer in microseconds (default 1000us = 1ms)",
      defaultValue = "1000")
  protected long deltaMicros = 1000L;

  @Option(
      names = {"--enforce-gates"},
      description =
          "Throw an IllegalStateException if any verification gate fails (for UNIFIED_PROFILE)",
      defaultValue = "false")
  protected boolean enforceGates;

  protected String getMetricName() {
    return LATENCY_NAME;
  }

  protected UnifiedProfileConfig buildUnifiedProfileConfig() {
    int resolvedWarmupCr =
        warmupCr != null ? warmupCr : UnifiedProfileConfig.defaultWarmupConcurrency(maxChannels);
    int resolvedSpikeCr =
        spikeCr != null ? spikeCr : UnifiedProfileConfig.defaultSpikeConcurrency(maxChannels);
    int resolvedSaturationCr =
        saturationCr != null
            ? saturationCr
            : UnifiedProfileConfig.defaultSaturationConcurrency(maxChannels);
    int resolvedRecoveryCr =
        recoveryCr != null
            ? recoveryCr
            : UnifiedProfileConfig.defaultRecoveryConcurrency(maxChannels);
    return new UnifiedProfileConfig(
        minChannels,
        maxChannels,
        resolvedWarmupCr,
        resolvedSpikeCr,
        resolvedSaturationCr,
        resolvedRecoveryCr,
        UnifiedProfileConfig.parsePhaseDuration(warmupDuration, "warmup-duration"),
        UnifiedProfileConfig.parsePhaseDuration(spikeDuration, "spike-duration"),
        UnifiedProfileConfig.parsePhaseDuration(saturationDuration, "saturation-duration"),
        UnifiedProfileConfig.parsePhaseDuration(recoveryDuration, "recovery-duration"),
        UnifiedProfileConfig.parsePhaseDuration(postTestWait, "post-test-wait"),
        deltaMicros,
        enforceGates);
  }

  protected void validateOptions() {
    if (loadType == LoadType.STEADY) {
      if (cycleDuration != null
          || peakFactor != null
          || burstFactor != null
          || burstDuration != null
          || burstFraction != null) {
        throw new IllegalArgumentException(
            "Cannot specify burst or gradual load options when load-type is steady");
      }
    } else if (loadType == LoadType.SPIKY) {
      if (cycleDuration != null || peakFactor != null) {
        throw new IllegalArgumentException(
            "Cannot specify gradual load options when load-type is spiky");
      }
      // Set defaults if not specified
      if (burstFactor == null) burstFactor = 1.0;
      if (burstDuration == null) burstDuration = 1.0;
      if (burstFraction == null) burstFraction = 0.1;
    } else if (loadType == LoadType.GRADUAL) {
      if (burstFactor != null || burstDuration != null || burstFraction != null) {
        throw new IllegalArgumentException(
            "Cannot specify burst load options when load-type is gradual");
      }
      // Set defaults if not specified
      if (cycleDuration == null) cycleDuration = "1h";
      if (peakFactor == null) peakFactor = 2.0;
    } else if (loadType == LoadType.UNIFIED_PROFILE) {
      if (cycleDuration != null
          || peakFactor != null
          || burstFactor != null
          || burstDuration != null
          || burstFraction != null) {
        throw new IllegalArgumentException(
            "Cannot specify burst or gradual load options when load-type is unified-profile");
      }
      if (numRows <= 0) {
        throw new IllegalArgumentException("num-rows must be > 0");
      }
      buildUnifiedProfileConfig();
    }

    // Set defaults for anything still null to avoid NPE during unboxing
    if (burstFactor == null) burstFactor = 1.0;
    if (burstDuration == null) burstDuration = 1.0;
    if (burstFraction == null) burstFraction = 0.1;
    if (cycleDuration == null) cycleDuration = "1h";
    if (peakFactor == null) peakFactor = 2.0;
  }

  @Override
  public void run() {
    validateOptions();

    Server server = null;
    if (parent.isMock()) {
      server = MockServerUtil.startMockSpannerServer(parent, tableName);
    }

    try {
      // Initialize OpenTelemetry
      OpenTelemetry openTelemetry =
          initializeOpenTelemetry(
              parent.getProjectId(),
              parent.getHost(),
              parent.getBenchmarkName(),
              parent.isNoMetrics());
      Meter meter = openTelemetry.getMeter(METER_NAME);
      BenchmarkMetrics metrics = BenchmarkApp.createBenchmarkMetrics(meter, getMetricName());

      // Initialize Spanner
      SpannerOptions.Builder spannerOptionsBuilder =
          SpannerOptions.newBuilder().setProjectId(parent.getProjectId());
      String emulatorHost = System.getenv("SPANNER_EMULATOR_HOST");
      if (emulatorHost != null && !emulatorHost.isEmpty()) {
        spannerOptionsBuilder
            .setEmulatorHost(emulatorHost)
            .setCredentials(NoCredentials.getInstance());
      } else if (parent.getHost() != null) {
        spannerOptionsBuilder.setHost(parent.getHost());
        spannerOptionsBuilder.setChannelConfigurator(builder -> builder.usePlaintext());
        spannerOptionsBuilder.setCredentials(NoCredentials.getInstance());
      }
      if (loadType == LoadType.UNIFIED_PROFILE) {
        spannerOptionsBuilder
            .enableDynamicChannelPool()
            .setGcpChannelPoolOptions(
                UnifiedProfileConfig.buildChannelPoolOptions(minChannels, maxChannels));
      } else {
        String numChannelsStr = System.getenv("SPANNER_NUM_CHANNELS");
        if (numChannelsStr != null && !numChannelsStr.isEmpty()) {
          try {
            int numChannels = Integer.parseInt(numChannelsStr);
            spannerOptionsBuilder.setNumChannels(numChannels);
            System.out.println("Configured Spanner Java client with " + numChannels + " channels.");
          } catch (NumberFormatException e) {
            System.err.println("Invalid SPANNER_NUM_CHANNELS value: " + numChannelsStr);
          }
        }
      }
      SpannerOptions spannerOptions = spannerOptionsBuilder.build();
      if (Boolean.TRUE.equals(spannerOptions.isEnableDirectAccess())
          || "true".equalsIgnoreCase(System.getenv("GOOGLE_SPANNER_ENABLE_DIRECT_ACCESS"))) {
        System.out.println(
            "Configured Spanner Java client with DirectPath (direct access) enabled.");
      }
      try (Spanner spanner = spannerOptions.getService()) {
        DatabaseClient client =
            spanner.getDatabaseClient(
                DatabaseId.of(
                    parent.getProjectId(), parent.getInstanceId(), parent.getDatabaseId()));

        Duration duration = AbstractBenchmark.parseDuration(parent.getDuration());
        boolean forAlerting = parent.isForAlerting();
        String benchmarkName = parent.getBenchmarkName();
        AbstractBenchmark benchmark =
            createBenchmark(
                client,
                metrics.latencyHistogram,
                metrics.operationCounter,
                metrics.errorCounter,
                metrics.memoryUsageHistogram,
                metrics.cpuUtilizationHistogram,
                parent.getResourceProbeInterval(),
                duration,
                forAlerting,
                benchmarkName,
                parent.isMock());
        if (loadType == LoadType.UNIFIED_PROFILE) {
          benchmark.setUnifiedProfileConfig(buildUnifiedProfileConfig());
        }
        benchmark.run();
      }
    } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
      throw e;
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (server != null) {
        server.shutdown();
      }
    }
  }

  protected abstract AbstractBenchmark createBenchmark(
      DatabaseClient client,
      LongHistogram latencyHistogram,
      LongCounter operationCounter,
      LongCounter errorCounter,
      LongHistogram memoryUsageHistogram,
      DoubleHistogram cpuUtilizationHistogram,
      String resourceProbeInterval,
      Duration duration,
      boolean forAlerting,
      String benchmarkName,
      boolean isMock);

  public static class LoadTypeConverter implements picocli.CommandLine.ITypeConverter<LoadType> {
    @Override
    public LoadType convert(String value) throws Exception {
      return LoadType.fromString(value);
    }
  }
}
