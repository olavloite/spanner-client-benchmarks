package com.google.cloud.spanner.benchmark;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.LockSupport;

public enum LoadType {
  STEADY {
    @Override
    public void run(AbstractBenchmark benchmark, ExecutorService executor) {
      while (!Thread.currentThread().isInterrupted()) {
        benchmark.submitTask(executor);
        LockSupport.parkNanos(AbstractBenchmark.calculatePoissonDelay(benchmark.tps));
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

      while (!Thread.currentThread().isInterrupted()) {
        long now = System.nanoTime();
        if (now >= nextStateChangeTime) {
          inBurst = !inBurst;
          long nextDelay =
              inBurst
                  ? AbstractBenchmark.calculatePoissonDelay(mu2)
                  : AbstractBenchmark.calculatePoissonDelay(mu1);
          nextStateChangeTime = now + nextDelay;
        }

        double currentRate = inBurst ? rBurst : rNormal;
        long delayNs;
        if (currentRate <= 0) {
          delayNs = Long.MAX_VALUE;
        } else {
          delayNs = AbstractBenchmark.calculatePoissonDelay(currentRate);
        }

        long timeToStateChange = nextStateChangeTime - now;
        if (delayNs > timeToStateChange) {
          if (timeToStateChange > 0) {
            LockSupport.parkNanos(timeToStateChange);
          }
          continue;
        }

        benchmark.submitTask(executor);
        LockSupport.parkNanos(delayNs);
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

      while (!Thread.currentThread().isInterrupted()) {
        long nowNs = System.nanoTime();
        long elapsedNs = nowNs - startTimeNs;

        // Calculate rate based on sine wave
        double angle = (2.0 * Math.PI * (elapsedNs % cycleDurationNs)) / cycleDurationNs;
        double currentRate = tps + amplitude * Math.cos(angle - Math.PI);

        benchmark.submitTask(executor);
        LockSupport.parkNanos(AbstractBenchmark.calculatePoissonDelay(currentRate));
      }
    }
  };

  public abstract void run(AbstractBenchmark benchmark, ExecutorService executor);
}
