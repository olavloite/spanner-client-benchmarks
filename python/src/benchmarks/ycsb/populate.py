import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

from google.cloud.spanner_v1.database import Database

from .utils import build_key_name, generate_random_string


def populate_data(
    database: Database,
    table_name: str = "usertable",
    record_count: int = 100000,
    zero_padding: int = 12,
    field_count: int = 10,
    field_length: int = 100,
    threads: int = 16,
    batch_size: int = 500,
) -> None:
    """
    Populates the YCSB table with pseudo-random records concurrently across multiple worker threads.
    Precomputes column names once and uses batched mutations for high throughput.
    """
    if record_count <= 0:
        return

    ranges = _compute_partition_ranges(record_count, threads)
    if not ranges:
        return

    # Precompute column names once to avoid per-record string/list allocations
    col_names = ["id"] + [f"field{f}" for f in range(field_count)]

    print(
        f"Starting data population: {record_count} records across {len(ranges)} threads with batch size {batch_size}..."
    )
    sys.stdout.flush()

    start_time = time.time()
    last_log_time_box = [start_time]
    progress = 0
    progress_lock = threading.Lock()

    def worker_populate(start_idx: int, end_idx: int) -> None:
        nonlocal progress
        batch_values = []

        for record_idx in range(start_idx, end_idx):
            key = build_key_name(record_idx, zero_padding)
            row = [key] + [
                generate_random_string(field_length) for _ in range(field_count)
            ]
            batch_values.append(row)

            if len(batch_values) >= batch_size:
                with database.batch() as batch:
                    batch.insert_or_update(
                        table=table_name,
                        columns=col_names,
                        values=batch_values,
                    )
                with progress_lock:
                    progress += len(batch_values)
                    _log_progress(
                        progress,
                        record_count,
                        start_time,
                        last_log_time_box,
                    )
                batch_values = []

        if batch_values:
            with database.batch() as batch:
                batch.insert_or_update(
                    table=table_name,
                    columns=col_names,
                    values=batch_values,
                )
            with progress_lock:
                progress += len(batch_values)
                _log_progress(
                    progress,
                    record_count,
                    start_time,
                    last_log_time_box,
                )

    with ThreadPoolExecutor(max_workers=len(ranges)) as executor:
        futures = [
            executor.submit(worker_populate, start, end) for start, end in ranges
        ]
        for f in as_completed(futures):
            f.result()

    total_duration = time.time() - start_time
    total_rate = (float(record_count) / total_duration) if total_duration > 0 else 0.0
    print(
        f"Data population complete: {record_count} records inserted in {total_duration:.3f}s ({total_rate:.2f} records/sec)."
    )
    sys.stdout.flush()


def _compute_partition_ranges(
    record_count: int, num_partitions: int
) -> list[tuple[int, int]]:
    if num_partitions <= 0 or record_count <= 0:
        return []
    if num_partitions > record_count:
        num_partitions = record_count

    chunk_size = record_count // num_partitions
    remainder = record_count % num_partitions
    ranges = []
    current_start = 0

    for i in range(num_partitions):
        size = chunk_size + (1 if i < remainder else 0)
        ranges.append((current_start, current_start + size))
        current_start += size

    return ranges


def _log_progress(
    current: int,
    total: int,
    start_time: float,
    last_log_time_box: list[float],
) -> None:
    now = time.time()
    if now - last_log_time_box[0] >= 5.0 or current == total:
        last_log_time_box[0] = now
        pct = (float(current) * 100.0) / float(total) if total > 0 else 100.0
        elapsed = max(1.0, now - start_time)
        rate = int(float(current) / elapsed)
        print(f"Progress: {current} / {total} records ({pct:.1f}%) - {rate} records/s")
        sys.stdout.flush()
