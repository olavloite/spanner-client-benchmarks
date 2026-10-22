package com.google.cloud.spanner.benchmark;

import static com.google.cloud.spanner.benchmark.BenchmarkApp.LATENCY_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.METER_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.initializeOpenTelemetry;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.benchmark.LoadType.BenchmarkYamlConfig;
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
      description = "Load type (STEADY, SPIKY, GRADUAL, CLOSED_LOOP)",
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
      names = {"--config"},
      description =
          "Path to a YAML benchmark configuration file (or 'default') configuring benchmark"
              + " options and multi-step workloads across all load types (STEADY, SPIKY, GRADUAL,"
              + " CLOSED_LOOP)")
  protected String configFile;

  @Option(
      names = {"--min-channels"},
      description =
          "Minimum (and initial) number of gRPC channels in the pool (overrides YAML minChannels)")
  protected Integer minChannels;

  @Option(
      names = {"--max-channels"},
      description =
          "Maximum number of gRPC channels in the pool M, up to 100 (overrides YAML maxChannels)")
  protected Integer maxChannels;

  @Option(
      names = {"--post-test-wait"},
      description =
          "Idle wait duration before post-test heap measurement (overrides YAML postTestWait)")
  protected String postTestWait;

  @Option(
      names = {"--delta-us"},
      description =
          "Allowed client-side P90 latency overhead buffer in microseconds (overrides YAML deltaMicros)")
  protected Long deltaMicros;

  @Option(
      names = {"--enforce-gates"},
      arity = "0..1",
      fallbackValue = "true",
      description =
          "Throw an IllegalStateException if any verification gate fails (overrides YAML enforceGates)")
  protected Boolean enforceGates;

  private BenchmarkYamlConfig resolvedYamlConfig;

  protected String getMetricName() {
    return LATENCY_NAME;
  }

  protected BenchmarkYamlConfig buildYamlConfig() {
    Duration postWaitOverride =
        postTestWait != null ? LoadType.parseStepDuration(postTestWait, "post-test-wait") : null;
    return BenchmarkYamlConfig.fromFileOrDefault(
        configFile,
        loadType,
        minChannels,
        maxChannels,
        postWaitOverride,
        deltaMicros,
        enforceGates);
  }

  protected void validateOptions() {
    resolvedYamlConfig = null;
    if (configFile != null) {
      if (numRows <= 0) {
        throw new IllegalArgumentException("num-rows must be > 0");
      }
      resolvedYamlConfig = buildYamlConfig();
      if (cycleDuration != null
          || peakFactor != null
          || burstFactor != null
          || burstDuration != null
          || burstFraction != null) {
        throw new IllegalArgumentException(
            "Cannot combine --config multi-step YAML with single-step CLI burst or gradual flags");
      }
      if (cycleDuration == null) cycleDuration = "1h";
      if (peakFactor == null) peakFactor = 2.0;
      if (burstFactor == null) burstFactor = 1.0;
      if (burstDuration == null) burstDuration = 1.0;
      if (burstFraction == null) burstFraction = 0.1;
      return;
    }
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
      BenchmarkYamlConfig yamlCfg =
          resolvedYamlConfig != null
              ? resolvedYamlConfig
              : (configFile != null ? buildYamlConfig() : null);
      if (yamlCfg != null && yamlCfg.isChannelPoolConfigured()) {
        spannerOptionsBuilder
            .enableDynamicChannelPool()
            .setGcpChannelPoolOptions(
                BenchmarkYamlConfig.buildChannelPoolOptions(
                    yamlCfg.getMinChannels(), yamlCfg.getMaxChannels()));
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
        if (yamlCfg != null) {
          benchmark.setYamlConfig(yamlCfg);
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
