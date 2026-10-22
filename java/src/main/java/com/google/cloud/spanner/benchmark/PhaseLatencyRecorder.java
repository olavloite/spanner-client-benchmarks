package com.google.cloud.spanner.benchmark;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class PhaseLatencyRecorder {

  // Tier 0: 0..10,000 us (10 ms) at 1 us resolution -> indices 0..10,000 (10,001 buckets)
  private static final long TIER0_MAX_US = 10_000L;
  private static final int TIER0_BUCKETS = 10_001;

  // Tier 1: 10,001..100,000 us (100 ms) at 100 us resolution -> indices 10,001..10,900 (900
  // buckets)
  private static final long TIER1_MAX_US = 100_000L;
  private static final long TIER1_STEP_US = 100L;
  private static final int TIER1_OFFSET = TIER0_BUCKETS;
  private static final int TIER1_BUCKETS = 900;

  // Tier 2: 100,001..1,000,000 us (1 s) at 1,000 us resolution -> indices 10,901..11,800 (900
  // buckets)
  private static final long TIER2_MAX_US = 1_000_000L;
  private static final long TIER2_STEP_US = 1_000L;
  private static final int TIER2_OFFSET = TIER1_OFFSET + TIER1_BUCKETS;
  private static final int TIER2_BUCKETS = 900;

  // Tier 3: 1,000,001..60,000,000 us (60 s) at 100,000 us resolution -> indices 11,801..12,390 (590
  // buckets)
  private static final long TIER3_MAX_US = 60_000_000L;
  private static final long TIER3_STEP_US = 100_000L;
  private static final int TIER3_OFFSET = TIER2_OFFSET + TIER2_BUCKETS;
  private static final int TIER3_BUCKETS = 590;

  // Overflow bucket: > 60,000,000 us -> index 12,391
  private static final int OVERFLOW_INDEX = TIER3_OFFSET + TIER3_BUCKETS;
  public static final int TOTAL_BUCKETS = OVERFLOW_INDEX + 1;

  private final AtomicLongArray buckets = new AtomicLongArray(TOTAL_BUCKETS);
  private final AtomicLong totalOperations = new AtomicLong();
  private final AtomicLong successfulOperations = new AtomicLong();
  private final AtomicLong errors = new AtomicLong();
  private final AtomicLong cancelledOperations = new AtomicLong();

  public void recordOperation(long latencyMicros, boolean failed) {
    totalOperations.incrementAndGet();
    if (failed) {
      errors.incrementAndGet();
      return;
    }
    successfulOperations.incrementAndGet();
    int bucketIndex = toBucketIndex(Math.max(0L, latencyMicros));
    buckets.incrementAndGet(bucketIndex);
  }

  public void recordCancelled() {
    cancelledOperations.incrementAndGet();
  }

  public long getCancelledCount() {
    return cancelledOperations.get();
  }

  static int toBucketIndex(long latencyMicros) {
    if (latencyMicros <= 0L) {
      return 0;
    }
    if (latencyMicros <= TIER0_MAX_US) {
      return (int) latencyMicros;
    }
    if (latencyMicros <= TIER1_MAX_US) {
      return TIER1_OFFSET + (int) ((latencyMicros - TIER0_MAX_US - 1L) / TIER1_STEP_US);
    }
    if (latencyMicros <= TIER2_MAX_US) {
      return TIER2_OFFSET + (int) ((latencyMicros - TIER1_MAX_US - 1L) / TIER2_STEP_US);
    }
    if (latencyMicros <= TIER3_MAX_US) {
      return TIER3_OFFSET + (int) ((latencyMicros - TIER2_MAX_US - 1L) / TIER3_STEP_US);
    }
    return OVERFLOW_INDEX;
  }

  static long bucketUpperBoundMicros(int index) {
    if (index < TIER0_BUCKETS) {
      return index;
    }
    if (index < TIER2_OFFSET) {
      return TIER0_MAX_US + (long) (index - TIER1_OFFSET + 1) * TIER1_STEP_US;
    }
    if (index < TIER3_OFFSET) {
      return TIER1_MAX_US + (long) (index - TIER2_OFFSET + 1) * TIER2_STEP_US;
    }
    if (index < OVERFLOW_INDEX) {
      return TIER2_MAX_US + (long) (index - TIER3_OFFSET + 1) * TIER3_STEP_US;
    }
    return TIER3_MAX_US;
  }

  public long getPercentile(int percentile) {
    long successCount = successfulOperations.get();
    if (successCount <= 0L) {
      return 0L;
    }
    long targetRank = Math.max(1L, (long) Math.ceil((percentile / 100.0) * successCount));
    long cumulative = 0L;
    for (int i = 0; i < TOTAL_BUCKETS; i++) {
      cumulative += buckets.get(i);
      if (cumulative >= targetRank) {
        return bucketUpperBoundMicros(i);
      }
    }
    return TIER3_MAX_US;
  }

  public int getBucketArrayLength() {
    return buckets.length();
  }

  public PhaseSummary toSummary(ChannelPoolPhase phase, int concurrency) {
    return new PhaseSummary(
        phase,
        concurrency,
        totalOperations.get(),
        errors.get(),
        getPercentile(50),
        getPercentile(90),
        getPercentile(99));
  }

  public static final class PhaseSummary {
    private final ChannelPoolPhase phase;
    private final int concurrency;
    private final long operationCount;
    private final long errorCount;
    private final long p50Micros;
    private final long p90Micros;
    private final long p99Micros;

    public PhaseSummary(
        ChannelPoolPhase phase,
        int concurrency,
        long operationCount,
        long errorCount,
        long p50Micros,
        long p90Micros,
        long p99Micros) {
      this.phase = phase;
      this.concurrency = concurrency;
      this.operationCount = operationCount;
      this.errorCount = errorCount;
      this.p50Micros = p50Micros;
      this.p90Micros = p90Micros;
      this.p99Micros = p99Micros;
    }

    public ChannelPoolPhase getPhase() {
      return phase;
    }

    public int getConcurrency() {
      return concurrency;
    }

    public long getOperationCount() {
      return operationCount;
    }

    public long getErrorCount() {
      return errorCount;
    }

    public long getP50Micros() {
      return p50Micros;
    }

    public long getP90Micros() {
      return p90Micros;
    }

    public long getP99Micros() {
      return p99Micros;
    }
  }
}
