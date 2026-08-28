// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use crate::ycsb::generator::{ZipfianGenerator, generate_random_string};
use google_cloud_spanner::client::DatabaseClient;
use google_cloud_spanner::mutation::Mutation;
use std::cmp::{max, min};
use std::mem;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::time::Instant;

/// Populates the target Cloud Spanner table with the specified number of YCSB records across
/// concurrent background tasks using batched mutations.
#[allow(clippy::too_many_arguments)]
pub async fn populate_data(
    client: DatabaseClient,
    table_name: &str,
    record_count: i64,
    field_count: usize,
    field_length: usize,
    zero_padding: usize,
    threads: usize,
    batch_size: usize,
) -> anyhow::Result<()> {
    println!(
        "Populating YCSB table {} with {} records using {} threads and batch size {}...",
        table_name, record_count, threads, batch_size
    );

    let progress = Arc::new(AtomicU64::new(0));
    let last_log_time = Arc::new(AtomicU64::new(0));
    let start_time = Instant::now();
    let ranges = compute_thread_ranges(record_count, threads);
    let field_names: Arc<Vec<String>> = Arc::new(
        (0..field_count)
            .map(|index| format!("field{}", index))
            .collect(),
    );
    let mut handles = Vec::with_capacity(ranges.len());

    for (start_id, end_id) in ranges {
        let database_client = client.clone();
        let table = table_name.to_string();
        let progress_clone = Arc::clone(&progress);
        let last_log_time_clone = Arc::clone(&last_log_time);
        let field_names_clone = Arc::clone(&field_names);

        handles.push(tokio::spawn(async move {
            let mut batch = Vec::with_capacity(batch_size);
            for id in start_id..=end_id {
                let key = ZipfianGenerator::build_key_name(id, zero_padding);
                let mut builder = Mutation::new_insert_or_update_builder(&table)
                    .set("id")
                    .to(&key);
                for field_index in 0..field_count {
                    let value = generate_random_string(field_length);
                    builder = builder.set(&field_names_clone[field_index]).to(&value);
                }
                batch.push(builder.build());

                if batch.len() >= batch_size {
                    let to_send = mem::replace(&mut batch, Vec::with_capacity(batch_size));
                    let writer = database_client.write_only_transaction().build();
                    writer.write_at_least_once(to_send).await?;
                    let current = progress_clone.fetch_add(batch_size as u64, Ordering::Relaxed)
                        + batch_size as u64;
                    log_progress(
                        current,
                        record_count as u64,
                        start_time,
                        &last_log_time_clone,
                    );
                }
            }

            if !batch.is_empty() {
                let count = batch.len();
                let writer = database_client.write_only_transaction().build();
                writer.write_at_least_once(batch).await?;
                let current =
                    progress_clone.fetch_add(count as u64, Ordering::Relaxed) + count as u64;
                log_progress(
                    current,
                    record_count as u64,
                    start_time,
                    &last_log_time_clone,
                );
            }

            Ok::<(), anyhow::Error>(())
        }));
    }

    for handle in handles {
        handle.await??;
    }

    let duration_seconds = max(1, start_time.elapsed().as_secs());
    let records_per_second = record_count as u64 / duration_seconds;
    println!(
        "Successfully populated {} records in {}s ({} records/sec).",
        record_count, duration_seconds, records_per_second
    );

    Ok(())
}

/// Computes non-overlapping record index intervals `[start_id, end_id]` for partitioning
/// population across `threads` workers.
pub(crate) fn compute_thread_ranges(record_count: i64, threads: usize) -> Vec<(i64, i64)> {
    if record_count <= 0 || threads == 0 {
        return Vec::new();
    }
    let records_per_thread = (record_count + threads as i64 - 1) / threads as i64;
    let mut ranges = Vec::with_capacity(threads);

    for thread_index in 0..threads {
        let start_id = thread_index as i64 * records_per_thread;
        if start_id >= record_count {
            break;
        }
        let end_id = min(
            record_count - 1,
            (thread_index as i64 + 1) * records_per_thread - 1,
        );
        ranges.push((start_id, end_id));
    }
    ranges
}

fn log_progress(current: u64, total: u64, start_time: Instant, last_log_time: &AtomicU64) {
    let now_ms = start_time.elapsed().as_millis() as u64;
    let last = last_log_time.load(Ordering::Relaxed);
    if (now_ms.saturating_sub(last) > 5000 || current >= total)
        && last_log_time
            .compare_exchange(last, now_ms, Ordering::SeqCst, Ordering::Relaxed)
            .is_ok()
    {
        let percentage = (current as f64 * 100.0) / total as f64;
        let elapsed_seconds = max(1, start_time.elapsed().as_secs());
        let records_per_second = current / elapsed_seconds;
        println!(
            "Progress: {} / {} records ({:.1}%) - {} records/s",
            current, total, percentage, records_per_second
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_thread_range() {
        let ranges = compute_thread_ranges(100, 1);
        assert_eq!(
            ranges,
            vec![(0, 99)],
            "Single thread should cover range 0..=99"
        );
    }

    #[test]
    fn even_partition_ranges() {
        let ranges = compute_thread_ranges(100, 4);
        assert_eq!(
            ranges,
            vec![(0, 24), (25, 49), (50, 74), (75, 99)],
            "4 threads across 100 records should divide into 4 equal segments of 25"
        );
    }

    #[test]
    fn uneven_partition_ranges() {
        let ranges = compute_thread_ranges(10, 3);
        assert_eq!(
            ranges,
            vec![(0, 3), (4, 7), (8, 9)],
            "10 records with 3 threads should partition without gaps or overlaps"
        );
    }

    #[test]
    fn more_threads_than_records() {
        let ranges = compute_thread_ranges(3, 10);
        assert_eq!(
            ranges,
            vec![(0, 0), (1, 1), (2, 2)],
            "3 records with 10 threads should only create 3 active single-item partitions"
        );
    }

    #[test]
    fn empty_or_zero_inputs() {
        assert_eq!(
            compute_thread_ranges(0, 4),
            vec![],
            "0 records should produce no ranges"
        );
        assert_eq!(
            compute_thread_ranges(100, 0),
            vec![],
            "0 threads should produce no ranges"
        );
        assert_eq!(
            compute_thread_ranges(-5, 2),
            vec![],
            "Negative record count should produce no ranges"
        );
    }

    #[test]
    fn coverage_and_contiguity() {
        let record_count = 12345;
        let threads = 7;
        let ranges = compute_thread_ranges(record_count, threads);

        let mut expected_start = 0;
        let mut total_records = 0;

        for (start_id, end_id) in ranges {
            assert_eq!(
                start_id, expected_start,
                "Partition start_id {} must match expected start {}",
                start_id, expected_start
            );
            assert!(
                end_id >= start_id,
                "Partition end_id {} must be >= start_id {}",
                end_id,
                start_id
            );
            total_records += end_id - start_id + 1;
            expected_start = end_id + 1;
        }

        assert_eq!(
            expected_start, record_count,
            "Final end_id + 1 must equal total record count"
        );
        assert_eq!(
            total_records, record_count,
            "Sum of partition sizes must equal total record count"
        );
    }
}
