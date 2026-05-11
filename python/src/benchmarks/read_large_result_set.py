import time
from google.cloud import spanner
from google.cloud.spanner_v1.database import Database
from opentelemetry.metrics import Histogram, Counter
from .abstract_benchmark import AbstractBenchmark

SQL = """SELECT
  MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2) = 0 AS random_bool,
  CAST(GENERATE_UUID() AS BYTES) AS random_bytes,
  DATE_FROM_UNIX_DATE(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 2932896))) AS random_date,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT32) AS random_float32,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS FLOAT64) AS random_float64,
  MAKE_INTERVAL(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 10)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 12)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 28)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 24)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60)), ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 60))) AS random_interval,
  TO_JSON('{"key": "' || GENERATE_UUID() || '"}') AS random_json,
  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64,
  CAST(FARM_FINGERPRINT(GENERATE_UUID()) / FARM_FINGERPRINT(GENERATE_UUID()) AS NUMERIC) AS random_numeric,
  GENERATE_UUID() AS random_string,
  TIMESTAMP_MICROS(ABS(MOD(FARM_FINGERPRINT(GENERATE_UUID()), 1230219000000000))) AS random_timestamp,
  NEW_UUID() AS random_uuid
FROM UNNEST(GENERATE_ARRAY(1, @num_rows)) AS n"""

class ReadLargeResultSetBenchmark(AbstractBenchmark):
    def __init__(
        self,
        database: Database,
        latency_histogram: Histogram,
        operation_counter: Counter,
        error_counter: Counter,
        table_name: str,
        min_id: int,
        max_id: int,
        tps: float,
        threads: int,
        duration_sec: float | None,
        for_alerting: bool,
        num_rows: int,
        load_type: str = "steady",
        cycle_duration_sec: float | None = None,
        peak_factor: float = 2.0,
        burst_factor: float = 1.0,
        burst_duration: float = 1.0,
        burst_fraction: float = 0.1,
    ):
        super().__init__(
            database,
            latency_histogram,
            operation_counter,
            error_counter,
            table_name,
            min_id,
            max_id,
            tps,
            threads,
            duration_sec,
            for_alerting,
            load_type,
            cycle_duration_sec,
            peak_factor,
            burst_factor,
            burst_duration,
            burst_fraction,
        )
        self.num_rows = num_rows

    def get_benchmark_name(self) -> str:
        return "Read Large Result Set Benchmark"

    def get_benchmark_type(self) -> str:
        return "read-large-result-set"

    def should_measure_entire_method(self) -> bool:
        return False

    def get_attributes(self) -> dict:
        attrs = super().get_attributes()
        attrs["num_rows"] = self.num_rows
        return attrs

    def execute_operation(
        self, database: Database, table_name: str, min_id: int, max_id: int
    ) -> None:
        with database.snapshot() as snapshot:
            results = snapshot.execute_sql(
                SQL,
                params={"num_rows": self.num_rows},
                param_types={"num_rows": spanner.param_types.INT64},
            )

            row_iterator = iter(results)
            try:
                first_row = next(row_iterator)
                # Force full deserialization of the first row
                for cell in first_row:
                    pass
            except StopIteration:
                return

            # Measure iteration duration of remaining rows
            start_time = time.perf_counter()
            for row in row_iterator:
                # Force full deserialization of each row
                for cell in row:
                    pass
            end_time = time.perf_counter()
            latency_us = (end_time - start_time) * 1000000.0

            self.latency_histogram.record(latency_us, self.get_attributes())
