package com.google.cloud.spanner.benchmark;

import com.google.cloud.grpc.GcpManagedChannelOptions.GcpChannelPoolOptions;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.IntStream;

public final class UnifiedProfileConfig {

  public static final int STREAMS_PER_CHANNEL = 100;

  private final int minChannels;
  private final int maxChannels;
  private final int maxPoolConcurrency;
  private final Map<ChannelPoolPhase, Integer> phaseConcurrencies;
  private final Map<ChannelPoolPhase, Duration> phaseDurations;
  private final Duration postTestWaitDuration;
  private final long deltaMicros;
  private final boolean enforceGates;

  public UnifiedProfileConfig(
      int minChannels,
      int maxChannels,
      int warmupConcurrency,
      int spikeConcurrency,
      int saturationConcurrency,
      int recoveryConcurrency,
      Duration warmupDuration,
      Duration spikeDuration,
      Duration saturationDuration,
      Duration recoveryDuration,
      Duration postTestWaitDuration,
      long deltaMicros,
      boolean enforceGates) {
    if (minChannels <= 0) {
      throw new IllegalArgumentException("min-channels must be > 0");
    }
    if (maxChannels < minChannels) {
      throw new IllegalArgumentException("max-channels must be >= min-channels");
    }
    if (deltaMicros < 0) {
      throw new IllegalArgumentException("delta-us must be >= 0");
    }
    validateConcurrency(warmupConcurrency, "warmup-cr");
    validateConcurrency(spikeConcurrency, "spike-cr");
    validateConcurrency(saturationConcurrency, "saturation-cr");
    validateConcurrency(recoveryConcurrency, "recovery-cr");

    this.minChannels = minChannels;
    this.maxChannels = maxChannels;
    this.maxPoolConcurrency =
        IntStream.of(
                warmupConcurrency, spikeConcurrency, saturationConcurrency, recoveryConcurrency)
            .max()
            .orElse(saturationConcurrency);

    Map<ChannelPoolPhase, Integer> concurrencies = new EnumMap<>(ChannelPoolPhase.class);
    concurrencies.put(ChannelPoolPhase.WARMUP, warmupConcurrency);
    concurrencies.put(ChannelPoolPhase.SPIKE_70, spikeConcurrency);
    concurrencies.put(ChannelPoolPhase.SATURATION_100, saturationConcurrency);
    concurrencies.put(ChannelPoolPhase.RECOVERY_40, recoveryConcurrency);
    this.phaseConcurrencies = Collections.unmodifiableMap(concurrencies);

    Map<ChannelPoolPhase, Duration> durations = new EnumMap<>(ChannelPoolPhase.class);
    durations.put(ChannelPoolPhase.WARMUP, warmupDuration);
    durations.put(ChannelPoolPhase.SPIKE_70, spikeDuration);
    durations.put(ChannelPoolPhase.SATURATION_100, saturationDuration);
    durations.put(ChannelPoolPhase.RECOVERY_40, recoveryDuration);
    this.phaseDurations = Collections.unmodifiableMap(durations);

    this.postTestWaitDuration = postTestWaitDuration;
    this.deltaMicros = deltaMicros;
    this.enforceGates = enforceGates;
  }

  public static UnifiedProfileConfig defaults() {
    return new UnifiedProfileConfig(
        1,
        3,
        defaultWarmupConcurrency(3),
        defaultSpikeConcurrency(3),
        defaultSaturationConcurrency(3),
        defaultRecoveryConcurrency(3),
        Duration.ofMinutes(5),
        Duration.ofMinutes(10),
        Duration.ofMinutes(5),
        Duration.ofMinutes(15),
        Duration.ofMinutes(5),
        1000L,
        false);
  }

  public static int defaultWarmupConcurrency(int maxChannels) {
    return Math.max(1, (int) Math.round(maxChannels * STREAMS_PER_CHANNEL * (50.0 / 300.0)));
  }

  public static int defaultSpikeConcurrency(int maxChannels) {
    return Math.max(1, (int) Math.round(maxChannels * STREAMS_PER_CHANNEL * 0.70));
  }

  public static int defaultSaturationConcurrency(int maxChannels) {
    return Math.max(1, maxChannels * STREAMS_PER_CHANNEL);
  }

  public static int defaultRecoveryConcurrency(int maxChannels) {
    return Math.max(1, (int) Math.round(maxChannels * STREAMS_PER_CHANNEL * 0.40));
  }

  public static void validateConcurrency(Integer cr, String optionName) {
    if (cr != null && cr <= 0) {
      throw new IllegalArgumentException(optionName + " must be > 0");
    }
  }

  public static Duration parsePhaseDuration(String durationStr, String optionName) {
    if (durationStr == null
        || durationStr.isEmpty()
        || "inf".equalsIgnoreCase(durationStr)
        || "infinite".equalsIgnoreCase(durationStr)) {
      throw new IllegalArgumentException(
          "Invalid " + optionName + ": must be a finite duration (e.g. 0s, 5m)");
    }
    if ("0".equals(durationStr) || "0s".equalsIgnoreCase(durationStr)) {
      return Duration.ZERO;
    }
    try {
      Duration parsed = AbstractBenchmark.parseDuration(durationStr);
      if (parsed == null || parsed.isNegative()) {
        throw new IllegalArgumentException("Invalid " + optionName + ": " + durationStr);
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid " + optionName + ": " + durationStr, e);
    }
  }

  public static GcpChannelPoolOptions buildChannelPoolOptions(int minChannels, int maxChannels) {
    return GcpChannelPoolOptions.newBuilder()
        .setMinSize(minChannels)
        .setInitSize(minChannels)
        .setMaxSize(maxChannels)
        .build();
  }

  public int getMinChannels() {
    return minChannels;
  }

  public int getMaxChannels() {
    return maxChannels;
  }

  public int getMaxPoolConcurrency() {
    return maxPoolConcurrency;
  }

  public Map<ChannelPoolPhase, Integer> getPhaseConcurrencies() {
    return phaseConcurrencies;
  }

  public Map<ChannelPoolPhase, Duration> getPhaseDurations() {
    return phaseDurations;
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
}
