package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.benchmark.PhaseLatencyRecorder.PhaseSummary;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class ChannelPoolVerificationReport {

  public static final double DEFAULT_HEAP_TOLERANCE_RATIO = 1.05;

  private final Map<ChannelPoolPhase, PhaseSummary> phaseSummaries;
  private final long deltaMicros;
  private final long preTestHeapBytes;
  private final long postTestHeapBytes;

  public ChannelPoolVerificationReport(
      Map<ChannelPoolPhase, PhaseSummary> phaseSummaries,
      long deltaMicros,
      long preTestHeapBytes,
      long postTestHeapBytes) {
    this.phaseSummaries = Collections.unmodifiableMap(new EnumMap<>(phaseSummaries));
    this.deltaMicros = deltaMicros;
    this.preTestHeapBytes = preTestHeapBytes;
    this.postTestHeapBytes = postTestHeapBytes;
  }

  public Map<ChannelPoolPhase, PhaseSummary> getPhaseSummaries() {
    return phaseSummaries;
  }

  public long getDeltaMicros() {
    return deltaMicros;
  }

  public long getMaxAllowedHeapBytes() {
    return Math.round(preTestHeapBytes * DEFAULT_HEAP_TOLERANCE_RATIO);
  }

  public long getBaselineP90Micros() {
    PhaseSummary warmup = phaseSummaries.get(ChannelPoolPhase.WARMUP);
    return warmup != null ? warmup.getP90Micros() : 0L;
  }

  public boolean isSpikeLatencyPassed() {
    PhaseSummary warmup = phaseSummaries.get(ChannelPoolPhase.WARMUP);
    PhaseSummary spike = phaseSummaries.get(ChannelPoolPhase.SPIKE_70);
    if (warmup == null
        || spike == null
        || warmup.getOperationCount() == 0
        || spike.getOperationCount() == 0) {
      return false;
    }
    return spike.getP90Micros() <= warmup.getP90Micros() + deltaMicros;
  }

  public boolean isThunderingHerdDetected() {
    PhaseSummary spike = phaseSummaries.get(ChannelPoolPhase.SPIKE_70);
    long baseline = getBaselineP90Micros();
    if (spike == null || baseline <= 0) {
      return false;
    }
    return spike.getP90Micros() >= 2 * baseline;
  }

  public boolean isSpikeErrorRatePassed() {
    PhaseSummary spike = phaseSummaries.get(ChannelPoolPhase.SPIKE_70);
    return spike != null && spike.getOperationCount() > 0 && spike.getErrorCount() == 0;
  }

  public boolean isRecoveryLatencyPassed() {
    PhaseSummary warmup = phaseSummaries.get(ChannelPoolPhase.WARMUP);
    PhaseSummary recovery = phaseSummaries.get(ChannelPoolPhase.RECOVERY_40);
    if (warmup == null
        || recovery == null
        || warmup.getOperationCount() == 0
        || recovery.getOperationCount() == 0) {
      return false;
    }
    return recovery.getP90Micros() <= warmup.getP90Micros() + deltaMicros;
  }

  public boolean isRecoveryErrorRatePassed() {
    PhaseSummary recovery = phaseSummaries.get(ChannelPoolPhase.RECOVERY_40);
    return recovery != null && recovery.getOperationCount() > 0 && recovery.getErrorCount() == 0;
  }

  public boolean isMemoryLeakGatePassed() {
    if (preTestHeapBytes <= 0) {
      return true;
    }
    return postTestHeapBytes <= getMaxAllowedHeapBytes();
  }

  public long getPreTestHeapBytes() {
    return preTestHeapBytes;
  }

  public long getPostTestHeapBytes() {
    return postTestHeapBytes;
  }

  public boolean isAllGatesPassed() {
    return isSpikeLatencyPassed()
        && isSpikeErrorRatePassed()
        && isRecoveryLatencyPassed()
        && isRecoveryErrorRatePassed()
        && isMemoryLeakGatePassed();
  }

  public String formatSummary() {
    return formatSummary(Collections.emptyMap());
  }

  public String formatSummary(Map<ChannelPoolPhase, Long> cancelledCounts) {
    StringBuilder sb = new StringBuilder();
    sb.append("=== Channel Pool Unified Benchmark Verification Report ===\n");
    for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
      PhaseSummary summary = phaseSummaries.get(phase);
      if (summary != null) {
        long cancelled = cancelledCounts != null ? cancelledCounts.getOrDefault(phase, 0L) : 0L;
        sb.append(
            String.format(
                Locale.ROOT,
                "Phase %-15s (CR=%3d): ops=%d, errors=%d, cancelled=%d, P50=%d us, P90=%d us, P99=%d us\n",
                phase.getMetricLabel(),
                summary.getConcurrency(),
                summary.getOperationCount(),
                summary.getErrorCount(),
                cancelled,
                summary.getP50Micros(),
                summary.getP90Micros(),
                summary.getP99Micros()));
      }
    }
    sb.append(
        String.format(
            Locale.ROOT,
            "Heap Usage: pre-test=%d bytes, post-test=%d bytes (Leak Gate Passed: %s)\n",
            preTestHeapBytes,
            postTestHeapBytes,
            isMemoryLeakGatePassed()));

    long baselineT = getBaselineP90Micros();
    long threshold = baselineT + deltaMicros;
    long doubleT = 2 * baselineT;
    PhaseSummary spike = phaseSummaries.get(ChannelPoolPhase.SPIKE_70);
    long spikeP90 = spike != null ? spike.getP90Micros() : 0L;
    long spikeErrors = spike != null ? spike.getErrorCount() : 0L;
    PhaseSummary recovery = phaseSummaries.get(ChannelPoolPhase.RECOVERY_40);
    long recP90 = recovery != null ? recovery.getP90Micros() : 0L;
    long recErrors = recovery != null ? recovery.getErrorCount() : 0L;
    long limitHeap = getMaxAllowedHeapBytes();

    sb.append(
        String.format(
            Locale.ROOT,
            "--- Verification Gates (Baseline P90 T = %d us, Delta = %d us, Threshold = %d us) ---\n",
            baselineT,
            deltaMicros,
            threshold));
    sb.append(
        String.format(
            Locale.ROOT,
            "Spike (70%%) Latency Gate (P90 <= T + delta): %s (actual=%d us, limit=%d us)\n",
            isSpikeLatencyPassed() ? "PASS" : "FAIL",
            spikeP90,
            threshold));
    sb.append(
        String.format(
            Locale.ROOT,
            "Spike (70%%) Thundering Herd Check (P90 < 2*T): %s (actual=%d us, 2*T=%d us)\n",
            isThunderingHerdDetected() ? "DETECTED" : "PASS",
            spikeP90,
            doubleT));
    sb.append(
        String.format(
            Locale.ROOT,
            "Spike (70%%) Error Rate Gate (errors == 0): %s (errors=%d)\n",
            isSpikeErrorRatePassed() ? "PASS" : "FAIL",
            spikeErrors));
    sb.append(
        String.format(
            Locale.ROOT,
            "Recovery (40%%) Latency Gate (P90 <= T + delta): %s (actual=%d us, limit=%d us)\n",
            isRecoveryLatencyPassed() ? "PASS" : "FAIL",
            recP90,
            threshold));
    sb.append(
        String.format(
            Locale.ROOT,
            "Recovery (40%%) Error Rate Gate (errors == 0): %s (errors=%d)\n",
            isRecoveryErrorRatePassed() ? "PASS" : "FAIL",
            recErrors));
    sb.append(
        String.format(
            Locale.ROOT,
            "Post-Test Memory Leak Gate (heap <= 1.05x baseline): %s (pre-test=%d bytes, post-test=%d bytes, limit=%d bytes)\n",
            isMemoryLeakGatePassed() ? "PASS" : "FAIL",
            preTestHeapBytes,
            postTestHeapBytes,
            limitHeap));
    sb.append(
        String.format(
            Locale.ROOT,
            "Overall Verification Verdict: %s\n",
            isAllGatesPassed() ? "PASS" : "FAIL"));

    return sb.toString();
  }
}
