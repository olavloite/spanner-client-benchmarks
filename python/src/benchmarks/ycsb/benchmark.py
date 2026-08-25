import random
import sys
import threading
import time
from typing import Optional

from google.cloud import spanner
from google.cloud.spanner_v1.database import Database
from opentelemetry.metrics import Counter, Histogram

from ..abstract_benchmark import AbstractBenchmark, LoadType
from .generator import (
    DEFAULT_ZIPFIAN_CONSTANT,
    ScrambledZipfianGenerator,
    SkewedLatestGenerator,
    ZipfianGenerator,
)
from .utils import build_key_name, generate_random_string
from .workload import (
    KeyDistribution,
    Operation,
    Workload,
)


class YcsbBenchmark(AbstractBenchmark):
    """
    Standard Yahoo! Cloud Serving Benchmark (YCSB) Workloads A through F for Cloud Spanner.
    """

    def __init__(
        self,
        database: Database,
        latency_histogram: Histogram,
        operation_counter: Counter,
        error_counter: Counter,
        memory_usage_histogram: Optional[Histogram],
        cpu_utilization_histogram: Optional[Histogram],
        resource_probe_interval_str: str,
        table_name: str = "usertable",
        workload: Workload = Workload.B,
        distribution: KeyDistribution = KeyDistribution.SCRAMBLED_ZIPFIAN,
        record_count: int = 100000,
        zero_padding: int = 12,
        field_count: int = 10,
        field_length: int = 100,
        use_read_row: bool = False,
        tps: float = 10.0,
        threads: int = 10,
        duration_sec: Optional[float] = None,
        for_alerting: bool = False,
        benchmark_name: str = "",
        load_type: LoadType = LoadType.STEADY,
        cycle_duration_sec: Optional[float] = None,
        peak_factor: float = 2.0,
        burst_factor: float = 1.0,
        burst_duration: float = 1.0,
        burst_fraction: float = 0.1,
        is_mock: bool = False,
    ):
        super().__init__(
            database=database,
            latency_histogram=latency_histogram,
            operation_counter=operation_counter,
            error_counter=error_counter,
            memory_usage_histogram=memory_usage_histogram,
            cpu_utilization_histogram=cpu_utilization_histogram,
            resource_probe_interval_str=resource_probe_interval_str,
            table_name=table_name,
            min_id=0,
            max_id=record_count - 1,
            tps=tps,
            threads=threads,
            duration_sec=duration_sec,
            for_alerting=for_alerting,
            benchmark_name=benchmark_name,
            load_type=load_type,
            cycle_duration_sec=cycle_duration_sec,
            peak_factor=peak_factor,
            burst_factor=burst_factor,
            burst_duration=burst_duration,
            burst_fraction=burst_fraction,
            is_mock=is_mock,
        )
        self.workload = workload
        self.distribution = distribution
        self.record_count = record_count
        self.zero_padding = zero_padding
        self.field_count = field_count
        self.field_length = field_length
        self.use_read_row = use_read_row

        # Aligned OpenTelemetry metric attributes
        self.attributes["transaction_type"] = f"ycsb-{workload.value.lower()}"
        self.attributes["workload"] = workload.value

        self.field_names = [f"field{i}" for i in range(field_count)]
        self.insert_col_names = ["id"] + self.field_names
        fields_str = ", ".join(self.field_names)
        self.read_sql = f"SELECT {fields_str} FROM {table_name} WHERE id = @id"
        self.scan_sql = f"SELECT {fields_str} FROM {table_name} WHERE id >= @startKey ORDER BY id LIMIT @scanLength"

        self._insert_key_sequence = record_count
        self._insert_lock = threading.Lock()

        self.zipfian_generator = ZipfianGenerator(
            0, record_count - 1, DEFAULT_ZIPFIAN_CONSTANT
        )
        self.scrambled_zipfian_generator = ScrambledZipfianGenerator(
            0, record_count - 1, DEFAULT_ZIPFIAN_CONSTANT
        )
        self.skewed_latest_generator = SkewedLatestGenerator(
            lambda: self._insert_key_sequence, DEFAULT_ZIPFIAN_CONSTANT
        )

        # Detailed metrics per operation
        self._metrics_lock = threading.Lock()
        self._op_counts: dict[Operation, int] = {op: 0 for op in Operation}
        self._op_durations_ns: dict[Operation, int] = {op: 0 for op in Operation}

    def get_benchmark_name(self) -> str:
        return f"YCSB Benchmark ({self.workload.value})"

    def get_benchmark_type(self) -> str:
        return "ycsb"

    def _get_random_key(self) -> str:
        if self.is_mock:
            return build_key_name(0, self.zero_padding)

        if self.distribution == KeyDistribution.SCRAMBLED_ZIPFIAN:
            key_num = self.scrambled_zipfian_generator.next_value()
        elif self.distribution == KeyDistribution.ZIPFIAN:
            key_num = self.zipfian_generator.next_value()
        elif self.distribution == KeyDistribution.UNIFORM:
            key_num = random.randint(0, self.record_count - 1)
        else:
            key_num = self.scrambled_zipfian_generator.next_value()

        return build_key_name(key_num, self.zero_padding)

    def _consume_row(self, row) -> int:
        """Consumes all column values in the row to force decoding and prevent dead-code elimination."""
        total_len = 0
        for col in row:
            if col is not None:
                total_len += len(str(col))
        return total_len

    def _record_operation(self, op: Operation, duration_ns: int) -> None:
        with self._metrics_lock:
            self._op_counts[op] += 1
            self._op_durations_ns[op] += duration_ns

    def _execute_read(self, database: Database) -> None:
        start = time.perf_counter_ns()
        try:
            key = (
                build_key_name(
                    self.skewed_latest_generator.next_value(), self.zero_padding
                )
                if self.workload == Workload.D and not self.is_mock
                else self._get_random_key()
            )

            found = False
            with database.snapshot() as snapshot:
                if self.use_read_row:
                    results = snapshot.read(
                        table=self.table_name,
                        columns=self.field_names,
                        keyset=spanner.KeySet(keys=[[key]]),
                    )
                else:
                    results = snapshot.execute_sql(
                        self.read_sql,
                        params={"id": key},
                        param_types={"id": spanner.param_types.STRING},
                    )
                for row in results:
                    found = True
                    self._consume_row(row)

            if not found:
                raise RuntimeError(f"Row not found for key: {key}")
        finally:
            duration_ns = time.perf_counter_ns() - start
            self._record_operation(Operation.READ, duration_ns)

    def _execute_update(self, database: Database) -> None:
        start = time.perf_counter_ns()
        try:
            key = self._get_random_key()
            field_idx = random.randint(0, self.field_count - 1)
            field_name = self.field_names[field_idx]
            val = generate_random_string(self.field_length)

            with database.batch() as batch:
                batch.insert_or_update(
                    table=self.table_name,
                    columns=["id", field_name],
                    values=[[key, val]],
                )
        finally:
            duration_ns = time.perf_counter_ns() - start
            self._record_operation(Operation.UPDATE, duration_ns)

    def _execute_insert(self, database: Database) -> None:
        start = time.perf_counter_ns()
        try:
            if self.is_mock:
                record_number = 0
            else:
                with self._insert_lock:
                    record_number = self._insert_key_sequence
                    self._insert_key_sequence += 1

            key = build_key_name(record_number, self.zero_padding)
            values = [key] + [
                generate_random_string(self.field_length)
                for _ in range(self.field_count)
            ]

            with database.batch() as batch:
                batch.insert_or_update(
                    table=self.table_name,
                    columns=self.insert_col_names,
                    values=[values],
                )
        finally:
            duration_ns = time.perf_counter_ns() - start
            self._record_operation(Operation.INSERT, duration_ns)

    def _execute_scan(self, database: Database) -> None:
        start = time.perf_counter_ns()
        try:
            start_key = self._get_random_key()
            scan_length = 10 if self.is_mock else random.randint(1, 100)

            with database.snapshot() as snapshot:
                if self.use_read_row:
                    results = snapshot.read(
                        table=self.table_name,
                        columns=self.field_names,
                        keyset=spanner.KeySet(
                            ranges=[spanner.KeyRange(start_closed=[start_key])]
                        ),
                        limit=scan_length,
                    )
                else:
                    results = snapshot.execute_sql(
                        self.scan_sql,
                        params={"startKey": start_key, "scanLength": scan_length},
                        param_types={
                            "startKey": spanner.param_types.STRING,
                            "scanLength": spanner.param_types.INT64,
                        },
                    )
                for row in results:
                    self._consume_row(row)
        finally:
            duration_ns = time.perf_counter_ns() - start
            self._record_operation(Operation.SCAN, duration_ns)

    def _execute_rmw(self, database: Database) -> None:
        start = time.perf_counter_ns()
        try:
            key = self._get_random_key()
            field_idx = random.randint(0, self.field_count - 1)
            field_name = self.field_names[field_idx]
            val = generate_random_string(self.field_length)

            def run_rmw_transaction(transaction):
                results = transaction.read(
                    table=self.table_name,
                    columns=self.field_names,
                    keyset=spanner.KeySet(keys=[[key]]),
                )
                found = False
                for row in results:
                    found = True
                    self._consume_row(row)

                if not found:
                    raise RuntimeError(f"Row not found for key: {key}")

                transaction.insert_or_update(
                    table=self.table_name,
                    columns=["id", field_name],
                    values=[[key, val]],
                )

            database.run_in_transaction(run_rmw_transaction)
        finally:
            duration_ns = time.perf_counter_ns() - start
            self._record_operation(Operation.READ_MODIFY_WRITE, duration_ns)

    def execute_operation(
        self, database: Database, table_name: str, min_id: int, max_id: int
    ) -> None:
        op = self.workload.next_operation()
        if op == Operation.READ:
            self._execute_read(database)
        elif op == Operation.UPDATE:
            self._execute_update(database)
        elif op == Operation.INSERT:
            self._execute_insert(database)
        elif op == Operation.SCAN:
            self._execute_scan(database)
        elif op == Operation.READ_MODIFY_WRITE:
            self._execute_rmw(database)

    def print_operation_summary(self) -> None:
        """Prints breakdown of operations and average latencies matching other SDK runners."""
        with self._metrics_lock:
            for op in [
                Operation.READ,
                Operation.UPDATE,
                Operation.INSERT,
                Operation.SCAN,
                Operation.READ_MODIFY_WRITE,
            ]:
                count = self._op_counts[op]
                if count > 0:
                    avg_latency_ms = (self._op_durations_ns[op] / count) / 1_000_000.0
                    label = f"  [{op.value}]".ljust(11)
                    print(
                        f"{label}Count: {count:,} ops, Avg Latency: {avg_latency_ms:.2f} ms"
                    )
        sys.stdout.flush()
