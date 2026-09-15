import os
import resource
import subprocess
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
    except (AttributeError, OSError):
        return float(os.cpu_count() or 1)


CPU_LIMIT = _get_cpu_limit()


def _get_clk_tck() -> int:
    try:
        return os.sysconf("SC_CLK_TCK")
    except (AttributeError, ValueError, OSError):
        return 100


CLK_TCK = _get_clk_tck()


def get_pid_resident_set_size(pid: int) -> int:
    """Returns Resident Set Size (RSS) in bytes for a specific PID."""
    # 1. Try reading Linux /proc/{pid}/status
    try:
        with open(f"/proc/{pid}/status", "r") as status_file:
            for line in status_file:
                if line.startswith("VmRSS:"):
                    parts = line.split()
                    if len(parts) >= 2:
                        return int(parts[1]) * 1024
    except Exception:
        pass

    # 2. Try psutil if installed
    try:
        import psutil

        return psutil.Process(pid).memory_info().rss
    except Exception:
        pass

    # 3. Try ps on macOS / POSIX fallback
    try:
        output = (
            subprocess.check_output(
                ["ps", "-o", "rss=", "-p", str(pid)],
                stderr=subprocess.DEVNULL,
            )
            .decode()
            .strip()
        )
        if output:
            return int(output) * 1024
    except Exception:
        pass

    return 0


def get_pid_cpu_time(pid: int) -> float:
    """Returns total user + system CPU time in seconds for a specific PID."""
    # 1. Try reading Linux /proc/{pid}/stat
    try:
        with open(f"/proc/{pid}/stat", "r") as stat_file:
            content = stat_file.read()
            rparen_index = content.rfind(")")
            if rparen_index != -1:
                fields = content[rparen_index + 1 :].split()
                if len(fields) >= 13:
                    utime_ticks = int(fields[11])
                    stime_ticks = int(fields[12])
                    return (utime_ticks + stime_ticks) / float(CLK_TCK)
    except Exception:
        pass

    # 2. Try psutil if installed
    try:
        import psutil

        cpu_times = psutil.Process(pid).cpu_times()
        return float(cpu_times.user + cpu_times.system)
    except Exception:
        pass

    # 3. Try ps on macOS / BSD
    try:
        output = (
            subprocess.check_output(
                ["ps", "-o", "cputime=", "-p", str(pid)],
                stderr=subprocess.DEVNULL,
            )
            .decode()
            .strip()
        )
        if output:
            days = 0
            if "-" in output:
                days_part, output = output.split("-", 1)
                days = int(days_part)
            parts = output.split(":")
            if len(parts) == 3:
                hours = int(parts[0])
                minutes = int(parts[1])
                seconds = float(parts[2])
            elif len(parts) == 2:
                hours = 0
                minutes = int(parts[0])
                seconds = float(parts[1])
            else:
                hours = minutes = 0
                seconds = float(parts[0])
            return days * 86400.0 + hours * 3600.0 + minutes * 60.0 + seconds
    except Exception:
        pass

    return 0.0


def get_total_cpu_time(worker_pids: Optional[list[int]] = None) -> float:
    """Returns total user + system CPU time in seconds across coordinator and worker processes."""
    if not worker_pids:
        return time.process_time()

    coordinator_cpu = get_pid_cpu_time(os.getpid())
    if coordinator_cpu <= 0.0:
        coordinator_cpu = time.process_time()

    total_cpu = coordinator_cpu
    for pid in worker_pids:
        total_cpu += get_pid_cpu_time(pid)

    return total_cpu


def get_current_resident_set_size(
    worker_pids: Optional[list[int]] = None,
) -> int:
    """Returns the current Resident Set Size (RSS) in bytes, including child worker processes."""
    total_rss = 0
    # Try reading /proc/self/status (Linux-specific, extremely fast and dependency-free)
    try:
        with open("/proc/self/status", "r") as status_file:
            for line in status_file:
                if line.startswith("VmRSS:"):
                    # Line format: "VmRSS:       123456 kB"
                    parts = line.split()
                    if len(parts) >= 2:
                        total_rss += int(parts[1]) * 1024
    except Exception:
        pass

    # Try using psutil if installed and /proc wasn't readable
    if total_rss == 0:
        try:
            import psutil

            total_rss += psutil.Process().memory_info().rss
        except Exception:
            pass

    # Fallback to getrusage on macOS/other POSIX if still 0
    if total_rss == 0:
        try:
            usage = resource.getrusage(resource.RUSAGE_SELF)
            if sys.platform == "darwin":
                total_rss += usage.ru_maxrss
            else:
                total_rss += usage.ru_maxrss * 1024
        except Exception:
            pass

    # Add RSS of all child worker processes
    if worker_pids:
        for pid in worker_pids:
            total_rss += get_pid_resident_set_size(pid)

    return total_rss


class ResourceMonitor:
    def __init__(
        self,
        probe_interval_str: str,
        memory_usage_histogram: Optional[Histogram],
        cpu_utilization_histogram: Optional[Histogram],
        attributes: Dict[str, Any],
        is_stopped_check: Callable[[], bool],
        worker_pids_supplier: Optional[Callable[[], list[int]]] = None,
    ):
        self.probe_interval_str = probe_interval_str
        self.memory_usage_histogram = memory_usage_histogram
        self.cpu_utilization_histogram = cpu_utilization_histogram
        self.attributes = attributes
        self.is_stopped_check = is_stopped_check
        self.worker_pids_supplier = worker_pids_supplier
        self.thread: Optional[threading.Thread] = None

    def start(self) -> None:
        if not self.probe_interval_str or self.probe_interval_str in ("0", "0s"):
            return

        from src.utils.duration import parse_duration

        probe_interval_sec = parse_duration(self.probe_interval_str)
        if probe_interval_sec is None or probe_interval_sec <= 0:
            return

        worker_pids = self.worker_pids_supplier() if self.worker_pids_supplier else None
        self._last_cpu_time = get_total_cpu_time(worker_pids)
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
            worker_pids = (
                self.worker_pids_supplier() if self.worker_pids_supplier else None
            )
            current_rss = get_current_resident_set_size(worker_pids)
            if self.memory_usage_histogram:
                self.memory_usage_histogram.record(int(current_rss), self.attributes)

            now_cpu_time = get_total_cpu_time(worker_pids)
            now_wall_time = time.perf_counter()
            elapsed_wall = now_wall_time - self._last_wall_time
            if elapsed_wall > 0 and self.cpu_utilization_histogram:
                elapsed_cpu = max(0.0, now_cpu_time - self._last_cpu_time)
                cpu_util = elapsed_cpu / elapsed_wall
                self.cpu_utilization_histogram.record(
                    float(cpu_util / CPU_LIMIT), self.attributes
                )

            self._last_cpu_time = now_cpu_time
            self._last_wall_time = now_wall_time
        except Exception:
            pass
