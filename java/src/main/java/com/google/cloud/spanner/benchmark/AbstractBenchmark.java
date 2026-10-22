package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.benchmark.LoadType.BenchmarkYamlConfig;
import com.google.cloud.spanner.benchmark.LoadType.StepConfig;
import com.google.cloud.spanner.benchmark.PhaseLatencyRecorder.PhaseSummary;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.lang.management.ManagementFactory;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nonnull;

public abstract class AbstractBenchmark {

  protected final DatabaseClient client;
  protected final LongHistogram latencyHistogram;
  protected final LongCounter operationCounter;
  protected final LongCounter errorCounter;
  protected final String tableName;
  protected final long minId;
  protected final long maxId;
  protected final double tps;
  protected final int threads;
  protected final Duration duration;
  protected final boolean forAlerting;
  protected final double burstFactor;
  protected final double burstDuration;
  protected final double burstFraction;
  protected final LoadType loadType;
  protected final Duration cycleDuration;
  protected final double peakFactor;
  private final Attributes attributes; // Pre-created attributes

  protected final LongHistogram memoryUsageHistogram;
  protected final DoubleHistogram cpuUtilizationHistogram;
  protected final String resourceProbeInterval;
  private ResourceMonitor resourceMonitor;
  private SocketChannel socketChannel = null;
  private UnifiedProfileConfig unifiedProfileConfig;
  private volatile ChannelPoolVerificationReport lastVerificationReport;

  public AbstractBenchmark(
      DatabaseClient client,
      LongHistogram latencyHistogram,
      LongCounter operationCounter,
      LongCounter errorCounter,
      LongHistogram memoryUsageHistogram,
      DoubleHistogram cpuUtilizationHistogram,
      String resourceProbeInterval,
      String tableName,
      long minId,
      long maxId,
      double tps,
      int threads,
      Duration duration,
      boolean forAlerting,
      String benchmarkName,
      LoadType loadType,
      Duration cycleDuration,
      double peakFactor,
      double burstFactor,
      double burstDuration,
      double burstFraction,
      boolean isMock) {
    this.client = client;
    this.latencyHistogram = latencyHistogram;
    this.operationCounter = operationCounter;
    this.errorCounter = errorCounter;
    this.memoryUsageHistogram = memoryUsageHistogram;
    this.cpuUtilizationHistogram = cpuUtilizationHistogram;
    this.resourceProbeInterval = resourceProbeInterval;
    this.tableName = tableName;
    this.minId = minId;
    this.maxId = maxId;
    this.tps = tps;
    this.threads = threads;
    this.duration = duration;
    this.forAlerting = forAlerting;
    this.loadType = loadType != null ? loadType : LoadType.STEADY;
    this.cycleDuration = cycleDuration;
    this.peakFactor = peakFactor;
    this.burstFactor = burstFactor;
    this.burstDuration = burstDuration;
    this.burstFraction = burstFraction;
    String benchmarkTypeAttr = getBenchmarkType();
    if (isMock) {
      benchmarkTypeAttr = benchmarkTypeAttr + "-mock";
    }
    // Pre-create attributes to avoid object creation overhead in the hot path
    this.attributes =
        Attributes.builder()
            .put("benchmark_type", benchmarkTypeAttr)
            .put("tps", tps)
            .put("for_alerting", forAlerting)
            .put("benchmark_name", benchmarkName != null ? benchmarkName : "")
            .put("client", "java-client")
            .put("load_type", this.loadType.name().toLowerCase())
            .put("burst_factor", burstFactor)
            .put("burst_duration", burstDuration)
            .put("burst_fraction", burstFraction)
            .put("cycle_duration_ms", cycleDuration != null ? cycleDuration.toMillis() : 0)
            .put("peak_factor", peakFactor)
            .put("transaction_type", "none")
            .build();
  }

  protected boolean shouldMeasureEntireMethod() {
    return true;
  }

  @Nonnull
  protected Attributes getAttributes() {
    return this.attributes;
  }

  public void setUnifiedProfileConfig(UnifiedProfileConfig unifiedProfileConfig) {
    this.unifiedProfileConfig = unifiedProfileConfig;
  }

  public UnifiedProfileConfig getUnifiedProfileConfig() {
    return this.unifiedProfileConfig;
  }

  public void setLastVerificationReport(ChannelPoolVerificationReport report) {
    this.lastVerificationReport = report;
  }

  public ChannelPoolVerificationReport getLastVerificationReport() {
    return this.lastVerificationReport;
  }

  private BenchmarkYamlConfig yamlConfig;

  public void setYamlConfig(BenchmarkYamlConfig yamlConfig) {
    this.yamlConfig = yamlConfig;
  }

  public BenchmarkYamlConfig getYamlConfig() {
    return yamlConfig;
  }

  private static final class ActiveStepContext {
    final StepConfig step;
    final PhaseLatencyRecorder recorder;
    final Attributes attributes;
    final long stepStartNanos;
    final AtomicLong nextScheduledNanos;

    ActiveStepContext(
        StepConfig step,
        PhaseLatencyRecorder recorder,
        Attributes attributes,
        long stepStartNanos) {
      this.step = step;
      this.recorder = recorder;
      this.attributes = attributes;
      this.stepStartNanos = stepStartNanos;
      this.nextScheduledNanos = new AtomicLong(stepStartNanos);
    }
  }

  public ChannelPoolVerificationReport runConfiguredSteps(BenchmarkYamlConfig config)
      throws Exception {
    Map<ChannelPoolPhase, PhaseLatencyRecorder> phaseRecorders =
        new EnumMap<>(ChannelPoolPhase.class);
    for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
      phaseRecorders.put(phase, new PhaseLatencyRecorder());
    }
    List<ActiveStepContext> stepContexts = new ArrayList<>(config.getSteps().size());
    for (StepConfig step : config.getSteps()) {
      PhaseLatencyRecorder recorder =
          step.getPhase() != null
              ? phaseRecorders.get(step.getPhase())
              : new PhaseLatencyRecorder();
      Attributes attrs =
          getAttributes().toBuilder()
              .put("load_type", step.getLoadType().name().toLowerCase(Locale.ROOT))
              .put("phase", step.getName())
              .put("max_channels", config.getMaxChannels())
              .build();
      stepContexts.add(new ActiveStepContext(step, recorder, attrs, System.nanoTime()));
    }

    startResourceMonitoring();
    long baselineUsedHeapBytes = recordHeapAfterGc();
    int maxConcurrency = config.getMaxConcurrency();
    ExecutorService executor = Executors.newFixedThreadPool(maxConcurrency);
    ReentrantLock lock = new ReentrantLock();
    Condition changed = lock.newCondition();
    AtomicInteger targetConcurrency = new AtomicInteger(0);
    final AtomicReference<ActiveStepContext> activeHolder =
        new AtomicReference<>(stepContexts.get(0));
    final AtomicBoolean runningFlag = new AtomicBoolean(true);

    try {
      for (int i = 0; i < maxConcurrency; i++) {
        final int workerIndex = i;
        executor.submit(
            () ->
                runStepWorkerLoop(
                    workerIndex, lock, changed, targetConcurrency, activeHolder, runningFlag));
      }
      for (ActiveStepContext ctx : stepContexts) {
        Duration d = ctx.step.getDuration();
        if (d == null || d.toMillis() <= 0) {
          continue;
        }
        System.out.printf(
            "Entering step %s (loadType=%s, concurrency=%d, tps=%d) for %s%n",
            ctx.step.getName(),
            ctx.step.getLoadType(),
            ctx.step.getConcurrency(),
            ctx.step.getTps(),
            d);
        ActiveStepContext liveCtx =
            new ActiveStepContext(ctx.step, ctx.recorder, ctx.attributes, System.nanoTime());
        lock.lock();
        try {
          activeHolder.set(liveCtx);
          targetConcurrency.set(ctx.step.getConcurrency());
          changed.signalAll();
        } finally {
          lock.unlock();
        }
        Thread.sleep(d.toMillis());
      }
      lock.lock();
      try {
        targetConcurrency.set(0);
        changed.signalAll();
      } finally {
        lock.unlock();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (resourceMonitor != null) {
        resourceMonitor.stop();
      }
      lock.lock();
      try {
        runningFlag.set(false);
        targetConcurrency.set(0);
        changed.signalAll();
      } finally {
        lock.unlock();
      }
      executor.shutdownNow();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    Map<ChannelPoolPhase, PhaseSummary> summaries = new EnumMap<>(ChannelPoolPhase.class);
    for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
      int cr = config.getPhaseConcurrencies().getOrDefault(phase, 0);
      summaries.put(phase, phaseRecorders.get(phase).toSummary(phase, cr));
    }
    Duration wait = config.getPostTestWaitDuration();
    if (wait != null && !wait.isZero() && !wait.isNegative()) {
      Thread.sleep(wait.toMillis());
    }
    long postGcUsedHeapBytes = recordHeapAfterGc();
    ChannelPoolVerificationReport report =
        new ChannelPoolVerificationReport(
            summaries, config.getDeltaMicros(), baselineUsedHeapBytes, postGcUsedHeapBytes);
    this.lastVerificationReport = report;
    printMultiStepReport(config, stepContexts, phaseRecorders, report);
    if (config.isEnforceGates() && !report.isAllGatesPassed()) {
      throw new IllegalStateException("Benchmark verification gates failed");
    }
    return report;
  }

  private void runStepWorkerLoop(
      int workerIndex,
      ReentrantLock lock,
      Condition changed,
      AtomicInteger targetConcurrency,
      AtomicReference<ActiveStepContext> activeHolder,
      AtomicBoolean runningFlag) {
    while (runningFlag.get() && !Thread.currentThread().isInterrupted()) {
      if (workerIndex >= targetConcurrency.get()) {
        lock.lock();
        try {
          while (runningFlag.get() && workerIndex >= targetConcurrency.get()) {
            changed.await();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } finally {
          lock.unlock();
        }
      }
      if (!runningFlag.get() || Thread.currentThread().isInterrupted()) {
        return;
      }
      ActiveStepContext ctx = activeHolder.get();
      if (!ctx.step.isClosedLoopStep()) {
        long now = System.nanoTime();
        long intervalNanos = ctx.step.computeIntervalNanos(Math.max(0L, now - ctx.stepStartNanos));
        long scheduled = ctx.nextScheduledNanos.getAndAdd(intervalNanos);
        if (scheduled < now - intervalNanos) {
          ctx.nextScheduledNanos.set(now);
          scheduled = now;
        }
        long sleepNanos = scheduled - now;
        if (sleepNanos > 0) {
          try {
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
      long startTime = System.nanoTime();
      boolean failed = false;
      try {
        executeOperation();
      } catch (Exception e) {
        if (!runningFlag.get() || Thread.currentThread().isInterrupted()) {
          return;
        }
        if (isCancellationOrInterruption(e)) {
          ctx.recorder.recordCancelled();
          continue;
        }
        failed = true;
        errorCounter.add(1, ctx.attributes);
      }
      long latencyUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startTime);
      if (!failed && shouldMeasureEntireMethod()) {
        latencyHistogram.record(latencyUs, ctx.attributes);
      }
      operationCounter.add(1, ctx.attributes);
      ctx.recorder.recordOperation(latencyUs, failed);
    }
  }

  private static long recordHeapAfterGc() {
    System.gc();
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static void printMultiStepReport(
      BenchmarkYamlConfig config,
      List<ActiveStepContext> stepContexts,
      Map<ChannelPoolPhase, PhaseLatencyRecorder> phaseRecorders,
      ChannelPoolVerificationReport report) {
    if (!config.hasStandardGatePhases()) {
      StringBuilder sb = new StringBuilder("=== Multi-Step Benchmark Summary ===\n");
      for (ActiveStepContext ctx : stepContexts) {
        PhaseSummary s = ctx.recorder.toSummary(ctx.step.getPhase(), ctx.step.getConcurrency());
        sb.append(
            String.format(
                Locale.ROOT,
                "Step %-15s (loadType=%s, CR=%3d, TPS=%d): ops=%d, errors=%d, P50=%d us, P90=%d us, P99=%d us\n",
                ctx.step.getName(),
                ctx.step.getLoadType(),
                s.getConcurrency(),
                ctx.step.getTps(),
                s.getOperationCount(),
                s.getErrorCount(),
                s.getP50Micros(),
                s.getP90Micros(),
                s.getP99Micros()));
      }
      System.out.print(sb.toString());
      return;
    }
    Map<ChannelPoolPhase, Long> cancelled = new EnumMap<>(ChannelPoolPhase.class);
    for (ChannelPoolPhase phase : ChannelPoolPhase.values()) {
      cancelled.put(phase, phaseRecorders.get(phase).getCancelledCount());
    }
    System.out.print(report.formatSummary(cancelled));
  }

  public void run() throws Exception {
    if (yamlConfig != null && !yamlConfig.getSteps().isEmpty()) {
      runConfiguredSteps(yamlConfig);
      return;
    }
    if (unifiedProfileConfig != null) {
      runConfiguredSteps(BenchmarkYamlConfig.fromLegacy(unifiedProfileConfig));
      return;
    }
    System.out.println(
        "Starting " + getBenchmarkName() + " with TPS: " + tps + ", threads: " + threads);
    ExecutorService executor = Executors.newFixedThreadPool(threads);

    startResourceMonitoring();

    String socketPath = System.getenv("SPANNER_BENCHMARK_SOCKET");
    Thread generatorThread;
    if (socketPath != null && !socketPath.isEmpty()) {
      generatorThread =
          new Thread(
              () -> {
                runSocketTriggeredGenerator(socketPath, executor);
              },
              "Socket-Generator");
    } else {
      generatorThread =
          new Thread(
              () -> {
                loadType.run(this, executor);
              },
              "TPS-Generator");
    }

    generatorThread.start();

    try {
      if (duration != null) {
        Thread.sleep(duration.toMillis());
      } else {
        Thread.sleep(Long.MAX_VALUE);
      }
      System.out.println("Benchmark duration reached. Stopping...");
      generatorThread.interrupt();
      cleanupSocket();
      if (resourceMonitor != null) {
        resourceMonitor.stop();
      }
      executor.shutdownNow();
    } catch (InterruptedException e) {
      System.out.println("Benchmark interrupted.");
      generatorThread.interrupt();
      cleanupSocket();
      if (resourceMonitor != null) {
        resourceMonitor.stop();
      }
      executor.shutdownNow();
    }
  }

  private final java.util.concurrent.atomic.AtomicLong lastQueueLogTime =
      new java.util.concurrent.atomic.AtomicLong(0);

  void submitTask(ExecutorService executor) {
    if (executor instanceof java.util.concurrent.ThreadPoolExecutor) {
      java.util.concurrent.ThreadPoolExecutor tp =
          (java.util.concurrent.ThreadPoolExecutor) executor;
      int queueSize = tp.getQueue().size();
      if (queueSize > 0) {
        long now = System.currentTimeMillis();
        long lastLog = lastQueueLogTime.get();
        if (now - lastLog > 1000) { // log at most once per second
          if (lastQueueLogTime.compareAndSet(lastLog, now)) {
            System.out.println(
                "Queue size: " + queueSize + " (concurrency limit reached, tasks are queueing)");
          }
        }
      }
    }
    executor.submit(
        () -> {
          long startTime = System.nanoTime();
          try {
            executeOperation();
          } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted() && !isCancellationOrInterruption(e)) {
              System.err.println("Operation failed: " + e.getMessage());
              errorCounter.add(1, getAttributes());
            }
          } finally {
            if (shouldMeasureEntireMethod()) {
              long endTime = System.nanoTime();
              long latencyNs = endTime - startTime;
              long latencyUs = latencyNs / 1000;
              latencyHistogram.record(latencyUs, getAttributes());
            }
            operationCounter.add(1, getAttributes());
          }
        });
  }

  private void runSocketTriggeredGenerator(String socketPath, ExecutorService executor) {
    System.out.println("Connecting to workload generator sidecar socket: " + socketPath);
    try {
      UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
      socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
      socketChannel.connect(address);

      // Send READY signal
      ByteBuffer buffer = ByteBuffer.wrap("READY\n".getBytes());
      socketChannel.write(buffer);

      ByteBuffer readBuffer = ByteBuffer.allocate(1024);
      while (socketChannel.isOpen()) {
        int bytesRead = socketChannel.read(readBuffer);
        if (bytesRead == -1) {
          System.out.println("Workload generator socket connection closed by server.");
          break;
        }
        readBuffer.flip();
        for (int i = 0; i < bytesRead; i++) {
          if (readBuffer.get(i) == 0x01) {
            submitTask(executor);
          }
        }
        readBuffer.clear();
      }
    } catch (Exception e) {
      if (!Thread.currentThread().isInterrupted()) {
        System.err.println("Socket reader loop error: " + e.getMessage());
        System.exit(1);
      }
    } finally {
      cleanupSocket();
    }
  }

  private void cleanupSocket() {
    if (socketChannel != null) {
      try {
        socketChannel.close();
      } catch (Exception e) {
        // Ignore
      }
    }
  }

  static boolean isCancellationOrInterruption(Throwable e) {
    if (e == null) {
      return false;
    }
    if (e instanceof InterruptedException || e instanceof java.io.InterruptedIOException) {
      return true;
    }
    if (e instanceof com.google.cloud.spanner.SpannerException) {
      com.google.cloud.spanner.SpannerException se = (com.google.cloud.spanner.SpannerException) e;
      if (se.getErrorCode() == com.google.cloud.spanner.ErrorCode.CANCELLED) {
        return true;
      }
    }
    String message = e.getMessage();
    if (message != null
        && (message.contains("Interrupted")
            || message.contains("CANCELLED")
            || message.contains("InterruptedIOException"))) {
      return true;
    }
    return isCancellationOrInterruption(e.getCause());
  }

  private void startResourceMonitoring() {
    resourceMonitor =
        new ResourceMonitor(
            resourceProbeInterval, memoryUsageHistogram, cpuUtilizationHistogram, getAttributes());
    resourceMonitor.start();
  }

  protected abstract void executeOperation() throws Exception;

  @Nonnull
  protected abstract String getBenchmarkName();

  @Nonnull
  protected abstract String getBenchmarkType();

  public static Duration parseDuration(String durationStr) {
    if (durationStr == null
        || durationStr.isEmpty()
        || "inf".equalsIgnoreCase(durationStr)
        || "infinite".equalsIgnoreCase(durationStr)) {
      return null;
    }
    if (durationStr.endsWith("s")) {
      return Duration.ofSeconds(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
    } else if (durationStr.endsWith("m")) {
      return Duration.ofMinutes(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
    } else if (durationStr.endsWith("h")) {
      return Duration.ofHours(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
    } else {
      return Duration.ofSeconds(Long.parseLong(durationStr)); // default to seconds
    }
  }

  static long calculatePoissonDelay(double rate) {
    double u = ThreadLocalRandom.current().nextDouble();
    return (long) (-Math.log(1.0 - u) * 1_000_000_000L / rate);
  }

  public static void sleepHybrid(long targetNanoTime) {
    long now = System.nanoTime();
    if (targetNanoTime > now) {
      long diff = targetNanoTime - now;
      if (diff > 1_000_000L) { // > 1ms
        java.util.concurrent.locks.LockSupport.parkNanos(diff - 100_000L); // 100us buffer
      }
      while (System.nanoTime() < targetNanoTime) {
        Thread.onSpinWait();
      }
    }
  }
}
