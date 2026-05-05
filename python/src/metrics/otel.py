from opentelemetry import metrics
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.view import View, ExplicitBucketHistogramAggregation
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.exporter.cloud_monitoring import CloudMonitoringMetricsExporter
from opentelemetry.sdk.resources import Resource
from typing import Tuple, Callable

METER_NAME = "spanner-benchmark"
LATENCY_NAME = "spanner_client_benchmarks/latency"

def setup_metrics(project_id: str, is_emulator: bool) -> Tuple[metrics.Meter, Callable[[], None]]:
    """
    Initializes OpenTelemetry metrics provider, binding a custom View for explicit
    histogram bucket boundaries and exporting metrics directly to Google Cloud Monitoring.
    """
    if is_emulator:
        print("Spanner Emulator or localhost detected. Initializing No-op metrics.")
        # metrics.get_meter with a default empty provider is a pure no-op (parity with Go/Java/Node)
        noop_meter = metrics.get_meter(METER_NAME)
        return noop_meter, lambda: None

    # Instantiate the Google Cloud Metric Exporter with unique identifier to allow parallel runs
    exporter = CloudMonitoringMetricsExporter(project_id=project_id, add_unique_identifier=True)

    # Periodic metric reader flushes data every 60 seconds (matching parity specifications)
    reader = PeriodicExportingMetricReader(exporter, export_interval_millis=60000)

    # Explicit bucket boundaries matching Java, Go, and Node exactly (in microseconds)
    explicit_boundaries = [
        500.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0, 3500.0, 4000.0, 4500.0, 5000.0,
        6000.0, 7000.0, 8000.0, 9000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0, 20000.0,
        25000.0, 30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0,
    ]

    # Create custom view to overlay explicit bucket histogram aggregations onto the target latency instrument
    latency_view = View(
        instrument_name=LATENCY_NAME,
        aggregation=ExplicitBucketHistogramAggregation(boundaries=explicit_boundaries),
    )

    # Define basic project resource tags (lands metrics under 'Generic Node' in Stackdriver for 1-to-1 parity)
    resource = Resource.create({"cloud.project.id": project_id})

    # Build MeterProvider with readers, views, and resource constraints
    provider = MeterProvider(
        metric_readers=[reader],
        views=[latency_view],
        resource=resource
    )

    # Assign global meter provider context
    metrics.set_meter_provider(provider)

    meter = metrics.get_meter(METER_NAME)

    # Return both the meter instrument broker and the shutdown cleaner hook
    def shutdown_hook():
        try:
            provider.shutdown()
            print("Metrics provider shut down successfully.")
        except Exception as err:
            print(f"Error during metrics provider shutdown: {err}")

    return meter, shutdown_hook
