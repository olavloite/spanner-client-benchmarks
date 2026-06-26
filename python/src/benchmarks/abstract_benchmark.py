import abc
import math
import os
import random
import socket
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from enum import Enum
from typing import Optional

from google.cloud.spanner_v1.database import Database
from opentelemetry.metrics import Counter, Histogram


class LoadType(str, Enum):
    STEADY = "steady"
    SPIKY = "spiky"
    GRADUAL = "gradual"
    CLOSED_LOOP = "closed-loop"


class ReservoirSampler:
    """Thread-safe Reservoir Sampler for tracking latency statistics."""

    def __init__(self, limit: int = 20000):
        self.limit = limit
        self.samples = []
        self.count = 0
        self.lock = threading.Lock()

    def add(self, val: float):
        with self.lock:
            self.count += 1
            if len(self.samples) < self.limit:
                self.samples.append(val)
            else:
                idx = random.randint(0, self.count - 1)
                if idx < self.limit:
                    self.samples[idx] = val

    def get_stats(self) -> dict:
        with self.lock:
            if not self.samples:
                return {}
            sorted_samples = sorted(self.samples)
            n = len(sorted_samples)
            total = sum(sorted_samples)
            avg = total / n

            def get_percentile(p: float) -> float:
                idx = int(math.ceil((p / 100.0) * n)) - 1
                idx = max(0, min(n - 1, idx))
                return sorted_samples[idx]

            return {
                "min": sorted_samples[0],
                "max": sorted_samples[-1],
                "avg": avg,
                "p50": get_percentile(50.0),
                "p90": get_percentile(90.0),
                "p95": get_percentile(95.0),
                "p99": get_percentile(99.0),
                "count": self.count,
            }


class AbstractBenchmark(abc.ABC):
    """
    Abstract base class for all Python client benchmarks.
    Implements a high-precision multi-threaded adaptive Poisson process scheduler.
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
        table_name: str,
        min_id: int,
        max_id: int,
        tps: float,
        threads: int,
        duration_sec: Optional[float],
        for_alerting: bool,
        benchmark_name: str = "",
        load_type: LoadType = LoadType.STEADY,
        cycle_duration_sec: Optional[float] = None,
        peak_factor: float = 2.0,
        burst_factor: float = 1.0,
        burst_duration: float = 1.0,
        burst_fraction: float = 0.1,
        is_mock: bool = False,
    ):
        self.database = database
        self.latency_histogram = latency_histogram
        self.operation_counter = operation_counter
        self.error_counter = error_counter
        self.memory_usage_histogram = memory_usage_histogram
        self.cpu_utilization_histogram = cpu_utilization_histogram
        self.resource_probe_interval_str = resource_probe_interval_str
        self.table_name = table_name
        self.min_id = min_id
        self.max_id = max_id
        self.tps = tps
        self.threads = threads
        self.duration_sec = duration_sec
        self.for_alerting = for_alerting
        self.load_type = load_type
        self.cycle_duration_sec = cycle_duration_sec
        self.peak_factor = peak_factor
        self.burst_factor = burst_factor
        self.burst_duration = burst_duration
        self.burst_fraction = burst_fraction
        self.is_mock = is_mock

        self.r_burst = self.tps * self.burst_factor
        self.r_normal = (self.tps - self.burst_fraction * self.r_burst) / (
            1.0 - self.burst_fraction
        )

        benchmark_type = self.get_benchmark_type()
        if self.is_mock:
            benchmark_type = f"{benchmark_type}-mock"

        # Pre-create metric attributes to optimize away overhead on the hot path
        self.attributes = {
            "benchmark_type": benchmark_type,
            "tps": self.tps,
            "for_alerting": str(self.for_alerting).lower(),
            "benchmark_name": benchmark_name,
            "client": "python-client",
            "load_type": self.load_type,
            "burst_factor": self.burst_factor,
            "burst_duration": self.burst_duration,
            "burst_fraction": self.burst_fraction,
            "cycle_duration_ms": (self.cycle_duration_sec * 1000)
            if self.cycle_duration_sec
            else 0,
            "peak_factor": self.peak_factor,
            "transaction_type": "none",
        }

        self.is_stopped = False
        self._outstanding_tasks = 0
        self._last_queue_log_time = 0.0
        self._success_count = 0
        self._error_count = 0
        self._latency_sampler = ReservoirSampler(limit=20000)
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=threads)
        self._generator_thread: Optional[threading.Thread] = None
        self._socket: Optional[socket.socket] = None

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
        print(
            f"Parameters: TPS={self.tps}, Max Workers={self.threads}, MinID={self.min_id}, MaxID={self.max_id}"
        )

        self.is_stopped = False
        if self.load_type == LoadType.CLOSED_LOOP:
            print(f"Running in closed-loop mode with {self.threads} client threads.")
            for _ in range(self.threads):
                self._executor.submit(self._closed_loop_worker)
        else:
            socket_path = os.environ.get("SPANNER_BENCHMARK_SOCKET")
            if socket_path:
                self._generator_thread = threading.Thread(
                    target=self._socket_triggered_generator,
                    args=(socket_path,),
                    name="Socket-WorkloadGenerator",
                    daemon=True,
                )
            else:
                self._generator_thread = threading.Thread(
                    target=self._workload_generator,
                    name="TPS-WorkloadGenerator",
                    daemon=True,
                )
            self._generator_thread.start()
            self._start_resource_monitoring()

        # Wait loop for duration expiration
        # TODO: Consider refactoring this busy-polling wait loop to use threading.Event().wait(duration_sec)
        # for instant wakeup handling upon graceful termination without loop sleeping overhead.
        start_wait = time.perf_counter()
        try:
            if self.duration_sec is not None:
                while (
                    time.perf_counter() - start_wait < self.duration_sec
                    and not self.is_stopped
                ):
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

        # Cleanly shutdown the executor and cancel any queued futures to release threads.
        try:
            self._executor.shutdown(wait=True, cancel_futures=True)
        except TypeError:
            # Fallback for Python < 3.9
            self._executor.shutdown(wait=True)

        # Print final benchmark summary statistics
        with self._lock:
            success_count = self._success_count
            error_count = self._error_count
            total_ops = success_count + error_count
            elapsed_sec = time.perf_counter() - start_wait
            actual_tps = total_ops / elapsed_sec if elapsed_sec > 0 else 0.0

        stats = self._latency_sampler.get_stats()

        print("\n" + "=" * 60)
        print("                  BENCHMARK RUN SUMMARY")
        print("=" * 60)
        print(f"Benchmark:       {self.get_benchmark_name()}")
        print(f"Total Ops:       {total_ops}")
        print(
            f"Success Ops:     {success_count} ({100.0 * success_count / total_ops:.1f}%)"
            if total_ops > 0
            else f"Success Ops:     {success_count}"
        )
        print(
            f"Error Ops:       {error_count} ({100.0 * error_count / total_ops:.1f}%)"
            if total_ops > 0
            else f"Error Ops:       {error_count}"
        )
        print(f"Duration:        {elapsed_sec:.2f} s")
        print(f"Actual TPS:      {actual_tps:.2f} tps")
        print("-" * 60)
        if stats:
            print("Latency (microseconds):")
            print(f"  Average:       {stats['avg']:.2f} us")
            print(f"  Min:           {stats['min']:.2f} us")
            print(f"  P50 (Median):  {stats['p50']:.2f} us")
            print(f"  P90:           {stats['p90']:.2f} us")
            print(f"  P95:           {stats['p95']:.2f} us")
            print(f"  P99:           {stats['p99']:.2f} us")
            print(f"  Max:           {stats['max']:.2f} us")
        else:
            print("No latency statistics collected.")
        print("=" * 60 + "\n")

        # For Cloud Run deployment, call os._exit(0) directly unless mocked in tests.
        import sys

        sys.stdout.flush()
        sys.stderr.flush()
        os._exit(0)

    def stop(self) -> None:
        """Gracefully instructs the workload generator to cease spawning new operations."""
        self.is_stopped = True
        if self._socket:
            try:
                self._socket.close()
            except Exception:
                pass

    def _socket_triggered_generator(self, socket_path: str) -> None:
        """
        Unix Domain Socket client listener that reads triggers from sidecar.
        """
        print(f"Connecting to workload generator sidecar socket: {socket_path}")
        try:
            self._socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self._socket.connect(socket_path)
            self._socket.sendall(b"READY\n")
        except Exception as e:
            print(f"Failed to connect to sidecar socket: {e}", file=sys.stderr)
            os._exit(1)

        try:
            while not self.is_stopped:
                data = self._socket.recv(1024)
                if not data:
                    print("Workload generator socket connection closed by server.")
                    break
                for byte in data:
                    if byte == 0x01:
                        self._submit_task()
        except Exception as e:
            if not self.is_stopped:
                print(f"Socket reader loop error: {e}", file=sys.stderr)
                os._exit(1)
        finally:
            self.stop()

    def _workload_generator(self) -> None:
        """
        High-precision Poisson arrival thread generator loop.
        """
        start_time_ns = time.perf_counter()
        next_task_time_ns = start_time_ns

        mu2 = 1.0 / self.burst_duration
        mu1 = mu2 * self.burst_fraction / (1.0 - self.burst_fraction)

        in_burst = False
        next_state_change_time_ns = start_time_ns + self._calculate_poisson_delay(mu1)

        while not self.is_stopped:
            now_ns = time.perf_counter()

            # Self-healing snap: if scheduler falls behind by more than 1.0 second,
            # snap the timeline forward to avoid heavy backlogs and out-of-memory issues.
            if now_ns - next_task_time_ns > 1.0:
                next_task_time_ns = now_ns

            if self.load_type == LoadType.SPIKY:
                if now_ns >= next_state_change_time_ns:
                    in_burst = not in_burst
                    next_delay_sec = (
                        self._calculate_poisson_delay(mu2)
                        if in_burst
                        else self._calculate_poisson_delay(mu1)
                    )
                    next_state_change_time_ns = now_ns + next_delay_sec

            current_rate = self._calculate_current_rate(now_ns, start_time_ns, in_burst)

            # Spawn all tasks scheduled to run in the current delta window
            while now_ns >= next_task_time_ns and not self.is_stopped:
                self._submit_task()

                # Calculate next arrival delay using exponential inter-arrival distribution
                delay_sec = self._calculate_poisson_delay(current_rate)

                if self.load_type == LoadType.SPIKY:
                    time_to_state_change_sec = (
                        next_state_change_time_ns - next_task_time_ns
                    )
                    if delay_sec > time_to_state_change_sec:
                        next_task_time_ns = next_state_change_time_ns
                        break

                next_task_time_ns += delay_sec

            # Sleep to yield to other threads, sleeping longer if the next task is far in the future
            if not self.is_stopped:
                next_now_ns = time.perf_counter()
                remaining_sec = next_task_time_ns - next_now_ns
                if remaining_sec > 0.001:  # More than 1ms remaining
                    time.sleep(remaining_sec)
                else:
                    time.sleep(0.0001)

    def _submit_task(self) -> None:
        """Checks concurrency thresholds and dispatches task to thread executor pool."""
        with self._lock:
            queue_size = self._outstanding_tasks - self.threads
            if queue_size > 0:
                now = time.time()
                if now - self._last_queue_log_time > 1.0:
                    print(
                        f"Queue size: {queue_size} (concurrency limit reached, tasks are queueing)",
                        file=sys.stderr,
                    )
                    self._last_queue_log_time = now

            if self._outstanding_tasks < 1000000 + self.threads:
                self._outstanding_tasks += 1
                self._executor.submit(self._run_task)
            else:
                # Dropping tasks to simulate unbounded network backlog limiters (parity with Go's 1M cap)
                print(
                    "Task dropped: workload queue is full (1M tasks exceeded)",
                    file=sys.stderr,
                )

    def should_measure_entire_method(self) -> bool:
        return True

    def get_attributes(self) -> dict:
        return self.attributes

    def _run_task(self) -> None:
        """Executes the concrete Spanner scenario, measures latency in microseconds, records to metrics."""
        start_time = time.perf_counter()
        success = False
        try:
            self.execute_operation(
                self.database, self.table_name, self.min_id, self.max_id
            )
            success = True
        except Exception as err:
            print(f"Operation failed: {err}", file=sys.stderr)
            self.error_counter.add(1, self.attributes)
            with self._lock:
                self._error_count += 1
        finally:
            end_time = time.perf_counter()
            if self.should_measure_entire_method() and success:
                latency_us = (end_time - start_time) * 1000000.0
                self.latency_histogram.record(latency_us, self.attributes)
                self._latency_sampler.add(latency_us)
            if success:
                with self._lock:
                    self._success_count += 1
            self.operation_counter.add(1, self.attributes)
            if self.load_type != LoadType.CLOSED_LOOP:
                with self._lock:
                    self._outstanding_tasks -= 1

    def _closed_loop_worker(self) -> None:
        """Continuously executes point-select operations in a loop."""
        while not self.is_stopped:
            self._run_task()

    def _calculate_poisson_delay(self, rate: float) -> float:
        """
        Calculates next Poisson arrival interval delay in seconds.
        Formula: delaySeconds = -ln(1.0 - u) / rate, where u ~ Uniform(0, 1)
        """
        if rate <= 0:
            return 3600.0  # 1 hour in seconds
        u = random.random()
        # Guard to prevent log(0) -> -Infinity error if u is exactly 1.0
        safe_u = 0.999999999 if u == 1.0 else u
        return -math.log(1.0 - safe_u) / rate

    def _calculate_current_rate(
        self, now_sec: float, start_time_sec: float, in_burst: bool
    ) -> float:
        if self.load_type == LoadType.SPIKY:
            return self.r_burst if in_burst else self.r_normal
        elif self.load_type == LoadType.GRADUAL:
            elapsed_sec = now_sec - start_time_sec
            cycle_duration_sec = self.cycle_duration_sec or 3600.0
            amplitude = self.tps * (self.peak_factor - 1.0)
            angle = (
                2.0 * math.pi * (elapsed_sec % cycle_duration_sec)
            ) / cycle_duration_sec
            return self.tps + amplitude * math.cos(angle - math.pi)
        return self.tps

    def _start_resource_monitoring(self) -> None:
        from src.utils.resources import ResourceMonitor

        self._resource_monitor = ResourceMonitor(
            probe_interval_str=self.resource_probe_interval_str,
            memory_usage_histogram=self.memory_usage_histogram,
            cpu_utilization_histogram=self.cpu_utilization_histogram,
            attributes=self.attributes,
            is_stopped_check=lambda: self.is_stopped,
        )
        self._resource_monitor.start()
