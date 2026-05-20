package com.google.cloud.spanner.benchmark;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;

public class BenchmarkMetrics {
    public final LongHistogram latencyHistogram;
    public final LongCounter operationCounter;
    public final LongCounter errorCounter;
    public final LongHistogram memoryUsageHistogram;
    public final DoubleHistogram cpuUtilizationHistogram;

    public BenchmarkMetrics(LongHistogram latencyHistogram, LongCounter operationCounter,
                            LongCounter errorCounter, LongHistogram memoryUsageHistogram,
                            DoubleHistogram cpuUtilizationHistogram) {
        this.latencyHistogram = latencyHistogram;
        this.operationCounter = operationCounter;
        this.errorCounter = errorCounter;
        this.memoryUsageHistogram = memoryUsageHistogram;
        this.cpuUtilizationHistogram = cpuUtilizationHistogram;
    }
}
