import random
import sys
import time
from concurrent.futures import ThreadPoolExecutor

from google.cloud.spanner_v1.database import Database
from opentelemetry.metrics import Counter, Histogram

from .transactions import (
    execute_delivery,
    execute_new_order,
    execute_new_order_mutations,
    execute_order_status,
    execute_order_status_reads,
    execute_payment,
    execute_payment_mutations_direct,
    execute_stock_level,
    execute_stock_level_partitioned,
)


class TpccBenchmarkRunner:
    def __init__(
        self,
        database: Database,
        latency_histogram: Histogram,
        operation_counter: Counter,
        error_counter: Counter,
        memory_usage_histogram: Histogram,
        cpu_utilization_histogram: Histogram,
        resource_probe_interval_str: str,
        scale_factor: int,
        clients: int,
        items: int,
        duration_sec: float,
        for_alerting: bool,
        benchmark_name: str,
        extended: bool = False,
    ):
        self.database = database
        self.latency_histogram = latency_histogram
        self.operation_counter = operation_counter
        self.error_counter = error_counter
        self.memory_usage_histogram = memory_usage_histogram
        self.cpu_utilization_histogram = cpu_utilization_histogram
        self.resource_probe_interval_str = resource_probe_interval_str
        self.scale_factor = scale_factor
        self.clients = clients
        self.items = items
        self.duration_sec = duration_sec
        self.for_alerting = for_alerting
        self.benchmark_name = benchmark_name
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
        self.attr_new_order = dict(self.base_attributes, transaction_type="new_order")
        self.attr_new_order_mutations = dict(
            self.base_attributes, transaction_type="new_order_mutations"
        )
        self.attr_payment = dict(self.base_attributes, transaction_type="payment")
        self.attr_payment_mutations_direct = dict(
            self.base_attributes, transaction_type="payment_mutations_direct"
        )
        self.attr_order_status = dict(
            self.base_attributes, transaction_type="order_status"
        )
        self.attr_order_status_reads = dict(
            self.base_attributes, transaction_type="order_status_reads"
        )
        self.attr_delivery = dict(self.base_attributes, transaction_type="delivery")
        self.attr_stock_level = dict(
            self.base_attributes, transaction_type="stock_level"
        )
        self.attr_stock_level_partitioned = dict(
            self.base_attributes, transaction_type="stock_level_partitioned"
        )
        self.is_stopped = False

    def _start_resource_monitoring(self) -> None:
        from src.utils.resources import ResourceMonitor

        self._resource_monitor = ResourceMonitor(
            probe_interval_str=self.resource_probe_interval_str,
            memory_usage_histogram=self.memory_usage_histogram,
            cpu_utilization_histogram=self.cpu_utilization_histogram,
            attributes=self.base_attributes,
            is_stopped_check=lambda: self.is_stopped,
        )
        self._resource_monitor.start()

    def run(self) -> None:
        extended_str = " [EXTENDED MODE]" if self.extended else ""
        print(
            f"Starting TPC-C Benchmark with Scale Factor (Warehouses): {self.scale_factor}, Parallel Clients: {self.clients}, Items: {self.items}{extended_str}"
        )

        self._start_resource_monitoring()

        # Assert database capacity
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

        executor = ThreadPoolExecutor(max_workers=self.clients)
        start_time = time.perf_counter()

        def worker_loop():
            while not self.is_stopped:
                if (
                    self.duration_sec is not None
                    and (time.perf_counter() - start_time) >= self.duration_sec
                ):
                    break

                prob = random.randint(0, 99)
                tx_type = "new_order"
                attr = self.attr_new_order
                op_start = time.perf_counter()
                success = False

                try:
                    if self.extended:
                        if prob < 25:
                            tx_type = "new_order"
                            attr = self.attr_new_order
                            execute_new_order(
                                self.database,
                                self.scale_factor,
                                self.items,
                                extended=True,
                            )
                        elif prob < 45:
                            tx_type = "new_order_mutations"
                            attr = self.attr_new_order_mutations
                            execute_new_order_mutations(
                                self.database, self.scale_factor, self.items
                            )
                        elif prob < 78:
                            tx_type = "payment"
                            attr = self.attr_payment
                            execute_payment(
                                self.database, self.scale_factor, extended=True
                            )
                        elif prob < 88:
                            tx_type = "payment_mutations_direct"
                            attr = self.attr_payment_mutations_direct
                            execute_payment_mutations_direct(
                                self.database, self.scale_factor
                            )
                        elif prob < 90:
                            tx_type = "order_status"
                            attr = self.attr_order_status
                            execute_order_status(
                                self.database, self.scale_factor, extended=True
                            )
                        elif prob < 92:
                            tx_type = "order_status_reads"
                            attr = self.attr_order_status_reads
                            execute_order_status_reads(self.database, self.scale_factor)
                        elif prob < 96:
                            tx_type = "delivery"
                            attr = self.attr_delivery
                            execute_delivery(
                                self.database, self.scale_factor, extended=True
                            )
                        elif prob < 98:
                            tx_type = "stock_level"
                            attr = self.attr_stock_level
                            execute_stock_level(
                                self.database, self.scale_factor, extended=True
                            )
                        else:
                            tx_type = "stock_level_partitioned"
                            attr = self.attr_stock_level_partitioned
                            execute_stock_level_partitioned(
                                self.database, self.scale_factor
                            )
                    else:
                        if prob < 45:
                            tx_type = "new_order"
                            attr = self.attr_new_order
                            execute_new_order(
                                self.database,
                                self.scale_factor,
                                self.items,
                                extended=False,
                            )
                        elif prob < 88:
                            tx_type = "payment"
                            attr = self.attr_payment
                            execute_payment(
                                self.database, self.scale_factor, extended=False
                            )
                        elif prob < 92:
                            tx_type = "order_status"
                            attr = self.attr_order_status
                            execute_order_status(
                                self.database, self.scale_factor, extended=False
                            )
                        elif prob < 96:
                            tx_type = "delivery"
                            attr = self.attr_delivery
                            execute_delivery(
                                self.database, self.scale_factor, extended=False
                            )
                        else:
                            tx_type = "stock_level"
                            attr = self.attr_stock_level
                            execute_stock_level(
                                self.database, self.scale_factor, extended=False
                            )
                    success = True
                except Exception as err:
                    print(f"TPC-C transaction {tx_type} failed: {err}", file=sys.stderr)
                    self.error_counter.add(1, attr)
                finally:
                    if success:
                        latency_us = (time.perf_counter() - op_start) * 1000000.0
                        self.latency_histogram.record(latency_us, attr)
                    self.operation_counter.add(1, attr)

        for _ in range(self.clients):
            executor.submit(worker_loop)

        # TODO: Consider refactoring this busy-polling wait loop to use threading.Event().wait(duration_sec)
        # for instant wakeup handling upon graceful termination without loop sleeping overhead.
        try:
            if self.duration_sec is not None:
                while (
                    time.perf_counter() - start_time < self.duration_sec
                    and not self.is_stopped
                ):
                    time.sleep(0.5)
            else:
                while not self.is_stopped:
                    time.sleep(0.5)
        except KeyboardInterrupt:
            self.stop()

        self.stop()
        executor.shutdown(wait=False)
        print("TPC-C benchmark execution complete.")

    def stop(self) -> None:
        self.is_stopped = True
