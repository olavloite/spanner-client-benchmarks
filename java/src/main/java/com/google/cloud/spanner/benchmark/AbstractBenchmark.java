package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
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

  public void run() throws Exception {
    System.out.println(
        "Starting " + getBenchmarkName() + " with TPS: " + tps + ", threads: " + threads);
    ExecutorService executor = Executors.newFixedThreadPool(threads);

    startResourceMonitoring();

    Thread generatorThread =
        new Thread(
            () -> {
              loadType.run(this, executor);
            },
            "TPS-Generator");

    generatorThread.start();

    try {
      if (duration != null) {
        Thread.sleep(duration.toMillis());
      } else {
        Thread.sleep(Long.MAX_VALUE);
      }
      System.out.println("Benchmark duration reached. Stopping...");
      generatorThread.interrupt();
      if (resourceMonitor != null) {
        resourceMonitor.stop();
      }
      executor.shutdownNow();
    } catch (InterruptedException e) {
      System.out.println("Benchmark interrupted.");
      generatorThread.interrupt();
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
