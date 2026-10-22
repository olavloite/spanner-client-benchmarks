package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.benchmark.PhaseLatencyRecorder.PhaseSummary;
import io.opentelemetry.api.common.Attributes;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class UnifiedProfileRunner {

  private final AbstractBenchmark benchmark;
  private final UnifiedProfileConfig config;
  private final Map<ChannelPoolPhase, PhaseLatencyRecorder> phaseRecorders =
      new EnumMap<>(ChannelPoolPhase.class);
  private final Map<ChannelPoolPhase, Attributes> phaseAttributes =
      new EnumMap<>(ChannelPoolPhase.class);
  private final ReentrantLock concurrencyLock = new ReentrantLock();
  private final Condition concurrencyChanged = concurrencyLock.newCondition();
  private final AtomicInteger targetConcurrency = new AtomicInteger(0);
  private volatile ChannelPoolPhase currentPhase = ChannelPoolPhase.WARMUP;
  private volatile boolean running = true;

  public UnifiedProfileRunner(AbstractBenchmark benchmark, UnifiedProfileConfig config) {
    this.benchmark = benchmark;
    this.config = config;
    for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
      phaseRecorders.put(phase, new PhaseLatencyRecorder());
      phaseAttributes.put(
          phase,
          benchmark.getAttributes().toBuilder()
              .put("phase", phase.getMetricLabel())
              .put("max_channels", config.getMaxChannels())
              .build());
    }
  }

  public ChannelPoolVerificationReport run() throws Exception {
    long preTestHeapBytes = recordHeapAfterGc();
    ResourceMonitor monitor =
        new ResourceMonitor(
            benchmark.resourceProbeInterval,
            benchmark.memoryUsageHistogram,
            benchmark.cpuUtilizationHistogram,
            benchmark.getAttributes());
    monitor.start();

    int maxPoolConcurrency = config.getMaxPoolConcurrency();
    ExecutorService executor = Executors.newFixedThreadPool(maxPoolConcurrency);
    try {
      for (int workerIndex = 0; workerIndex < maxPoolConcurrency; workerIndex++) {
        final int index = workerIndex;
        executor.submit(() -> runWorkerLoop(index));
      }

      for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
        runPhase(
            phase,
            config.getPhaseConcurrencies().get(phase),
            config.getPhaseDurations().get(phase));
      }

      updatePhaseAndConcurrency(ChannelPoolPhase.RECOVERY_40, 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      running = false;
      wakeAllWorkers();
      monitor.stop();
      executor.shutdownNow();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    Map<ChannelPoolPhase, PhaseSummary> summaries = new EnumMap<>(ChannelPoolPhase.class);
    for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
      summaries.put(
          phase,
          phaseRecorders.get(phase).toSummary(phase, config.getPhaseConcurrencies().get(phase)));
    }

    Duration postTestWait = config.getPostTestWaitDuration();
    if (!Thread.currentThread().isInterrupted()
        && postTestWait != null
        && postTestWait.toMillis() > 0) {
      Thread.sleep(postTestWait.toMillis());
    }

    long postTestHeapBytes = recordHeapAfterGc();
    ChannelPoolVerificationReport report =
        new ChannelPoolVerificationReport(
            summaries, config.getDeltaMicros(), preTestHeapBytes, postTestHeapBytes);
    benchmark.setLastVerificationReport(report);
    printVerificationReport(report);

    if (config.isEnforceGates() && !report.isAllGatesPassed()) {
      throw new IllegalStateException(
          "Channel pool verification gates failed: spikeLatencyPassed="
              + report.isSpikeLatencyPassed()
              + ", spikeErrorRatePassed="
              + report.isSpikeErrorRatePassed()
              + ", recoveryLatencyPassed="
              + report.isRecoveryLatencyPassed()
              + ", recoveryErrorRatePassed="
              + report.isRecoveryErrorRatePassed()
              + ", memoryLeakGatePassed="
              + report.isMemoryLeakGatePassed());
    }
    return report;
  }

  private void runPhase(ChannelPoolPhase phase, int concurrency, Duration phaseDuration)
      throws InterruptedException {
    if (phaseDuration == null || phaseDuration.toMillis() <= 0) {
      return;
    }
    System.out.printf(
        "Entering phase %s with %d concurrent requests for %s%n",
        phase.getMetricLabel(), concurrency, phaseDuration);
    updatePhaseAndConcurrency(phase, concurrency);
    Thread.sleep(phaseDuration.toMillis());
  }

  private void updatePhaseAndConcurrency(ChannelPoolPhase phase, int concurrency) {
    concurrencyLock.lock();
    try {
      currentPhase = phase;
      targetConcurrency.set(concurrency);
      concurrencyChanged.signalAll();
    } finally {
      concurrencyLock.unlock();
    }
  }

  private void wakeAllWorkers() {
    concurrencyLock.lock();
    try {
      concurrencyChanged.signalAll();
    } finally {
      concurrencyLock.unlock();
    }
  }

  private void runWorkerLoop(int workerIndex) {
    while (running && !Thread.currentThread().isInterrupted()) {
      if (workerIndex >= targetConcurrency.get()) {
        concurrencyLock.lock();
        try {
          while (running
              && !Thread.currentThread().isInterrupted()
              && workerIndex >= targetConcurrency.get()) {
            concurrencyChanged.await();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } finally {
          concurrencyLock.unlock();
        }
      }
      if (!running || Thread.currentThread().isInterrupted()) {
        return;
      }
      ChannelPoolPhase activePhase = currentPhase;
      Attributes attrs = phaseAttributes.get(activePhase);
      long startTime = System.nanoTime();
      boolean failed = false;
      try {
        benchmark.executeOperation();
      } catch (Exception e) {
        if (!running || Thread.currentThread().isInterrupted()) {
          return;
        }
        if (AbstractBenchmark.isCancellationOrInterruption(e)) {
          phaseRecorders.get(activePhase).recordCancelled();
          continue;
        }
        failed = true;
        benchmark.errorCounter.add(1, attrs);
      }
      long latencyUs = (System.nanoTime() - startTime) / 1000;
      if (!failed && benchmark.shouldMeasureEntireMethod()) {
        benchmark.latencyHistogram.record(latencyUs, attrs);
      }
      benchmark.operationCounter.add(1, attrs);
      phaseRecorders.get(activePhase).recordOperation(latencyUs, failed);
    }
  }

  static long recordHeapAfterGc() {
    System.gc();
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private void printVerificationReport(ChannelPoolVerificationReport report) {
    Map<ChannelPoolPhase, Long> cancelled = new EnumMap<>(ChannelPoolPhase.class);
    for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
      PhaseLatencyRecorder recorder = phaseRecorders.get(phase);
      cancelled.put(phase, recorder != null ? recorder.getCancelledCount() : 0L);
    }
    System.out.print(report.formatSummary(cancelled));
  }
}
