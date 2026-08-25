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

use crate::ycsb::generator::{
    ScrambledZipfianGenerator, SkewedLatestGenerator, ZipfianGenerator, generate_random_string,
};
use crate::ycsb::workload::{KeyDistribution, Operation, YcsbWorkload};
use futures::FutureExt;
use futures::future::BoxFuture;
use google_cloud_spanner::Error as SpannerError;
use google_cloud_spanner::client::DatabaseClient;
use google_cloud_spanner::key;
use google_cloud_spanner::key::{KeyRange, KeySet};
use google_cloud_spanner::mutation::Mutation;
use google_cloud_spanner::read::ReadRequest;
use google_cloud_spanner::result::{ResultSet, Row};
use google_cloud_spanner::statement::Statement;
use rand::{RngExt, random_range, rng};
use std::cmp::max;
use std::hint::black_box;
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use tokio::time::Instant;

/// Shared state and metrics tracking for executing YCSB workloads against Cloud Spanner.
pub struct YcsbBenchmarkState {
    workload: YcsbWorkload,
    distribution: KeyDistribution,
    record_count: i64,
    zero_padding: usize,
    field_count: usize,
    field_length: usize,
    use_read_row: bool,
    is_mock: bool,
    table_name: Arc<String>,
    zipfian_generator: ZipfianGenerator,
    scrambled_zipfian_generator: ScrambledZipfianGenerator,
    skewed_latest_generator: SkewedLatestGenerator,
    insert_key_sequence: Arc<AtomicI64>,
    field_names: Arc<Vec<String>>,
    read_sql: String,
    scan_sql: String,

    read_operation_count: AtomicU64,
    read_total_duration_ns: AtomicU64,
    update_operation_count: AtomicU64,
    update_total_duration_ns: AtomicU64,
    insert_operation_count: AtomicU64,
    insert_total_duration_ns: AtomicU64,
    scan_operation_count: AtomicU64,
    scan_total_duration_ns: AtomicU64,
    rmw_operation_count: AtomicU64,
    rmw_total_duration_ns: AtomicU64,
}

impl YcsbBenchmarkState {
    /// Creates and initializes benchmark state, key generators, projection SQL statements, and metric counters.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        table_name: String,
        workload: YcsbWorkload,
        distribution: KeyDistribution,
        record_count: i64,
        zero_padding: usize,
        field_count: usize,
        field_length: usize,
        use_read_row: bool,
        is_mock: bool,
    ) -> Self {
        let max_record_id = max(0, record_count - 1);
        let zipfian_generator = ZipfianGenerator::new(0, max_record_id);
        let scrambled_zipfian_generator = ScrambledZipfianGenerator::new(0, max_record_id);
        let insert_key_sequence = Arc::new(AtomicI64::new(record_count));
        let skewed_latest_generator = SkewedLatestGenerator::new(Arc::clone(&insert_key_sequence));

        let mut field_names = Vec::with_capacity(field_count);
        for index in 0..field_count {
            field_names.push(format!("field{}", index));
        }

        let projection = field_names.join(", ");
        let read_sql = format!("SELECT {} FROM {} WHERE id = @id", projection, table_name);
        let scan_sql = format!(
            "SELECT {} FROM {} WHERE id >= @startKey ORDER BY id LIMIT @scanLength",
            projection, table_name
        );

        Self {
            workload,
            distribution,
            record_count,
            zero_padding,
            field_count,
            field_length,
            use_read_row,
            is_mock,
            table_name: Arc::new(table_name),
            zipfian_generator,
            scrambled_zipfian_generator,
            skewed_latest_generator,
            insert_key_sequence,
            field_names: Arc::new(field_names),
            read_sql,
            scan_sql,
            read_operation_count: AtomicU64::new(0),
            read_total_duration_ns: AtomicU64::new(0),
            update_operation_count: AtomicU64::new(0),
            update_total_duration_ns: AtomicU64::new(0),
            insert_operation_count: AtomicU64::new(0),
            insert_total_duration_ns: AtomicU64::new(0),
            scan_operation_count: AtomicU64::new(0),
            scan_total_duration_ns: AtomicU64::new(0),
            rmw_operation_count: AtomicU64::new(0),
            rmw_total_duration_ns: AtomicU64::new(0),
        }
    }

    fn get_random_key(&self) -> String {
        if self.is_mock {
            return ZipfianGenerator::build_key_name(0, self.zero_padding);
        }
        match self.distribution {
            KeyDistribution::Zipfian => self.zipfian_generator.next_key(self.zero_padding),
            KeyDistribution::Uniform => {
                let id = rng().random_range(0..self.record_count);
                ZipfianGenerator::build_key_name(id, self.zero_padding)
            }
            KeyDistribution::ScrambledZipfian => {
                self.scrambled_zipfian_generator.next_key(self.zero_padding)
            }
        }
    }

    /// Executes a single benchmark operation (Read, Update, Insert, Scan, or RMW) according to the configured workload.
    pub fn execute_operation(
        self: Arc<Self>,
        client: DatabaseClient,
    ) -> BoxFuture<'static, anyhow::Result<()>> {
        async move {
            let operation = self.workload.next_operation();
            match operation {
                Operation::Read => self.execute_read(client).await,
                Operation::Update => self.execute_update(client).await,
                Operation::Insert => self.execute_insert(client).await,
                Operation::Scan => self.execute_scan(client).await,
                Operation::ReadModifyWrite => self.execute_read_modify_write(client).await,
            }
        }
        .boxed()
    }

    async fn execute_read(&self, client: DatabaseClient) -> anyhow::Result<()> {
        let start_time = Instant::now();
        let key = if self.workload == YcsbWorkload::D && !self.is_mock {
            self.skewed_latest_generator.next_key(self.zero_padding)
        } else {
            self.get_random_key()
        };

        let result = if self.use_read_row {
            let single_use = client.single_use().build();
            let read_request = ReadRequest::builder(&*self.table_name, &*self.field_names)
                .with_keys(key![key.as_str()])
                .build();
            let result_set = single_use.execute_read(read_request).await?;
            consume_single_row(result_set, self.field_count, &key).await
        } else {
            let single_use = client.single_use().build();
            let statement = Statement::builder(&self.read_sql)
                .add_param("id", &key)
                .build();
            let result_set: ResultSet = single_use.execute_query(statement).await?;
            consume_single_row(result_set, self.field_count, &key).await
        };

        let elapsed_ns = start_time.elapsed().as_nanos() as u64;
        self.read_total_duration_ns
            .fetch_add(elapsed_ns, Ordering::Relaxed);
        self.read_operation_count.fetch_add(1, Ordering::Relaxed);
        result
    }

    async fn execute_update(&self, client: DatabaseClient) -> anyhow::Result<()> {
        let start_time = Instant::now();
        let key = self.get_random_key();
        let field_index = random_range(0..self.field_count);
        let field_name = &self.field_names[field_index];
        let value = generate_random_string(self.field_length);

        let mutation = Mutation::new_insert_or_update_builder(&*self.table_name)
            .set("id")
            .to(&key)
            .set(field_name)
            .to(&value)
            .build();

        let writer = client.write_only_transaction().build();
        let result = writer
            .write_at_least_once(vec![mutation])
            .await
            .map(|_| ())
            .map_err(Into::into);

        let elapsed_ns = start_time.elapsed().as_nanos() as u64;
        self.update_total_duration_ns
            .fetch_add(elapsed_ns, Ordering::Relaxed);
        self.update_operation_count.fetch_add(1, Ordering::Relaxed);
        result
    }

    async fn execute_insert(&self, client: DatabaseClient) -> anyhow::Result<()> {
        let start_time = Instant::now();
        let record_number = if self.is_mock {
            0
        } else {
            self.insert_key_sequence.fetch_add(1, Ordering::Relaxed)
        };
        let key = ZipfianGenerator::build_key_name(record_number, self.zero_padding);
        let mut builder = Mutation::new_insert_or_update_builder(&*self.table_name)
            .set("id")
            .to(&key);
        for field_index in 0..self.field_count {
            let field_name = &self.field_names[field_index];
            let value = generate_random_string(self.field_length);
            builder = builder.set(field_name).to(&value);
        }

        let writer = client.write_only_transaction().build();
        let result = writer
            .write_at_least_once(vec![builder.build()])
            .await
            .map(|_| ())
            .map_err(Into::into);

        let elapsed_ns = start_time.elapsed().as_nanos() as u64;
        self.insert_total_duration_ns
            .fetch_add(elapsed_ns, Ordering::Relaxed);
        self.insert_operation_count.fetch_add(1, Ordering::Relaxed);
        result
    }

    async fn execute_scan(&self, client: DatabaseClient) -> anyhow::Result<()> {
        let start_time = Instant::now();
        let start_key = self.get_random_key();
        let scan_length: i64 = if self.is_mock {
            10
        } else {
            random_range(1..=100)
        };

        let result = if self.use_read_row {
            let single_use = client.single_use().build();
            let key_range = KeyRange::closed_open(key![start_key.as_str()], key![]);
            let read_request = ReadRequest::builder(&*self.table_name, &*self.field_names)
                .with_keys(KeySet::from(key_range))
                .set_limit(scan_length)
                .build();
            let result_set = single_use.execute_read(read_request).await?;
            consume_all_rows(result_set, self.field_count).await
        } else {
            let single_use = client.single_use().build();
            let statement = Statement::builder(&self.scan_sql)
                .add_param("startKey", &start_key)
                .add_param("scanLength", &scan_length)
                .build();
            let result_set: ResultSet = single_use.execute_query(statement).await?;
            consume_all_rows(result_set, self.field_count).await
        };

        let elapsed_ns = start_time.elapsed().as_nanos() as u64;
        self.scan_total_duration_ns
            .fetch_add(elapsed_ns, Ordering::Relaxed);
        self.scan_operation_count.fetch_add(1, Ordering::Relaxed);
        result
    }

    async fn execute_read_modify_write(&self, client: DatabaseClient) -> anyhow::Result<()> {
        let start_time = Instant::now();
        let key = self.get_random_key();
        let field_index = random_range(0..self.field_count);
        let value = generate_random_string(self.field_length);
        let table = Arc::clone(&self.table_name);
        let field_names = Arc::clone(&self.field_names);
        let field_count = self.field_count;

        let runner = client.read_write_transaction().build().await?;
        let result = runner
            .run(async move |transaction| {
                let read_request = ReadRequest::builder(&*table, &*field_names)
                    .with_keys(key![key.as_str()])
                    .build();
                let mut result_set = transaction.execute_read(read_request).await?;
                if let Some(row) = result_set.next().await.transpose()? {
                    consume_row(&row, field_count);
                } else {
                    return Err(SpannerError::deser(format!(
                        "Row not found for key: {}",
                        key
                    )));
                }
                drop(result_set);

                let mutation = Mutation::new_insert_or_update_builder(&*table)
                    .set("id")
                    .to(&key)
                    .set(&field_names[field_index])
                    .to(&value)
                    .build();
                transaction.buffer(vec![mutation])?;
                Ok(())
            })
            .await;

        let elapsed_ns = start_time.elapsed().as_nanos() as u64;
        self.rmw_total_duration_ns
            .fetch_add(elapsed_ns, Ordering::Relaxed);
        self.rmw_operation_count.fetch_add(1, Ordering::Relaxed);
        result?;
        Ok(())
    }

    /// Prints latency and operation summary statistics across all workload operations.
    pub fn print_summary(&self) {
        let reads = self.read_operation_count.load(Ordering::Relaxed);
        let updates = self.update_operation_count.load(Ordering::Relaxed);
        let inserts = self.insert_operation_count.load(Ordering::Relaxed);
        let scans = self.scan_operation_count.load(Ordering::Relaxed);
        let rmws = self.rmw_operation_count.load(Ordering::Relaxed);

        if reads > 0 {
            let avg_read_ms = (self.read_total_duration_ns.load(Ordering::Relaxed) as f64
                / 1_000_000.0)
                / reads as f64;
            println!(
                "  [READ]   Count: {} ops, Avg Latency: {:.2} ms",
                reads, avg_read_ms
            );
        }
        if updates > 0 {
            let avg_update_ms = (self.update_total_duration_ns.load(Ordering::Relaxed) as f64
                / 1_000_000.0)
                / updates as f64;
            println!(
                "  [UPDATE] Count: {} ops, Avg Latency: {:.2} ms",
                updates, avg_update_ms
            );
        }
        if inserts > 0 {
            let avg_insert_ms = (self.insert_total_duration_ns.load(Ordering::Relaxed) as f64
                / 1_000_000.0)
                / inserts as f64;
            println!(
                "  [INSERT] Count: {} ops, Avg Latency: {:.2} ms",
                inserts, avg_insert_ms
            );
        }
        if scans > 0 {
            let avg_scan_ms = (self.scan_total_duration_ns.load(Ordering::Relaxed) as f64
                / 1_000_000.0)
                / scans as f64;
            println!(
                "  [SCAN]   Count: {} ops, Avg Latency: {:.2} ms",
                scans, avg_scan_ms
            );
        }
        if rmws > 0 {
            let avg_rmw_ms = (self.rmw_total_duration_ns.load(Ordering::Relaxed) as f64
                / 1_000_000.0)
                / rmws as f64;
            println!(
                "  [RMW]    Count: {} ops, Avg Latency: {:.2} ms",
                rmws, avg_rmw_ms
            );
        }
    }
}

async fn consume_single_row(
    mut result_set: ResultSet,
    field_count: usize,
    key: &str,
) -> anyhow::Result<()> {
    let mut found = false;
    while let Some(row) = result_set.next().await.transpose()? {
        consume_row(&row, field_count);
        found = true;
    }
    if found {
        Ok(())
    } else {
        anyhow::bail!("Row not found for key: {}", key);
    }
}

async fn consume_all_rows(mut result_set: ResultSet, field_count: usize) -> anyhow::Result<()> {
    while let Some(row) = result_set.next().await.transpose()? {
        consume_row(&row, field_count);
    }
    Ok(())
}

fn consume_row(row: &Row, field_count: usize) {
    for index in 0..field_count {
        let value: String = row.get(index);
        black_box(value);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn benchmark_state_initialization() {
        let state = YcsbBenchmarkState::new(
            "usertable".to_string(),
            YcsbWorkload::A,
            KeyDistribution::Zipfian,
            1000,
            12,
            10,
            100,
            false,
            false,
        );

        assert_eq!(state.field_names.len(), 10);
        assert_eq!(state.field_names[0], "field0");
        assert_eq!(state.field_names[9], "field9");
        assert_eq!(
            state.read_sql,
            "SELECT field0, field1, field2, field3, field4, field5, field6, field7, field8, field9 FROM usertable WHERE id = @id"
        );
        assert_eq!(
            state.scan_sql,
            "SELECT field0, field1, field2, field3, field4, field5, field6, field7, field8, field9 FROM usertable WHERE id >= @startKey ORDER BY id LIMIT @scanLength"
        );

        let key = state.get_random_key();
        assert!(key.starts_with("user"));
        assert_eq!(key.len(), 16); // "user" + 12 digits
    }

    #[test]
    fn print_summary_no_panic() {
        let state = YcsbBenchmarkState::new(
            "usertable".to_string(),
            YcsbWorkload::B,
            KeyDistribution::ScrambledZipfian,
            1000,
            12,
            5,
            50,
            true,
            true,
        );
        state.read_operation_count.store(5, Ordering::Relaxed);
        state
            .read_total_duration_ns
            .store(50_000_000, Ordering::Relaxed);
        state.print_summary();
    }

    #[test]
    fn type_traits() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<YcsbBenchmarkState>();
    }
}
