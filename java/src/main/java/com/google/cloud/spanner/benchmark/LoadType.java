package com.google.cloud.spanner.benchmark;

import java.util.concurrent.ExecutorService;

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
                    System.err.println("Operation failed: " + e.getMessage());
                    benchmark.errorCounter.add(1, benchmark.getAttributes());
                  }
                } finally {
                  if (benchmark.shouldMeasureEntireMethod()) {
                    long endTime = System.nanoTime();
                    long latencyNs = endTime - startTime;
                    long latencyUs = latencyNs / 1000;
                    benchmark.latencyHistogram.record(latencyUs, benchmark.getAttributes());
                  }
                  benchmark.operationCounter.add(1, benchmark.getAttributes());
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

  public abstract void run(AbstractBenchmark benchmark, ExecutorService executor);
}
