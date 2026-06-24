import uuid
from typing import Callable, Tuple

from opentelemetry import metrics
from opentelemetry.exporter.cloud_monitoring import CloudMonitoringMetricsExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.metrics.view import ExplicitBucketHistogramAggregation, View
from opentelemetry.sdk.resources import Resource

METER_NAME = "spanner-benchmark"
LATENCY_NAME = "spanner_client_benchmarks/latency"
READ_LATENCY_NAME = "spanner_client_benchmarks/read_latency"
OPERATION_COUNT_NAME = "spanner_client_benchmarks/operation_count"
ERROR_COUNT_NAME = "spanner_client_benchmarks/error_count"
MEMORY_USAGE_NAME = "spanner_client_benchmarks/memory_usage"
CPU_UTILIZATION_NAME = "spanner_client_benchmarks/cpu_utilization"

_testing_meter_provider = None


def set_testing_meter_provider(provider):
    global _testing_meter_provider
    _testing_meter_provider = provider


def setup_metrics(
    project_id: str, no_metrics: bool, benchmark_name: str = None
) -> Tuple[metrics.Meter, Callable[[], None]]:
    """
    Initializes OpenTelemetry metrics provider, binding a custom View for explicit
    histogram bucket boundaries and exporting metrics directly to Google Cloud Monitoring.
    """
    global _testing_meter_provider
    if _testing_meter_provider is not None:
        metrics.set_meter_provider(_testing_meter_provider)
        meter = _testing_meter_provider.get_meter(METER_NAME)
        return meter, lambda: None

    if no_metrics or project_id == "fake-project":
        print(
            "Spanner Emulator, localhost, or fake-project detected. Initializing No-op metrics."
        )
        # metrics.get_meter with a default empty provider is a pure no-op (parity with Go/Java/Node)
        noop_meter = metrics.get_meter(METER_NAME)
        return noop_meter, lambda: None

    # Instantiate the Google Cloud Metric Exporter with unique identifier to allow parallel runs
    exporter = CloudMonitoringMetricsExporter(
        project_id=project_id, add_unique_identifier=True
    )

    # Periodic metric reader flushes data every 60 seconds (matching parity specifications)
    reader = PeriodicExportingMetricReader(exporter, export_interval_millis=60000)

    def get_latency_buckets():
        buckets = [float(i) for i in range(50, 5050, 50)]
        buckets.extend(
            [
                6000.0,
                7000.0,
                8000.0,
                9000.0,
                10000.0,
                12000.0,
                14000.0,
                16000.0,
                18000.0,
                20000.0,
                25000.0,
                30000.0,
                40000.0,
                50000.0,
                75000.0,
                100000.0,
                150000.0,
                200000.0,
            ]
        )
        return buckets

    explicit_boundaries = get_latency_buckets()

    read_latency_boundaries = [
        50000.0,
        100000.0,
        250000.0,
        500000.0,
        750000.0,
        1000000.0,
        1250000.0,
        1500000.0,
        1750000.0,
        2000000.0,
        2250000.0,
        2500000.0,
        2750000.0,
        3000000.0,
        3250000.0,
        3500000.0,
        3750000.0,
        4000000.0,
        4250000.0,
        4500000.0,
        4750000.0,
        5000000.0,
        5500000.0,
        6000000.0,
        6500000.0,
        7000000.0,
        7500000.0,
        8000000.0,
        8500000.0,
        9000000.0,
        9500000.0,
        10000000.0,
        12500000.0,
        15000000.0,
        20000000.0,
        30000000.0,
    ]

    # Create custom view to overlay explicit bucket histogram aggregations onto the target latency instrument
    latency_view = View(
        instrument_name=LATENCY_NAME,
        aggregation=ExplicitBucketHistogramAggregation(boundaries=explicit_boundaries),
    )

    read_latency_view = View(
        instrument_name=READ_LATENCY_NAME,
        aggregation=ExplicitBucketHistogramAggregation(
            boundaries=read_latency_boundaries
        ),
    )

    MB = 1024.0 * 1024.0
    memory_usage_view = View(
        instrument_name=MEMORY_USAGE_NAME,
        aggregation=ExplicitBucketHistogramAggregation(
            boundaries=[
                2.5 * MB,
                5.0 * MB,
                7.5 * MB,
                10.0 * MB,
                20.0 * MB,
                30.0 * MB,
                40.0 * MB,
                50.0 * MB,
                60.0 * MB,
                70.0 * MB,
                80.0 * MB,
                90.0 * MB,
                100.0 * MB,
                200.0 * MB,
                300.0 * MB,
                400.0 * MB,
                500.0 * MB,
                750.0 * MB,
                1000.0 * MB,
                1500.0 * MB,
                2000.0 * MB,
                3000.0 * MB,
                5000.0 * MB,
                10000.0 * MB,
            ]
        ),
    )

    cpu_utilization_view = View(
        instrument_name=CPU_UTILIZATION_NAME,
        aggregation=ExplicitBucketHistogramAggregation(
            boundaries=[
                0.01,
                0.02,
                0.03,
                0.04,
                0.05,
                0.1,
                0.15,
                0.2,
                0.25,
                0.3,
                0.35,
                0.4,
                0.45,
                0.5,
                0.6,
                0.7,
                0.8,
                0.9,
                0.95,
                1.0,
            ]
        ),
    )

    # Define basic project resource tags (lands metrics under 'Generic Node' in Stackdriver for 1-to-1 parity)
    service_name = benchmark_name or "spanner-benchmark"
    instance_id = str(uuid.uuid4())
    resource = Resource.create(
        {
            "cloud.project.id": project_id,
            "service.name": service_name,
            "service.instance.id": instance_id,
        }
    )

    # Build MeterProvider with readers, views, and resource constraints
    provider = MeterProvider(
        metric_readers=[reader],
        views=[
            latency_view,
            read_latency_view,
            memory_usage_view,
            cpu_utilization_view,
        ],
        resource=resource,
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
