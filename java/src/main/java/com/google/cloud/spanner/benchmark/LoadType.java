package com.google.cloud.spanner.benchmark;

import com.google.cloud.grpc.GcpManagedChannelOptions.GcpChannelPoolOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

public enum LoadType {
  STEADY {
    @Override
    public void run(AbstractBenchmark benchmark, ExecutorService executor) {
      long nextTickTime = System.nanoTime();
      long tickDurationNs = 1_000_000L; // 1ms
      long poissonTimelineNs = System.nanoTime();

      while (!Thread.currentThread().isInterrupted()) {
        long now = System.nanoTime();
        if (nextTickTime < now) {
          nextTickTime = now;
        }
        long targetTickEnd = nextTickTime + tickDurationNs;

        if (poissonTimelineNs < nextTickTime) {
          poissonTimelineNs = nextTickTime;
        }

        // Calculate number of tasks for this 1ms tick
        int count = 0;
        while (poissonTimelineNs < targetTickEnd) {
          count++;
          long delay = AbstractBenchmark.calculatePoissonDelay(benchmark.tps);
          poissonTimelineNs += delay;
        }

        for (int i = 0; i < count; i++) {
          benchmark.submitTask(executor);
        }

        nextTickTime += tickDurationNs;
        AbstractBenchmark.sleepHybrid(nextTickTime);
      }
    }
  },
  SPIKY {
    @Override
    public void run(AbstractBenchmark benchmark, ExecutorService executor) {
      double tps = benchmark.tps;
      double rBurst = tps * benchmark.burstFactor;
      double rNormal = (tps - benchmark.burstFraction * rBurst) / (1.0 - benchmark.burstFraction);

      double mu2 = 1.0 / benchmark.burstDuration;
      double mu1 = mu2 * benchmark.burstFraction / (1.0 - benchmark.burstFraction);

      boolean inBurst = false;
      long nextStateChangeTime = System.nanoTime() + AbstractBenchmark.calculatePoissonDelay(mu1);
      long nextTickTime = System.nanoTime();
      long tickDurationNs = 1_000_000L; // 1ms
      long poissonTimelineNs = System.nanoTime();

      while (!Thread.currentThread().isInterrupted()) {
        long now = System.nanoTime();
        if (nextTickTime < now) {
          nextTickTime = now;
        }
        long targetTickEnd = nextTickTime + tickDurationNs;

        if (poissonTimelineNs < nextTickTime) {
          poissonTimelineNs = nextTickTime;
        }

        if (now >= nextStateChangeTime) {
          inBurst = !inBurst;
          long nextDelay =
              inBurst
                  ? AbstractBenchmark.calculatePoissonDelay(mu2)
                  : AbstractBenchmark.calculatePoissonDelay(mu1);
          nextStateChangeTime = now + nextDelay;
        }

        double currentRate = inBurst ? rBurst : rNormal;

        // Calculate number of tasks for this 1ms tick
        int count = 0;
        if (currentRate > 0) {
          while (poissonTimelineNs < targetTickEnd) {
            count++;
            long delay = AbstractBenchmark.calculatePoissonDelay(currentRate);
            poissonTimelineNs += delay;
          }
        } else {
          poissonTimelineNs = targetTickEnd;
        }

        for (int i = 0; i < count; i++) {
          benchmark.submitTask(executor);
        }

        nextTickTime += tickDurationNs;
        AbstractBenchmark.sleepHybrid(nextTickTime);
      }
    }
  },
  GRADUAL {
    @Override
    public void run(AbstractBenchmark benchmark, ExecutorService executor) {
      long startTimeNs = System.nanoTime();
      long cycleDurationNs = benchmark.cycleDuration.toNanos();
      double amplitude = benchmark.tps * (benchmark.peakFactor - 1.0);
      double tps = benchmark.tps;
      long nextTickTime = System.nanoTime();
      long tickDurationNs = 1_000_000L; // 1ms
      long poissonTimelineNs = System.nanoTime();

      while (!Thread.currentThread().isInterrupted()) {
        long nowNs = System.nanoTime();
        if (nextTickTime < nowNs) {
          nextTickTime = nowNs;
        }
        long targetTickEnd = nextTickTime + tickDurationNs;

        if (poissonTimelineNs < nextTickTime) {
          poissonTimelineNs = nextTickTime;
        }

        long elapsedNs = nowNs - startTimeNs;

        // Calculate rate based on sine wave
        double angle = (2.0 * Math.PI * (elapsedNs % cycleDurationNs)) / cycleDurationNs;
        double currentRate = tps + amplitude * Math.cos(angle - Math.PI);

        // Calculate number of tasks for this 1ms tick
        int count = 0;
        if (currentRate > 0) {
          while (poissonTimelineNs < targetTickEnd) {
            count++;
            long delay = AbstractBenchmark.calculatePoissonDelay(currentRate);
            poissonTimelineNs += delay;
          }
        } else {
          poissonTimelineNs = targetTickEnd;
        }

        for (int i = 0; i < count; i++) {
          benchmark.submitTask(executor);
        }

        nextTickTime += tickDurationNs;
        AbstractBenchmark.sleepHybrid(nextTickTime);
      }
    }
  },
  CLOSED_LOOP {
    @Override
    public void run(AbstractBenchmark benchmark, ExecutorService executor) {
      for (int i = 0; i < benchmark.threads; i++) {
        executor.submit(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                long startTime = System.nanoTime();
                try {
                  benchmark.executeOperation();
                } catch (Exception e) {
                  if (!Thread.currentThread().isInterrupted()
                      && !AbstractBenchmark.isCancellationOrInterruption(e)) {
                    benchmark.errorCounter.add(1, benchmark.getAttributes());
                  }
                }
                long duration = (System.nanoTime() - startTime) / 1000;
                benchmark.operationCounter.add(1, benchmark.getAttributes());
                if (benchmark.shouldMeasureEntireMethod()) {
                  benchmark.latencyHistogram.record(duration, benchmark.getAttributes());
                }
              }
            });
      }
      try {
        Thread.sleep(Long.MAX_VALUE);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  };

  public static final int STREAMS_PER_CHANNEL = 100;
  public static final int MAX_STEP_CONCURRENCY = 10_000;
  public static final int MAX_CHANNELS_LIMIT = MAX_STEP_CONCURRENCY / STREAMS_PER_CHANNEL;
  private static final Pattern STEP_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

  public static final class StepConfig {
    private final String name;
    private final LoadType loadType;
    private final Duration duration;
    private final int concurrency;
    private final int tps;
    private final Duration cycleDuration;
    private final double peakFactor;
    private final double burstFactor;
    private final double burstDuration;
    private final double burstFraction;
    private final ChannelPoolPhase phase;

    public StepConfig(
        String name,
        LoadType loadType,
        Duration duration,
        int concurrency,
        int tps,
        Duration cycleDuration,
        double peakFactor,
        double burstFactor,
        double burstDuration,
        double burstFraction,
        ChannelPoolPhase phase) {
      if (name == null || !STEP_NAME_PATTERN.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid step name: " + name);
      }
      if (loadType == null) {
        throw new IllegalArgumentException("Step loadType cannot be null for " + name);
      }
      if (duration == null || duration.isNegative()) {
        throw new IllegalArgumentException("Invalid duration for step " + name);
      }
      if (concurrency <= 0 || concurrency > MAX_STEP_CONCURRENCY) {
        throw new IllegalArgumentException(
            "Step concurrency for " + name + " must be between 1 and " + MAX_STEP_CONCURRENCY);
      }
      if (tps < 0) {
        throw new IllegalArgumentException("Step tps must be >= 0 for " + name);
      }
      if (peakFactor <= 0) {
        throw new IllegalArgumentException("peakFactor must be > 0 for step " + name);
      }
      if (burstFactor <= 0) {
        throw new IllegalArgumentException("burstFactor must be > 0 for step " + name);
      }
      if (burstDuration <= 0) {
        throw new IllegalArgumentException("burstDuration must be > 0 for step " + name);
      }
      if (burstFraction <= 0 || burstFraction >= 1.0) {
        throw new IllegalArgumentException(
            "burstFraction must be between 0 and 1 (exclusive) for step " + name);
      }
      this.name = name;
      this.loadType = loadType;
      this.duration = duration;
      this.concurrency = concurrency;
      this.tps = tps;
      this.cycleDuration = cycleDuration != null ? cycleDuration : Duration.ofHours(1);
      this.peakFactor = peakFactor;
      this.burstFactor = burstFactor;
      this.burstDuration = burstDuration;
      this.burstFraction = burstFraction;
      this.phase = phase;
    }

    public String getName() {
      return name;
    }

    public LoadType getLoadType() {
      return loadType;
    }

    public Duration getDuration() {
      return duration;
    }

    public int getConcurrency() {
      return concurrency;
    }

    public int getTps() {
      return tps;
    }

    public Duration getCycleDuration() {
      return cycleDuration;
    }

    public double getPeakFactor() {
      return peakFactor;
    }

    public double getBurstFactor() {
      return burstFactor;
    }

    public double getBurstDuration() {
      return burstDuration;
    }

    public double getBurstFraction() {
      return burstFraction;
    }

    public ChannelPoolPhase getPhase() {
      return phase;
    }

    public boolean isClosedLoopStep() {
      return loadType == LoadType.CLOSED_LOOP || tps <= 0;
    }

    public long computeIntervalNanos(long elapsedNanos) {
      if (isClosedLoopStep()) {
        return 0L;
      }
      double effectiveTps = tps;
      if (loadType == LoadType.GRADUAL) {
        long cycleNanos = Math.max(1L, cycleDuration.toNanos());
        double angle = 2.0 * Math.PI * (elapsedNanos % cycleNanos) / cycleNanos;
        double amplitude = tps * (peakFactor - 1.0);
        effectiveTps = Math.max(1.0, tps + amplitude * Math.cos(angle - Math.PI));
      } else if (loadType == LoadType.SPIKY) {
        double rBurst = tps * burstFactor;
        double rNormal =
            (burstFraction < 1.0)
                ? Math.max(0.0, (tps - burstFraction * rBurst) / (1.0 - burstFraction))
                : tps;
        double burstPeriodSec = Math.max(0.001, burstDuration / Math.max(0.001, burstFraction));
        double posSec = (elapsedNanos / 1_000_000_000.0) % burstPeriodSec;
        effectiveTps = Math.max(1.0, posSec < burstDuration ? rBurst : rNormal);
      }
      return Math.max(1L, Math.round(1_000_000_000.0 / effectiveTps));
    }
  }

  public static final class BenchmarkYamlConfig {
    public static final String DEFAULT_YAML = BenchmarkYamlLoader.DEFAULT_YAML;

    private final LoadType loadType;
    private final int minChannels;
    private final int maxChannels;
    private final boolean channelPoolConfigured;
    private final Duration postTestWaitDuration;
    private final long deltaMicros;
    private final boolean enforceGates;
    private final List<StepConfig> steps;
    private final Map<ChannelPoolPhase, Integer> phaseConcurrencies;
    private final Map<ChannelPoolPhase, Duration> phaseDurations;

    public BenchmarkYamlConfig(
        int minChannels,
        int maxChannels,
        boolean channelPoolConfigured,
        Duration postTestWaitDuration,
        long deltaMicros,
        boolean enforceGates,
        List<StepConfig> steps) {
      this(
          LoadType.SPIKY,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          minChannels,
          maxChannels,
          channelPoolConfigured,
          postTestWaitDuration,
          deltaMicros,
          enforceGates,
          steps);
    }

    @Deprecated
    public BenchmarkYamlConfig(
        LoadType loadType,
        Integer tps,
        Integer threads,
        String duration,
        Integer numRows,
        String cycleDuration,
        Double peakFactor,
        Double burstFactor,
        Double burstDuration,
        Double burstFraction,
        int minChannels,
        int maxChannels,
        boolean channelPoolConfigured,
        Duration postTestWaitDuration,
        long deltaMicros,
        boolean enforceGates,
        List<StepConfig> steps) {
      if (minChannels <= 0 || maxChannels < minChannels || maxChannels > MAX_CHANNELS_LIMIT) {
        throw new IllegalArgumentException(
            "Invalid channel bounds: min=" + minChannels + ", max=" + maxChannels);
      }
      if (deltaMicros < 0) {
        throw new IllegalArgumentException("delta-us must be >= 0");
      }
      this.loadType = loadType != null ? loadType : LoadType.SPIKY;
      this.minChannels = minChannels;
      this.maxChannels = maxChannels;
      this.channelPoolConfigured = channelPoolConfigured;
      this.postTestWaitDuration =
          postTestWaitDuration != null ? postTestWaitDuration : Duration.ofMinutes(5);
      this.deltaMicros = deltaMicros;
      this.enforceGates = enforceGates;
      this.steps = steps != null ? List.copyOf(steps) : Collections.emptyList();

      Map<ChannelPoolPhase, Integer> concurrencies = new EnumMap<>(ChannelPoolPhase.class);
      Map<ChannelPoolPhase, Duration> durations = new EnumMap<>(ChannelPoolPhase.class);
      for (StepConfig step : this.steps) {
        if (step.getPhase() != null) {
          concurrencies.put(step.getPhase(), step.getConcurrency());
          durations.put(step.getPhase(), step.getDuration());
        }
      }
      this.phaseConcurrencies = Collections.unmodifiableMap(concurrencies);
      this.phaseDurations = Collections.unmodifiableMap(durations);
      if (this.enforceGates && !hasStandardGatePhases()) {
        throw new IllegalArgumentException(
            "--enforce-gates requires steps covering warmup, spike, and recovery phases");
      }
    }

    public static GcpChannelPoolOptions buildChannelPoolOptions(int minChannels, int maxChannels) {
      return GcpChannelPoolOptions.newBuilder()
          .setMinSize(minChannels)
          .setMaxSize(maxChannels)
          .setInitSize(minChannels)
          .build();
    }

    public static BenchmarkYamlConfig fromLegacy(UnifiedProfileConfig legacy) {
      List<StepConfig> converted = new ArrayList<>();
      for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
        Integer cr = legacy.getPhaseConcurrencies().get(phase);
        Duration dur = legacy.getPhaseDurations().get(phase);
        if (cr != null && dur != null) {
          converted.add(
              new StepConfig(
                  phase.getMetricLabel(),
                  LoadType.SPIKY,
                  dur,
                  cr,
                  0,
                  Duration.ofHours(1),
                  2.0,
                  2.0,
                  1.0,
                  0.1,
                  phase));
        }
      }
      return new BenchmarkYamlConfig(
          legacy.getMinChannels(),
          legacy.getMaxChannels(),
          true,
          legacy.getPostTestWaitDuration(),
          legacy.getDeltaMicros(),
          legacy.isEnforceGates(),
          converted);
    }

    public static BenchmarkYamlConfig fromFileOrDefault(
        String pathOrName,
        LoadType cliLoadType,
        Integer minChannelsOverride,
        Integer maxChannelsOverride,
        Duration postTestWaitOverride,
        Long deltaMicrosOverride,
        Boolean enforceGatesOverride) {
      return BenchmarkYamlLoader.fromFileOrDefault(
          pathOrName,
          cliLoadType,
          minChannelsOverride,
          maxChannelsOverride,
          postTestWaitOverride,
          deltaMicrosOverride,
          enforceGatesOverride);
    }

    public static BenchmarkYamlConfig fromYaml(String yamlContent) {
      return fromYaml(yamlContent, null, null, null, null, null, null);
    }

    public static BenchmarkYamlConfig fromYaml(
        String yamlContent,
        LoadType cliLoadType,
        Integer minChannelsOverride,
        Integer maxChannelsOverride,
        Duration postTestWaitOverride,
        Long deltaMicrosOverride,
        Boolean enforceGatesOverride) {
      return BenchmarkYamlLoader.fromYaml(
          yamlContent,
          cliLoadType,
          minChannelsOverride,
          maxChannelsOverride,
          postTestWaitOverride,
          deltaMicrosOverride,
          enforceGatesOverride);
    }

    public LoadType getLoadType() {
      return loadType;
    }

    public int getMinChannels() {
      return minChannels;
    }

    public int getMaxChannels() {
      return maxChannels;
    }

    public boolean isChannelPoolConfigured() {
      return channelPoolConfigured;
    }

    public Duration getPostTestWaitDuration() {
      return postTestWaitDuration;
    }

    public long getDeltaMicros() {
      return deltaMicros;
    }

    public boolean isEnforceGates() {
      return enforceGates;
    }

    public List<StepConfig> getSteps() {
      return steps;
    }

    public int getMaxConcurrency() {
      return steps.stream().mapToInt(StepConfig::getConcurrency).max().orElse(1);
    }

    public Map<ChannelPoolPhase, Integer> getPhaseConcurrencies() {
      return phaseConcurrencies;
    }

    public Map<ChannelPoolPhase, Duration> getPhaseDurations() {
      return phaseDurations;
    }

    public boolean hasStandardGatePhases() {
      return phaseConcurrencies.containsKey(ChannelPoolPhase.WARMUP)
          && phaseConcurrencies.containsKey(ChannelPoolPhase.SPIKE_70)
          && phaseConcurrencies.containsKey(ChannelPoolPhase.RECOVERY_40);
    }
  }

  public static int parseStepConcurrency(String rawValue, int maxChannels, String stepName) {
    return BenchmarkYamlLoader.parseStepConcurrency(rawValue, maxChannels, stepName);
  }

  public static Duration parseStepDuration(String value, String key) {
    return BenchmarkYamlLoader.parseStepDuration(value, key);
  }

  public abstract void run(AbstractBenchmark benchmark, ExecutorService executor);

  public static LoadType fromString(String text) {
    if (text != null) {
      for (LoadType b : LoadType.values()) {
        if (text.equalsIgnoreCase(b.name()) || text.equalsIgnoreCase(b.name().replace("_", "-"))) {
          return b;
        }
      }
    }
    throw new IllegalArgumentException("No constant with text " + text + " found");
  }
}
