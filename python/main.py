import os
import sys
import time
import random
from google.cloud import spanner
from opentelemetry import metrics
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.resources import Resource

METER_NAME = "spanner-benchmark"
COUNTER_NAME = "query_count"
LATENCY_NAME = "query_latency"

def main():
    if len(sys.argv) < 5:
        print(f"Usage: {sys.argv[0]} <PROJECT_ID> <INSTANCE_ID> <DATABASE_ID> <TABLE_NAME>")
        sys.exit(1)

    project_id = sys.argv[1]
    instance_id = sys.argv[2]
    database_id = sys.argv[3]
    table_name = sys.argv[4]

    # Initialize OpenTelemetry
    exporter = OTLPMetricExporter()
    reader = PeriodicExportingMetricReader(exporter, export_interval_millis=60000)
    resource = Resource.create({"service.name": "spanner-python-benchmark", "cloud.project.id": project_id})
    provider = MeterProvider(metric_readers=[reader], resource=resource)
    metrics.set_meter_provider(provider)

    meter = metrics.get_meter(METER_NAME)
    query_counter = meter.create_counter(
        name=COUNTER_NAME,
        description="Number of queries executed",
    )
    latency_histogram = meter.create_histogram(
        name=LATENCY_NAME,
        description="Query latency in milliseconds",
        unit="ms",
    )

    # Initialize Spanner
    spanner_client = spanner.Client(project=project_id)
    instance = spanner_client.instance(instance_id)
    database = instance.database(database_id)

    print("Starting benchmark...")

    # Run benchmark loop
    while True:
        random_id = random.randint(0, 10000)
        sql = f"SELECT * FROM {table_name} WHERE id = @id"
        
        start_time = time.perf_counter()
        with database.snapshot() as snapshot:
            results = snapshot.execute_sql(
                sql,
                params={"id": random_id},
                param_types={"id": spanner.param_types.INT64},
            )
            for row in results:
                # Consume results to avoid DCE
                _ = row[0]
                
        end_time = time.perf_counter()
        latency_ms = (end_time - start_time) * 1000.0

        # Record metrics
        query_counter.add(1)
        latency_histogram.record(latency_ms)

        print(f"Query executed in {latency_ms:.2f} ms")

        time.sleep(1)

if __name__ == "__main__":
    main()
