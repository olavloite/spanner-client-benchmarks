import { metrics, Meter } from "@opentelemetry/api";
import {
  MeterProvider,
  PeriodicExportingMetricReader,
  View,
  InstrumentType,
  ExplicitBucketHistogramAggregation,
} from "@opentelemetry/sdk-metrics";
import { MetricExporter } from "@google-cloud/opentelemetry-cloud-monitoring-exporter";
import { Resource } from "@opentelemetry/resources";

export const METER_NAME = "spanner-benchmark";
export const LATENCY_NAME = "spanner_client_benchmarks/latency";
export const READ_LATENCY_NAME = "spanner_client_benchmarks/read_latency";
export const OPERATION_COUNT_NAME = "spanner_client_benchmarks/operation_count";
export const ERROR_COUNT_NAME = "spanner_client_benchmarks/error_count";
export const MEMORY_USAGE_NAME = "spanner_client_benchmarks/memory_usage";
export const CPU_UTILIZATION_NAME = "spanner_client_benchmarks/cpu_utilization";

export interface MetricSetupResult {
  meter: Meter;
  shutdown: () => Promise<void>;
}

/**
 * Initializes the OpenTelemetry metrics provider and exports to Google Cloud Monitoring.
 * Returns the meter instance and a shutdown cleanup hook.
 */
export function setupMetrics(projectId: string, isEmulator: boolean): MetricSetupResult {
  if (isEmulator) {
    console.log("Spanner Emulator or localhost detected. Initializing No-op metric provider.");
    const noopMeter = metrics.getMeter(METER_NAME);
    return {
      meter: noopMeter,
      shutdown: async () => {},
    };
  }

  // Create the Google Cloud Metric Exporter
  const exporter = new MetricExporter({
    projectId: projectId,
  });

  // Export metrics every 60 seconds (matching Java and Go)
  const reader = new PeriodicExportingMetricReader({
    exporter: exporter,
    exportIntervalMillis: 60000,
  });

  // Explicit bucket boundaries matching Java, Go, and Python exactly (in microseconds)
  const explicitBoundaries = [
    500.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0, 4000.0, 4500.0, 5000.0,
    6000.0, 7000.0, 8000.0, 9000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0, 20000.0,
    25000.0, 30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0,
  ];

  const readLatencyBoundaries = [
    50000.0, 100000.0, 250000.0, 500000.0, 750000.0,
    1000000.0, 1250000.0, 1500000.0, 1750000.0, 2000000.0, 2250000.0, 2500000.0, 2750000.0, 3000000.0, 3250000.0, 3500000.0, 3750000.0, 4000000.0, 4250000.0, 4500000.0, 4750000.0, 5000000.0,
    5500000.0, 6000000.0, 6500000.0, 7000000.0, 7500000.0, 8000000.0, 8500000.0, 9000000.0, 9500000.0, 10000000.0,
    12500000.0, 15000000.0, 20000000.0, 30000000.0,
  ];

  // Register custom view to apply explicit bucket histogram aggregation to the benchmark latency instrument
  const latencyView = new View({
    instrumentName: LATENCY_NAME,
    instrumentType: InstrumentType.HISTOGRAM,
    aggregation: new ExplicitBucketHistogramAggregation(explicitBoundaries),
  });

  const readLatencyView = new View({
    instrumentName: READ_LATENCY_NAME,
    instrumentType: InstrumentType.HISTOGRAM,
    aggregation: new ExplicitBucketHistogramAggregation(readLatencyBoundaries),
  });

  const memoryUsageView = new View({
    instrumentName: MEMORY_USAGE_NAME,
    instrumentType: InstrumentType.HISTOGRAM,
    aggregation: new ExplicitBucketHistogramAggregation([
      10e6, 25e6, 50e6, 100e6, 200e6, 300e6, 400e6, 500e6, 750e6, 1e9, 1.5e9, 2e9, 3e9, 5e9, 10e9,
    ]),
  });

  const cpuUtilizationView = new View({
    instrumentName: CPU_UTILIZATION_NAME,
    instrumentType: InstrumentType.HISTOGRAM,
    aggregation: new ExplicitBucketHistogramAggregation([
      0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 1.0,
    ]),
  });

  // Set up standard resource tags (keeps it under Generic Node like Go and Java)
  const resource = new Resource({
    "cloud.project.id": projectId,
  });

  const provider = new MeterProvider({
    resource: resource,
    views: [latencyView, readLatencyView, memoryUsageView, cpuUtilizationView],
  });

  provider.addMetricReader(reader);

  // Set global meter provider
  metrics.setGlobalMeterProvider(provider);

  const meter = metrics.getMeter(METER_NAME);

  return {
    meter,
    shutdown: async () => {
      try {
        await provider.shutdown();
        console.log("Metrics provider shut down successfully.");
      } catch (err) {
        console.error("Error during metrics provider shutdown:", err);
      }
    },
  };
}
