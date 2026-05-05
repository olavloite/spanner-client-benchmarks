import abc
import math
import random
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Optional
from google.cloud import spanner
from google.cloud.spanner_v1.database import Database
from opentelemetry.metrics import Histogram

class AbstractBenchmark(abc.ABC):
    """
    Abstract base class for all Python client benchmarks.
    Implements a high-precision multi-threaded adaptive Poisson process scheduler.
    """

    def __init__(
        self,
        database: Database,
        latency_histogram: Histogram,
        table_name: str,
        min_id: int,
        max_id: int,
        tps: float,
        threads: int,
        duration_sec: Optional[float],
        for_alerting: bool,
    ):
        self.database = database
        self.latency_histogram = latency_histogram
        self.table_name = table_name
        self.min_id = min_id
        self.max_id = max_id
        self.tps = tps
        self.threads = threads
        self.duration_sec = duration_sec
        self.for_alerting = for_alerting

        # Pre-create metric attributes to optimize away overhead on the hot path
        self.attributes = {
            "benchmark_type": self.get_benchmark_type(),
            "tps": self.tps,
            "for_alerting": self.for_alerting,
            "client": "python-client",
        }

        self.is_stopped = False
        self._outstanding_tasks = 0
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=threads)
        self._generator_thread: Optional[threading.Thread] = None

    @abc.abstractmethod
    def execute_operation(
        self, database: Database, table_name: str, min_id: int, max_id: int
    ) -> None:
        """Performs the actual Spanner read or write workload statement operation."""
        pass

    @abc.abstractmethod
    def get_benchmark_name(self) -> str:
        """Returns human readable descriptor name."""
        pass

    @abc.abstractmethod
    def get_benchmark_type(self) -> str:
        """Returns alphanumeric identifier type (e.g. point-select)."""
        pass

    def run(self) -> None:
        """
        Spawns the background ticker thread and blocks until duration completes or stopped.
        """
        print(f"Starting {self.get_benchmark_name()}")
        print(f"Parameters: TPS={self.tps}, Max Workers={self.threads}, MinID={self.min_id}, MaxID={self.max_id}")

        self.is_stopped = False
        self._generator_thread = threading.Thread(
            target=self._workload_generator, name="TPS-WorkloadGenerator", daemon=True
        )
        self._generator_thread.start()

        # Wait loop for duration expiration
        start_wait = time.perf_counter()
        try:
            if self.duration_sec is not None:
                while time.perf_counter() - start_wait < self.duration_sec and not self.is_stopped:
                    time.sleep(0.1)
                print("Benchmark duration reached. Stopping workload generator...")
                self.stop()
            else:
                # Run infinitely (block thread) until interrupted or stopped
                while not self.is_stopped:
                    time.sleep(0.5)
        except KeyboardInterrupt:
            print("Benchmark interrupted by user keyboard event.")
            self.stop()

        # Block and wait for the ThreadPoolExecutor to drain and active workers to settle
        self._executor.shutdown(wait=True)
        print("ThreadPoolExecutor drained and shut down successfully. Benchmark run finished.")

    def stop(self) -> None:
        """Gracefully instructs the workload generator to cease spawning new operations."""
        self.is_stopped = True

    def _workload_generator(self) -> None:
        """
        High-precision Poisson arrival thread generator loop.
        """
        start_time_ns = time.perf_counter()
        next_task_time_ns = start_time_ns

        while not self.is_stopped:
            now_ns = time.perf_counter()

            # Self-healing snap: if scheduler falls behind by more than 1.0 second,
            # snap the timeline forward to avoid heavy backlogs and out-of-memory issues.
            if now_ns - next_task_time_ns > 1.0:
                next_task_time_ns = now_ns

            # Spawn all tasks scheduled to run in the current delta window
            while now_ns >= next_task_time_ns and not self.is_stopped:
                self._submit_task()

                # Calculate next arrival delay using exponential inter-arrival distribution
                delay_sec = self._calculate_poisson_delay(self.tps)
                next_task_time_ns += delay_sec

            # Sleep microsecond slice to yield interpretation time back to other worker threads
            time.sleep(0.0001)

    def _submit_task(self) -> None:
        """Checks concurrency thresholds and dispatches task to thread executor pool."""
        with self._lock:
            if self._outstanding_tasks < 1000000 + self.threads:
                self._outstanding_tasks += 1
                self._executor.submit(self._run_task)
            else:
                # Dropping tasks to simulate unbounded network backlog limiters (parity with Go's 1M cap)
                print("Task dropped: workload queue is full (1M tasks exceeded)", file=sys.stderr)

    def _run_task(self) -> None:
        """Executes the concrete Spanner scenario, measures latency in microseconds, records to metrics."""
        start_time = time.perf_counter()
        try:
            self.execute_operation(self.database, self.table_name, self.min_id, self.max_id)
            end_time = time.perf_counter()

            # Record latency in microseconds (us) matching Go, Java, and Node parity specifications
            latency_us = (end_time - start_time) * 1000000.0
            self.latency_histogram.record(latency_us, self.attributes)
        except Exception as err:
            print(f"Operation failed: {err}", file=sys.stderr)
        finally:
            with self._lock:
                self._outstanding_tasks -= 1

    def _calculate_poisson_delay(self, rate: float) -> float:
        """
        Calculates next Poisson arrival interval delay in seconds.
        Formula: delaySeconds = -ln(1.0 - u) / rate, where u ~ Uniform(0, 1)
        """
        u = random.random()
        # Guard to prevent log(0) -> -Infinity error if u is exactly 1.0
        safe_u = 0.999999999 if u == 1.0 else u
        return -math.log(1.0 - safe_u) / rate
