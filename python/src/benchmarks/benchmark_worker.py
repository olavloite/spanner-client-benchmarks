import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from multiprocessing.connection import Connection
from typing import Any, Callable, Optional, Tuple

from src.benchmarks.point_select import execute_point_select
from src.benchmarks.read_large_result_set import execute_read_large_result_set
from src.benchmarks.read_narrow_result_set import execute_read_narrow_result_set
from src.benchmarks.select_update import execute_select_and_update
from src.benchmarks.tpcc.transactions import execute_tpcc_transaction
from src.spanner.client import create_spanner_client


@dataclass
class BenchmarkWorkerData:
    """Configuration transferred from coordinator to worker process during initialization."""

    worker_id: int
    benchmark_type: str
    project_id: str
    instance_id: str
    database_id: str
    host: Optional[str] = None
    table_name: str = "test"
    min_id: int = 1
    max_id: int = 1000000
    num_rows: int = 100000
    lazy_decode: bool = False
    scale_factor: int = 1
    items: int = 100000
    extended: bool = False
    concurrency: int = 10


def _safe_call(action: Callable[[], Any]) -> None:
    try:
        action()
    except Exception:
        pass


def worker_process_main(
    worker_id: int,
    pipe: Connection,
    worker_data: BenchmarkWorkerData,
) -> None:
    try:
        spanner_client = create_spanner_client(worker_data.project_id, worker_data.host)
        database = spanner_client.instance(worker_data.instance_id).database(
            worker_data.database_id
        )
    except Exception as err:
        print(
            f"Failed to initialize worker {worker_id}: {err}",
            file=sys.stderr,
        )
        try:
            pipe.send(
                {
                    "type": "error",
                    "worker_id": worker_id,
                    "error": str(err),
                }
            )
        except Exception:
            pass
        return

    # For TPC-C, verify database capacity on worker 0 during initialization
    if worker_id == 0 and worker_data.benchmark_type == "tpcc":
        try:
            with database.snapshot(multi_use=False) as snapshot:
                results = snapshot.execute_sql("SELECT COUNT(*) FROM warehouse")
                warehouse_count = 0
                for row in results:
                    warehouse_count = row[0]
                    break
                if warehouse_count < worker_data.scale_factor:
                    raise RuntimeError(
                        f"Database capacity check failed: Required scale factor {worker_data.scale_factor} warehouses, but database only has {warehouse_count}"
                    )
        except Exception as err:
            print(
                f"Worker {worker_id} capacity check failed: {err}",
                file=sys.stderr,
            )
            try:
                pipe.send(
                    {
                        "type": "error",
                        "worker_id": worker_id,
                        "error": str(err),
                    }
                )
            except Exception:
                pass
            return

    # Resolve execution function based on benchmark type
    if worker_data.benchmark_type == "point-select":

        def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            execute_point_select(
                database,
                worker_data.table_name,
                worker_data.min_id,
                worker_data.max_id,
            )
            return None, None, None

    elif worker_data.benchmark_type == "select-update":

        def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            execute_select_and_update(
                database,
                worker_data.table_name,
                worker_data.min_id,
                worker_data.max_id,
            )
            return None, None, None

    elif worker_data.benchmark_type == "read-large-result-set":

        def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            latency_us = execute_read_large_result_set(
                database, worker_data.num_rows, worker_data.lazy_decode
            )
            return None, latency_us, None

    elif worker_data.benchmark_type == "read-narrow-result-set":

        def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            latency_us = execute_read_narrow_result_set(
                database, worker_data.num_rows, worker_data.lazy_decode
            )
            return None, latency_us, None

    elif worker_data.benchmark_type == "tpcc":

        def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            tx_type = execute_tpcc_transaction(
                database,
                worker_data.scale_factor,
                worker_data.items,
                worker_data.extended,
            )
            return None, None, tx_type

    else:
        print(
            f"Unsupported benchmark type in worker {worker_id}: {worker_data.benchmark_type}",
            file=sys.stderr,
        )
        pipe.send(
            {
                "type": "error",
                "worker_id": worker_id,
                "error": f"Unsupported benchmark type: {worker_data.benchmark_type}",
            }
        )
        return

    pipe_lock = threading.Lock()

    def safe_send(msg: dict) -> None:
        with pipe_lock:
            try:
                pipe.send(msg)
            except (BrokenPipeError, EOFError, OSError):
                pass

    executor = ThreadPoolExecutor(max_workers=max(1, worker_data.concurrency))

    def run_task(task_id: int) -> None:
        start_time = time.perf_counter()
        try:
            _, custom_latency_us, tx_type = execute_fn()
            end_time = time.perf_counter()
            latency_us = (
                custom_latency_us
                if custom_latency_us is not None
                else (end_time - start_time) * 1000000.0
            )
            safe_send(
                {
                    "type": "completed",
                    "task_id": task_id,
                    "worker_id": worker_id,
                    "latency_us": latency_us,
                    "success": True,
                    "tx_type": tx_type,
                    "error": None,
                }
            )
        except Exception as err:
            end_time = time.perf_counter()
            tx_type = getattr(err, "tx_type", None)
            safe_send(
                {
                    "type": "completed",
                    "task_id": task_id,
                    "worker_id": worker_id,
                    "latency_us": (end_time - start_time) * 1000000.0,
                    "success": False,
                    "tx_type": tx_type,
                    "error": str(err),
                }
            )

    # Notify coordinator that client initialization is complete
    safe_send({"type": "ready", "worker_id": worker_id})

    try:
        while True:
            try:
                msg = pipe.recv()
            except (EOFError, KeyboardInterrupt):
                break

            if not isinstance(msg, dict):
                continue

            msg_type = msg.get("type")
            if msg_type == "execute":
                task_id = msg.get("task_id", 0)
                executor.submit(run_task, task_id)
            elif msg_type == "stop":
                break
    finally:
        # Graceful teardown
        executor.shutdown(wait=True)
        _safe_call(
            lambda: (
                database.channel_pool.close()
                if getattr(database, "channel_pool", None)
                else None
            )
        )
        _safe_call(lambda: database.pool.close())
        _safe_call(lambda: database.spanner_api.transport.close())
        _safe_call(lambda: spanner_client.database_admin_api.transport.close())
        _safe_call(lambda: spanner_client.instance_admin_api.transport.close())
        _safe_call(lambda: spanner_client.close())
