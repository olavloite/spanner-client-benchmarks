import sys
import time
from typing import Optional

from google.cloud.spanner_v1.database import Database
from opentelemetry.metrics import Counter, Histogram

from src.benchmarks.abstract_benchmark import AbstractBenchmark, LoadType

from .transactions import execute_tpcc_transaction


class TpccBenchmarkRunner(AbstractBenchmark):
    def __init__(
        self,
        database: Optional[Database] = None,
        latency_histogram: Optional[Histogram] = None,
        operation_counter: Optional[Counter] = None,
        error_counter: Optional[Counter] = None,
        memory_usage_histogram: Optional[Histogram] = None,
        cpu_utilization_histogram: Optional[Histogram] = None,
        resource_probe_interval_str: str = "10s",
        scale_factor: int = 1,
        clients: int = 10,
        items: int = 100000,
        duration_sec: Optional[float] = None,
        for_alerting: bool = False,
        benchmark_name: str = "",
        extended: bool = False,
        workers: int = 1,
        host: Optional[str] = None,
        project_id: Optional[str] = None,
        instance_id: Optional[str] = None,
        database_id: Optional[str] = None,
    ):
        super().__init__(
            database=database,
            latency_histogram=latency_histogram,
            operation_counter=operation_counter,
            error_counter=error_counter,
            memory_usage_histogram=memory_usage_histogram,
            cpu_utilization_histogram=cpu_utilization_histogram,
            resource_probe_interval_str=resource_probe_interval_str,
            table_name="warehouse",
            min_id=1,
            max_id=scale_factor,
            tps=0.0,
            threads=clients,
            duration_sec=duration_sec,
            for_alerting=for_alerting,
            benchmark_name=benchmark_name,
            load_type=LoadType.CLOSED_LOOP,
            workers=workers,
            host=host,
            project_id=project_id,
            instance_id=instance_id,
            database_id=database_id,
        )
        self.scale_factor = scale_factor
        self.clients = clients
        self.items = items
        self.extended = extended

        self.base_attributes = {
            "benchmark_type": "tpcc",
            "for_alerting": str(self.for_alerting).lower(),
            "benchmark_name": self.benchmark_name,
            "client": "python-client",
            "concurrent_clients": self.clients,
        }
        if self.extended:
            self.base_attributes["extended"] = "true"

        self.attributes.clear()
        self.attributes.update(self.base_attributes)

        self.tx_attributes = {
            "new_order": dict(self.base_attributes, transaction_type="new_order"),
            "new_order_mutations": dict(
                self.base_attributes, transaction_type="new_order_mutations"
            ),
            "payment": dict(self.base_attributes, transaction_type="payment"),
            "payment_mutations_direct": dict(
                self.base_attributes, transaction_type="payment_mutations_direct"
            ),
            "order_status": dict(self.base_attributes, transaction_type="order_status"),
            "order_status_reads": dict(
                self.base_attributes, transaction_type="order_status_reads"
            ),
            "delivery": dict(self.base_attributes, transaction_type="delivery"),
            "stock_level": dict(self.base_attributes, transaction_type="stock_level"),
            "stock_level_partitioned": dict(
                self.base_attributes, transaction_type="stock_level_partitioned"
            ),
        }

    def get_benchmark_name(self) -> str:
        return "TPC-C Benchmark"

    def get_benchmark_type(self) -> str:
        return "tpcc"

    def should_measure_entire_method(self) -> bool:
        return False

    def get_attributes_for_task(self, msg: Optional[dict] = None) -> dict:
        if msg and msg.get("tx_type") in self.tx_attributes:
            return self.tx_attributes[msg["tx_type"]]
        return self.base_attributes

    def _get_worker_data(self, worker_id: int, concurrency: int):
        data = super()._get_worker_data(worker_id, concurrency)
        data.scale_factor = self.scale_factor
        data.items = self.items
        data.extended = self.extended
        return data

    def execute_operation(
        self, database: Database, table_name: str, min_id: int, max_id: int
    ) -> None:
        op_start = time.perf_counter()
        tx_type = "new_order"
        attr = self.tx_attributes["new_order"]
        success = False
        try:
            tx_type = execute_tpcc_transaction(
                database, self.scale_factor, self.items, self.extended
            )
            attr = self.tx_attributes.get(tx_type, self.base_attributes)
            success = True
        except Exception as err:
            tx_type = getattr(err, "tx_type", tx_type)
            attr = self.tx_attributes.get(tx_type, self.base_attributes)
            print(f"TPC-C transaction {tx_type} failed: {err}", file=sys.stderr)
            self.error_counter.add(1, attr)
            with self._lock:
                self._error_count += 1
        finally:
            if success:
                latency_us = (time.perf_counter() - op_start) * 1000000.0
                self.latency_histogram.record(latency_us, attr)
                self._latency_sampler.add(latency_us)
                with self._lock:
                    self._success_count += 1
            self.operation_counter.add(1, attr)

    def run(self) -> None:
        extended_str = " [EXTENDED MODE]" if self.extended else ""
        worker_info = f", Worker Processes={self.workers}" if self.workers > 1 else ""
        print(
            f"Starting TPC-C Benchmark with Scale Factor (Warehouses): {self.scale_factor}, Parallel Clients: {self.clients}, Items: {self.items}{extended_str}{worker_info}"
        )

        # Assert database capacity if running in-process
        if self.database is not None:
            with self.database.snapshot(multi_use=False) as snapshot:
                results = snapshot.execute_sql("SELECT COUNT(*) FROM warehouse")
                warehouse_count = 0
                for row in results:
                    warehouse_count = row[0]
                    break
                if warehouse_count < self.scale_factor:
                    print(
                        f"Error: Database capacity check failed: Required scale factor {self.scale_factor} warehouses, but database only has {warehouse_count}",
                        file=sys.stderr,
                    )
                    sys.exit(1)

        super().run()
