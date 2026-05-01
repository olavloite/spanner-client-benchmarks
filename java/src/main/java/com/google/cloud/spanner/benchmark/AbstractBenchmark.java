package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.DatabaseClient;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

public abstract class AbstractBenchmark {

    protected final DatabaseClient client;
    protected final LongHistogram latencyHistogram;
    protected final String tableName;
    protected final long minId;
    protected final long maxId;
    protected final double tps;
    protected final int threads;
    protected final Duration duration;
    protected final boolean forAlerting;
    private final Attributes attributes; // Pre-created attributes

    public AbstractBenchmark(DatabaseClient client, LongHistogram latencyHistogram, String tableName, long minId, long maxId, double tps, int threads, Duration duration, boolean forAlerting) {
        this.client = client;
        this.latencyHistogram = latencyHistogram;
        this.tableName = tableName;
        this.minId = minId;
        this.maxId = maxId;
        this.tps = tps;
        this.threads = threads;
        this.duration = duration;
        this.forAlerting = forAlerting;
        // Pre-create attributes to avoid object creation overhead in the hot path
        this.attributes = Attributes.of(
                AttributeKey.stringKey("benchmark_type"), getBenchmarkType(),
                AttributeKey.doubleKey("tps"), tps,
                AttributeKey.booleanKey("for_alerting"), forAlerting,
                AttributeKey.stringKey("client"), "java-client"
        );
    }

    public void run() throws Exception {
        System.out.println("Starting " + getBenchmarkName() + " with TPS: " + tps + ", threads: " + threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        Thread generatorThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                executor.submit(() -> {
                    long startTime = System.nanoTime();
                    try {
                        executeOperation();
                    } catch (Exception e) {
                        System.err.println("Operation failed: " + e.getMessage());
                    }
                    long endTime = System.nanoTime();
                    long latencyNs = endTime - startTime;
                    long latencyUs = latencyNs / 1000;

                    // Record metrics with pre-created attributes
                    latencyHistogram.record(latencyUs, this.attributes);
                });
                LockSupport.parkNanos(calculatePoissonDelay(tps));
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

    protected abstract void executeOperation() throws Exception;
    
    protected abstract String getBenchmarkName();
    
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
