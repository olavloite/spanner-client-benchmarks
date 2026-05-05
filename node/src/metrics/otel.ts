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

  // Explicit bucket boundaries matching Java and Go exactly (in microseconds)
  const explicitBoundaries = [
    1000.0, 2500.0, 5000.0, 7500.0, 10000.0, 15000.0, 20000.0, 25000.0,
    30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0,
  ];

  // Register custom view to apply explicit bucket histogram aggregation to the benchmark latency instrument
  const latencyView = new View({
    instrumentName: LATENCY_NAME,
    instrumentType: InstrumentType.HISTOGRAM,
    aggregation: new ExplicitBucketHistogramAggregation(explicitBoundaries),
  });

  // Set up standard resource tags (keeps it under Generic Node like Go and Java)
  const resource = new Resource({
    "cloud.project.id": projectId,
  });

  const provider = new MeterProvider({
    resource: resource,
    views: [latencyView],
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
