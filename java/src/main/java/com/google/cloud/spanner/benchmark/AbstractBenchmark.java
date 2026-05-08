package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.common.Attributes;
import javax.annotation.Nonnull;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

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
    private final Attributes attributes; // Pre-created attributes

    public AbstractBenchmark(DatabaseClient client, LongHistogram latencyHistogram, LongCounter operationCounter, LongCounter errorCounter, String tableName, long minId, long maxId, double tps, int threads, Duration duration, boolean forAlerting, double burstFactor, double burstDuration, double burstFraction) {
        this.client = client;
        this.latencyHistogram = latencyHistogram;
        this.operationCounter = operationCounter;
        this.errorCounter = errorCounter;
        this.tableName = tableName;
        this.minId = minId;
        this.maxId = maxId;
        this.tps = tps;
        this.threads = threads;
        this.duration = duration;
        this.forAlerting = forAlerting;
        this.burstFactor = burstFactor;
        this.burstDuration = burstDuration;
        this.burstFraction = burstFraction;
        // Pre-create attributes to avoid object creation overhead in the hot path
        this.attributes = Attributes.builder()
                .put("benchmark_type", getBenchmarkType())
                .put("tps", tps)
                .put("for_alerting", forAlerting)
                .put("client", "java-client")
                .put("burst_factor", burstFactor)
                .put("burst_duration", burstDuration)
                .put("burst_fraction", burstFraction)
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
        System.out.println("Starting " + getBenchmarkName() + " with TPS: " + tps + ", threads: " + threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        Thread generatorThread = new Thread(() -> {
            if (burstFactor == 1.0) {
                while (!Thread.currentThread().isInterrupted()) {
                    submitTask(executor);
                    LockSupport.parkNanos(calculatePoissonDelay(tps));
                }
            } else {
                double rBurst = tps * burstFactor;
                double rNormal = (tps - burstFraction * rBurst) / (1.0 - burstFraction);

                double mu2 = 1.0 / burstDuration;
                double mu1 = mu2 * burstFraction / (1.0 - burstFraction);

                boolean inBurst = false;
                long nextStateChangeTime = System.nanoTime() + calculatePoissonDelay(mu1);

                while (!Thread.currentThread().isInterrupted()) {
                    long now = System.nanoTime();
                    if (now >= nextStateChangeTime) {
                        inBurst = !inBurst;
                        long nextDelay = inBurst ? calculatePoissonDelay(mu2) : calculatePoissonDelay(mu1);
                        nextStateChangeTime = now + nextDelay;
                    }

                    double currentRate = inBurst ? rBurst : rNormal;
                    long delayNs;
                    if (currentRate <= 0) {
                        delayNs = Long.MAX_VALUE;
                    } else {
                        delayNs = calculatePoissonDelay(currentRate);
                    }

                    long timeToStateChange = nextStateChangeTime - now;
                    if (delayNs > timeToStateChange) {
                        if (timeToStateChange > 0) {
                            LockSupport.parkNanos(timeToStateChange);
                        }
                        continue;
                    }

                    submitTask(executor);
                    LockSupport.parkNanos(delayNs);
                }
            }
        }, "TPS-Generator");

        generatorThread.start();

        try {
            if (duration != null) {
                Thread.sleep(duration.toMillis());
            } else {
                Thread.sleep(Long.MAX_VALUE);
            }
            System.out.println("Benchmark duration reached. Stopping...");
            generatorThread.interrupt();
            executor.shutdownNow();
        } catch (InterruptedException e) {
            System.out.println("Benchmark interrupted.");
            generatorThread.interrupt();
            executor.shutdownNow();
        }
    }

    private void submitTask(ExecutorService executor) {
        executor.submit(() -> {
            long startTime = System.nanoTime();
            try {
                executeOperation();
            } catch (Exception e) {
                System.err.println("Operation failed: " + e.getMessage());
                errorCounter.add(1, getAttributes());
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

    protected abstract void executeOperation() throws Exception;
    
    @Nonnull
    protected abstract String getBenchmarkName();
    
    @Nonnull
    protected abstract String getBenchmarkType();

    public static Duration parseDuration(String durationStr) {
        if (durationStr == null || durationStr.isEmpty() || "inf".equalsIgnoreCase(durationStr) || "infinite".equalsIgnoreCase(durationStr)) {
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

    private static long calculatePoissonDelay(double rate) {
        double u = ThreadLocalRandom.current().nextDouble();
        return (long) (-Math.log(1.0 - u) * 1_000_000_000L / rate);
    }
}
