import os
import resource
import sys
import threading
import time
from typing import Any, Callable, Dict, Optional

from opentelemetry.metrics import Histogram


def _get_cpu_limit() -> float:
    limit_str = os.environ.get("BENCHMARK_CPU_LIMIT")
    if limit_str:
        try:
            limit = float(limit_str)
            if limit > 0:
                return limit
        except ValueError:
            pass
    try:
        return float(len(os.sched_getaffinity(0)))
    except AttributeError:
        return float(os.cpu_count() or 1)


CPU_LIMIT = _get_cpu_limit()


def get_current_resident_set_size() -> int:
    """Returns the current Resident Set Size (RSS) in bytes."""
    # Try reading /proc/self/status (Linux-specific, extremely fast and dependency-free)
    try:
        with open("/proc/self/status", "r") as status_file:
            for line in status_file:
                if line.startswith("VmRSS:"):
                    # Line format: "VmRSS:       123456 kB"
                    parts = line.split()
                    if len(parts) >= 2:
                        return int(parts[1]) * 1024
    except Exception:
        pass

    # Try using psutil if installed
    try:
        import psutil

        return psutil.Process().memory_info().rss
    except Exception:
        pass

    # Fallback to getrusage (reports max/peak RSS, but works on macOS and other POSIX platforms)
    try:
        usage = resource.getrusage(resource.RUSAGE_SELF)
        if sys.platform == "darwin":
            return usage.ru_maxrss
        return usage.ru_maxrss * 1024
    except Exception:
        pass

    return 0


class ResourceMonitor:
    def __init__(
        self,
        probe_interval_str: str,
        memory_usage_histogram: Optional[Histogram],
        cpu_utilization_histogram: Optional[Histogram],
        attributes: Dict[str, Any],
        is_stopped_check: Callable[[], bool],
    ):
        self.probe_interval_str = probe_interval_str
        self.memory_usage_histogram = memory_usage_histogram
        self.cpu_utilization_histogram = cpu_utilization_histogram
        self.attributes = attributes
        self.is_stopped_check = is_stopped_check
        self.thread: Optional[threading.Thread] = None

    def start(self) -> None:
        if not self.probe_interval_str or self.probe_interval_str in ("0", "0s"):
            return

        from src.utils.duration import parse_duration

        probe_interval_sec = parse_duration(self.probe_interval_str)
        if probe_interval_sec is None or probe_interval_sec <= 0:
            return

        self._last_cpu_time = time.process_time()
        self._last_wall_time = time.perf_counter()

        def _loop():
            while not self.is_stopped_check():
                time.sleep(probe_interval_sec)
                if self.is_stopped_check():
                    break
                self._probe_resource_usage()

        self.thread = threading.Thread(
            target=_loop, name="ResourceMonitor", daemon=True
        )
        self.thread.start()

    def _probe_resource_usage(self) -> None:
        try:
            current_rss = get_current_resident_set_size()
            if self.memory_usage_histogram:
                self.memory_usage_histogram.record(int(current_rss), self.attributes)

            now_cpu_time = time.process_time()
            now_wall_time = time.perf_counter()
            elapsed_wall = now_wall_time - self._last_wall_time
            if elapsed_wall > 0 and self.cpu_utilization_histogram:
                cpu_util = (now_cpu_time - self._last_cpu_time) / elapsed_wall
                self.cpu_utilization_histogram.record(
                    float(cpu_util / CPU_LIMIT), self.attributes
                )

            self._last_cpu_time = now_cpu_time
            self._last_wall_time = now_wall_time
        except Exception:
            pass
