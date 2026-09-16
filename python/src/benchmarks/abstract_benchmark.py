import abc
import collections
import math
import multiprocessing
import os
import random
import selectors
import socket
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from enum import Enum
from multiprocessing.connection import Connection
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
        workers: int = 1,
        host: Optional[str] = None,
        project_id: Optional[str] = None,
        instance_id: Optional[str] = None,
        database_id: Optional[str] = None,
    ):
        self.database = database
        self.project_id = (
            project_id
            if project_id
            else (
                getattr(getattr(database, "_instance", None), "_client", None).project
                if database and getattr(database, "_instance", None)
                else None
            )
        )
        self.instance_id = (
            instance_id
            if instance_id
            else (
                getattr(getattr(database, "_instance", None), "instance_id", None)
                if database
                else None
            )
        )
        self.database_id = (
            database_id
            if database_id
            else (getattr(database, "database_id", None) if database else None)
        )
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
        self.benchmark_name = benchmark_name
        self.load_type = load_type
        self.cycle_duration_sec = cycle_duration_sec
        self.peak_factor = peak_factor
        self.burst_factor = burst_factor
        self.burst_duration = burst_duration
        self.burst_fraction = burst_fraction
        self.is_mock = is_mock
        self.workers = max(1, workers)
        self.host = host

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

        # Multi-worker process coordination state
        self._workers: list[multiprocessing.Process] = []
        self._worker_conns: list[Connection] = []
        self._worker_in_flight: list[int] = []
        self._worker_active: list[bool] = []
        self._next_worker_index = 0
        self._next_task_id = 1
        self._task_queue: collections.deque[int] = collections.deque()
        self._selector: Optional[selectors.DefaultSelector] = None
        self._listener_thread: Optional[threading.Thread] = None
        self._workers_cleaned_up = False

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

    def get_attributes_for_task(self, msg: Optional[dict] = None) -> dict:
        """Resolves metric attributes for a completed task. Subclasses may override."""
        return self.get_attributes()

    def get_worker_pids(self) -> list[int]:
        """Returns PIDs of currently alive worker processes for memory/CPU monitoring."""
        return [p.pid for p in self._workers if p.pid and p.is_alive()]

    def _get_worker_data(self, worker_id: int, concurrency: int):
        from src.benchmarks.benchmark_worker import BenchmarkWorkerData

        project_id = self.project_id or ""
        instance_id = self.instance_id or ""
        database_id = self.database_id or ""

        return BenchmarkWorkerData(
            worker_id=worker_id,
            benchmark_type=self.get_benchmark_type(),
            project_id=project_id,
            instance_id=instance_id,
            database_id=database_id,
            host=self.host,
            table_name=self.table_name,
            min_id=self.min_id,
            max_id=self.max_id,
            num_rows=getattr(self, "num_rows", 100000),
            lazy_decode=getattr(self, "lazy_decode", False),
            scale_factor=getattr(self, "scale_factor", 1),
            items=getattr(self, "items", 100000),
            extended=getattr(self, "extended", False),
            concurrency=concurrency,
        )

    def _init_workers(self) -> None:
        from src.benchmarks.benchmark_worker import worker_process_main

        print(f"Initializing {self.workers} worker processes...")
        mp_context = multiprocessing.get_context("spawn")
        worker_concurrency = max(1, math.ceil(self.threads / self.workers))

        for worker_id in range(self.workers):
            parent_conn, child_conn = mp_context.Pipe(duplex=True)
            worker_data = self._get_worker_data(worker_id, worker_concurrency)
            process = mp_context.Process(
                target=worker_process_main,
                args=(worker_id, child_conn, worker_data),
                name=f"SpannerWorker-{worker_id}",
                daemon=True,
            )
            process.start()
            child_conn.close()
            self._workers.append(process)
            self._worker_conns.append(parent_conn)
            self._worker_in_flight.append(0)
            self._worker_active.append(True)

        # Wait for all workers to send READY signal
        for worker_id, conn in enumerate(self._worker_conns):
            ready_received = False
            start_wait = time.time()
            while not ready_received and time.time() - start_wait < 30.0:
                if conn.poll(0.1):
                    try:
                        msg = conn.recv()
                    except (EOFError, OSError):
                        process = self._workers[worker_id]
                        raise RuntimeError(
                            f"Worker {worker_id} process terminated unexpectedly during startup (exitcode={process.exitcode})"
                        )
                    if isinstance(msg, dict) and msg.get("type") == "ready":
                        ready_received = True
                    elif isinstance(msg, dict) and msg.get("type") == "error":
                        raise RuntimeError(
                            f"Worker {worker_id} failed to initialize: {msg.get('error')}"
                        )
            if not ready_received:
                process = self._workers[worker_id]
                raise TimeoutError(
                    f"Timed out waiting for worker {worker_id} to initialize (exitcode={process.exitcode})"
                )

        print(f"All {self.workers} worker processes initialized and ready.")

        # Register all worker parent pipes with DefaultSelector
        self._selector = selectors.DefaultSelector()
        for worker_id, conn in enumerate(self._worker_conns):
            self._selector.register(conn, selectors.EVENT_READ, data=worker_id)

        self._listener_thread = threading.Thread(
            target=self._worker_response_listener,
            name="WorkerResponseListener",
            daemon=True,
        )
        self._listener_thread.start()

    def _worker_response_listener(self) -> None:
        while not self._workers_cleaned_up:
            if not self._selector:
                break
            try:
                events = self._selector.select(timeout=0.1)
            except Exception:
                break
            for key, _ in events:
                worker_id = key.data
                conn = key.fileobj
                try:
                    while conn.poll(0):
                        msg = conn.recv()
                        if isinstance(msg, dict) and msg.get("type") == "completed":
                            self._on_worker_task_completed(worker_id, msg)
                except (EOFError, BrokenPipeError, OSError):
                    try:
                        self._selector.unregister(conn)
                    except Exception:
                        pass
                    in_flight = 0
                    with self._lock:
                        self._worker_active[worker_id] = False
                        in_flight = self._worker_in_flight[worker_id]
                        self._worker_in_flight[worker_id] = float("inf")
                        if in_flight > 0 and in_flight != float("inf"):
                            self._outstanding_tasks = max(
                                0, self._outstanding_tasks - in_flight
                            )
                            self._error_count += in_flight
                    if in_flight > 0 and in_flight != float("inf"):
                        self.error_counter.add(in_flight, self.get_attributes())
                        # In closed-loop mode, replenish the lost tasks to healthy workers
                        if (
                            self.load_type == LoadType.CLOSED_LOOP
                            and not self.is_stopped
                        ):
                            for _ in range(in_flight):
                                self._submit_task_multi_worker()

    def _on_worker_task_completed(self, worker_id: int, msg: dict) -> None:
        with self._lock:
            self._worker_in_flight[worker_id] = max(
                0, self._worker_in_flight[worker_id] - 1
            )
            self._outstanding_tasks = max(0, self._outstanding_tasks - 1)

        attributes = self.get_attributes_for_task(msg)
        success = msg.get("success", False)
        latency_us = msg.get("latency_us")

        if success:
            if latency_us is not None and latency_us > 0:
                self.latency_histogram.record(latency_us, attributes)
                self._latency_sampler.add(latency_us)
            with self._lock:
                self._success_count += 1
            self.operation_counter.add(1, attributes)
        else:
            error_message = msg.get("error", "Unknown error")
            print(f"Operation failed: {error_message}", file=sys.stderr)
            self.error_counter.add(1, attributes)
            self.operation_counter.add(1, attributes)
            with self._lock:
                self._error_count += 1

        if self.load_type == LoadType.CLOSED_LOOP:
            if not self.is_stopped:
                with self._lock:
                    self._dispatch_task_to_worker()
        else:
            with self._lock:
                while (
                    len(self._task_queue) > 0
                    and self._outstanding_tasks < self.threads
                    and not self.is_stopped
                ):
                    self._task_queue.popleft()
                    self._dispatch_task_to_worker()

    def run(self) -> None:
        """
        Spawns the background ticker thread and blocks until duration completes or stopped.
        """
        print(f"Starting {self.get_benchmark_name()}")
        worker_info = f", Worker Processes={self.workers}" if self.workers > 1 else ""
        if self.load_type == LoadType.CLOSED_LOOP:
            print(
                f"Parameters: Mode=ClosedLoop, Clients/Concurrency={self.threads}{worker_info}"
            )
        else:
            print(
                f"Parameters: TPS={self.tps}, Max Workers={self.threads}, MinID={self.min_id}, MaxID={self.max_id}{worker_info}"
            )

        self.is_stopped = False
        self._start_resource_monitoring()

        if self.workers > 1:
            self._init_workers()

        if self.load_type == LoadType.CLOSED_LOOP:
            if self.workers > 1:
                with self._lock:
                    for _ in range(self.threads):
                        self._dispatch_task_to_worker()
            else:
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

        if self.workers > 1:
            drain_start = time.perf_counter()
            while time.perf_counter() - drain_start < 5.0:
                with self._lock:
                    if self._outstanding_tasks == 0:
                        break
                time.sleep(0.05)
            self._cleanup_workers()
        else:
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

        import sys

        sys.stdout.flush()
        sys.stderr.flush()

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
        """Checks concurrency thresholds and dispatches task to thread executor pool or worker processes."""
        if self.workers > 1:
            self._submit_task_multi_worker()
        else:
            self._submit_task_single_worker()

    def _submit_task_single_worker(self) -> None:
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

    def _submit_task_multi_worker(self) -> None:
        with self._lock:
            if self._outstanding_tasks < self.threads:
                self._dispatch_task_to_worker()
            else:
                self._enqueue_task()

    def _enqueue_task(self) -> None:
        queue_size = len(self._task_queue)
        if queue_size > 0:
            now = time.time()
            if now - self._last_queue_log_time > 1.0:
                print(
                    f"Queue size: {queue_size} (concurrency limit reached, tasks are queueing)",
                    file=sys.stderr,
                )
                self._last_queue_log_time = now
        if len(self._task_queue) < 1000000:
            self._task_queue.append(1)
        else:
            print(
                "Task dropped: workload queue is full (1M tasks exceeded)",
                file=sys.stderr,
            )

    def _dispatch_task_to_worker(self) -> None:
        # Select the least loaded active worker, breaking ties round-robin
        active_indices = [i for i in range(self.workers) if self._worker_active[i]]
        if not active_indices or self.is_stopped:
            return

        selected_index = active_indices[0]
        min_in_flight = float("inf")
        for i in range(len(active_indices)):
            idx = active_indices[(self._next_worker_index + i) % len(active_indices)]
            if self._worker_in_flight[idx] < min_in_flight:
                min_in_flight = self._worker_in_flight[idx]
                selected_index = idx

        self._next_worker_index = (self._next_worker_index + 1) % len(active_indices)
        self._worker_in_flight[selected_index] += 1
        self._outstanding_tasks += 1
        task_id = self._next_task_id
        self._next_task_id += 1

        try:
            self._worker_conns[selected_index].send(
                {"type": "execute", "task_id": task_id}
            )
        except Exception as err:
            print(
                f"Failed to post execute task to worker {selected_index}: {err}",
                file=sys.stderr,
            )
            self._worker_in_flight[selected_index] = max(
                0, self._worker_in_flight[selected_index] - 1
            )
            self._outstanding_tasks = max(0, self._outstanding_tasks - 1)
            self.error_counter.add(1, self.get_attributes())

    def _cleanup_workers(self) -> None:
        with self._lock:
            if self._workers_cleaned_up:
                return
            self._workers_cleaned_up = True

        for conn in self._worker_conns:
            try:
                conn.send({"type": "stop"})
            except Exception:
                pass

        for process in self._workers:
            try:
                process.join(timeout=2.0)
                if process.is_alive():
                    process.terminate()
            except Exception:
                pass

        for conn in self._worker_conns:
            try:
                conn.close()
            except Exception:
                pass

        if self._selector:
            try:
                self._selector.close()
            except Exception:
                pass

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
            worker_pids_supplier=self.get_worker_pids,
        )
        self._resource_monitor.start()
