import asyncio
import sys
import threading
import time
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


async def _async_safe_call(action: Callable[[], Any]) -> None:
    try:
        res = action()
        if asyncio.iscoroutine(res):
            await res
    except Exception:
        pass


def worker_process_main(
    worker_id: int,
    pipe: Connection,
    worker_data: BenchmarkWorkerData,
) -> None:
    """Entrypoint executed in dedicated child worker process."""
    asyncio.run(async_worker_main(worker_id, pipe, worker_data))


async def async_worker_main(
    worker_id: int,
    pipe: Connection,
    worker_data: BenchmarkWorkerData,
) -> None:
    try:
        spanner_client = create_spanner_client(worker_data.project_id, worker_data.host)
        database = await spanner_client.instance(worker_data.instance_id).database(
            worker_data.database_id
        )
    except Exception as err:
        print(
            f"Failed to initialize async worker {worker_id}: {err}",
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
            async with database.snapshot(multi_use=False) as snapshot:
                results = await snapshot.execute_sql("SELECT COUNT(*) FROM warehouse")
                warehouse_count = 0
                async for row in results:
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

    # Resolve execution coroutine function based on benchmark type
    if worker_data.benchmark_type == "point-select":

        async def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            await execute_point_select(
                database,
                worker_data.table_name,
                worker_data.min_id,
                worker_data.max_id,
            )
            return None, None, None

    elif worker_data.benchmark_type == "select-update":

        async def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            await execute_select_and_update(
                database,
                worker_data.table_name,
                worker_data.min_id,
                worker_data.max_id,
            )
            return None, None, None

    elif worker_data.benchmark_type == "read-large-result-set":

        async def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            latency_us = await execute_read_large_result_set(
                database, worker_data.num_rows, worker_data.lazy_decode
            )
            return None, latency_us, None

    elif worker_data.benchmark_type == "read-narrow-result-set":

        async def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            latency_us = await execute_read_narrow_result_set(
                database, worker_data.num_rows, worker_data.lazy_decode
            )
            return None, latency_us, None

    elif worker_data.benchmark_type == "tpcc":

        async def execute_fn() -> Tuple[None, Optional[float], Optional[str]]:
            tx_type = await execute_tpcc_transaction(
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
        try:
            pipe.send(
                {
                    "type": "error",
                    "worker_id": worker_id,
                    "error": f"Unsupported benchmark type: {worker_data.benchmark_type}",
                }
            )
        except Exception:
            pass
        return

    pipe_lock = threading.Lock()

    def safe_send(msg: dict) -> None:
        with pipe_lock:
            try:
                pipe.send(msg)
            except (BrokenPipeError, EOFError, OSError):
                pass

    running_tasks: set[asyncio.Task] = set()

    async def run_task(task_id: int) -> None:
        start_time = time.perf_counter()
        try:
            _, custom_latency_us, tx_type = await execute_fn()
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

    # Message delivery bridge from pipe to asyncio loop
    loop = asyncio.get_running_loop()
    task_queue: asyncio.Queue = asyncio.Queue()

    def pipe_reader_thread():
        while True:
            try:
                msg = pipe.recv()
                loop.call_soon_threadsafe(task_queue.put_nowait, msg)
                if isinstance(msg, dict) and msg.get("type") == "stop":
                    break
            except (EOFError, OSError):
                loop.call_soon_threadsafe(task_queue.put_nowait, {"type": "stop"})
                break

    reader_thread = threading.Thread(
        target=pipe_reader_thread,
        name=f"Worker-{worker_id}-PipeReader",
        daemon=True,
    )
    reader_thread.start()

    try:
        while True:
            msg = await task_queue.get()
            if not isinstance(msg, dict):
                continue
            msg_type = msg.get("type")
            if msg_type == "execute":
                task_id = msg.get("task_id", 0)
                active_task = asyncio.create_task(run_task(task_id))
                running_tasks.add(active_task)
                active_task.add_done_callback(running_tasks.discard)
            elif msg_type == "stop":
                break
    finally:
        # Await running tasks
        if running_tasks:
            await asyncio.gather(*list(running_tasks), return_exceptions=True)

        # Cleanup Spanner connections
        await _async_safe_call(lambda: database.close())
        await _async_safe_call(lambda: database.spanner_api.transport.close())
        await _async_safe_call(
            lambda: spanner_client.database_admin_api.transport.close()
        )
        await _async_safe_call(
            lambda: spanner_client.instance_admin_api.transport.close()
        )
        _safe_call(lambda: spanner_client.close())
