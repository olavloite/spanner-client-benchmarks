// Copyright 2025 Google LLC
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

use crate::BenchmarkMetrics;
use google_cloud_spanner::batch::BatchDml;
use google_cloud_spanner::client::DatabaseClient;
use google_cloud_spanner::key;
use google_cloud_spanner::key::{KeyRange, KeySet};
use google_cloud_spanner::model::PartitionOptions;
use google_cloud_spanner::mutation::Mutation;
use google_cloud_spanner::read::ReadRequest;
use google_cloud_spanner::result::ResultSet;
use google_cloud_spanner::statement::Statement;
use google_cloud_spanner::transaction::TimestampBound;
use opentelemetry::KeyValue;
use std::hint::black_box;
use std::sync::Arc;
use tokio::time::{Duration, Instant};

async fn execute_new_order(
    client: DatabaseClient,
    scale_factor: i64,
    total_items: i64,
    _extended: bool,
) -> anyhow::Result<()> {
    let builder = client
        .read_write_transaction()
        .set_transaction_tag("new_order");
    let runner = builder.build().await?;
    runner.run(async move |transaction| {
        let warehouse_id = rand::random_range(1..=scale_factor);
        let district_id = rand::random_range(1..=10);
        let customer_id = rand::random_range(1..=3000);
        let num_items = rand::random_range(5..=15i64);

        let mut item_ids = Vec::with_capacity(num_items as usize);
        let mut quantities = Vec::with_capacity(num_items as usize);
        for _ in 0..num_items {
            item_ids.push(rand::random_range(1..=total_items));
            quantities.push(rand::random_range(1..=10));
        }

        let statement = Statement::builder("SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d")
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
            .set_request_tag("new_order")
            .build();

        let mut result_set: ResultSet = transaction.execute_query(statement).await?;
        let mut next_order_id = 1000i64;
        if let Some(row) = result_set.next().await.transpose()? {
            let val: i64 = row.get(0_usize);
            next_order_id = val;
        }
        drop(result_set);

        let customer_query = Statement::builder("SELECT discount, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
            .add_param("c", &customer_id)
            .set_request_tag("new_order")
            .build();
        let mut customer_result_set: ResultSet = transaction.execute_query(customer_query).await?;
        while let Some(row) = customer_result_set.next().await.transpose()? {
            let _: f64 = black_box(row.get(0_usize));
            let _: String = black_box(row.get(1_usize));
        }
        drop(customer_result_set);

        let mut statements = Vec::with_capacity(num_items as usize * 2 + 3);

        statements.push(
            Statement::builder("UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d")
                .add_param("next", &(next_order_id + 1))
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .set_request_tag("new_order")
                .build()
        );

        statements.push(
            Statement::builder("INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) VALUES (@w, @d, @o, @c, CURRENT_TIMESTAMP(), @cnt, 1)")
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .add_param("o", &next_order_id)
                .add_param("c", &customer_id)
                .add_param("cnt", &num_items)
                .set_request_tag("new_order")
                .build()
        );

        statements.push(
            Statement::builder("INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) VALUES (@w, @d, @o, CURRENT_TIMESTAMP())")
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .add_param("o", &next_order_id)
                .set_request_tag("new_order")
                .build()
        );

        for i in 0..num_items {
            let item_id = item_ids[i as usize];
            let quantity = quantities[i as usize];
            let order_line_id = i + 1;

            statements.push(
                Statement::builder("INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')")
                    .add_param("w", &warehouse_id)
                    .add_param("d", &district_id)
                    .add_param("o", &next_order_id)
                    .add_param("ol", &order_line_id)
                    .add_param("i", &item_id)
                    .add_param("qty", &quantity)
                    .add_param("amt", &25.0f64)
                    .set_request_tag("new_order")
                    .build()
            );

            statements.push(
                Statement::builder("UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 WHERE warehouse_id = @w AND item_id = @i")
                    .add_param("qty", &quantity)
                    .add_param("w", &warehouse_id)
                    .add_param("i", &item_id)
                    .set_request_tag("new_order")
                    .build()
            );
        }

        if !statements.is_empty() {
            let mut batch = BatchDml::builder();
            for stmt in statements {
                batch = batch.add_statement(stmt);
            }
            let batch = batch.set_request_tag("new_order");
            transaction.execute_batch_update(batch.build()).await?;
        }

        Ok(())
    }).await?;

    Ok(())
}

async fn execute_payment(
    client: DatabaseClient,
    scale_factor: i64,
    _extended: bool,
) -> anyhow::Result<()> {
    let builder = client
        .read_write_transaction()
        .set_transaction_tag("payment");
    let runner = builder.build().await?;
    runner.run(async move |transaction| {
        let warehouse_id = rand::random_range(1..=scale_factor);
        let district_id = rand::random_range(1..=10);
        let customer_id = rand::random_range(1..=3000);
        let amount = rand::random_range(1.0..=5000.0f64);

        let statements = vec![
            Statement::builder("UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w")
                .add_param("amt", &amount)
                .add_param("w", &warehouse_id)
                .set_request_tag("payment")
                .build(),
            Statement::builder("UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d")
                .add_param("amt", &amount)
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .set_request_tag("payment")
                .build(),
            Statement::builder("UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                .add_param("amt", &amount)
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .add_param("c", &customer_id)
                .set_request_tag("payment")
                .build(),
            Statement::builder("INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) VALUES (@w, @d, GENERATE_UUID(), @c, CURRENT_TIMESTAMP(), @amt, 'history')")
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .add_param("c", &customer_id)
                .add_param("amt", &amount)
                .set_request_tag("payment")
                .build(),
        ];

        let mut batch = BatchDml::builder();
        for stmt in statements {
            batch = batch.add_statement(stmt);
        }
        let batch = batch.set_request_tag("payment");
        transaction.execute_batch_update(batch.build()).await?;
        Ok(())
    }).await?;

    Ok(())
}

async fn execute_order_status(
    client: DatabaseClient,
    scale_factor: i64,
    extended: bool,
) -> anyhow::Result<()> {
    let warehouse_id = rand::random_range(1..=scale_factor);
    let district_id = rand::random_range(1..=10);
    let customer_id = rand::random_range(1..=3000);

    let mut builder = client.read_only_transaction();
    if extended {
        builder =
            builder.set_timestamp_bound(TimestampBound::exact_staleness(Duration::from_secs(15)));
    }
    let transaction = builder.build().await?;

    let customer_query = Statement::builder("SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
        .add_param("w", &warehouse_id)
        .add_param("d", &district_id)
        .add_param("c", &customer_id)
        .set_request_tag("order_status")
        .build();
    let mut customer_result_set: ResultSet = transaction.execute_query(customer_query).await?;
    while let Some(row) = customer_result_set.next().await.transpose()? {
        let _: f64 = black_box(row.get(0_usize));
        let _: String = black_box(row.get(1_usize));
        let _: String = black_box(row.get(2_usize));
    }
    drop(customer_result_set);

    let order_query = Statement::builder("SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1")
        .add_param("w", &warehouse_id)
        .add_param("d", &district_id)
        .add_param("c", &customer_id)
        .set_request_tag("order_status")
        .build();
    let mut order_result_set: ResultSet = transaction.execute_query(order_query).await?;
    let mut order_id_opt = None;
    if let Some(row) = order_result_set.next().await.transpose()? {
        let order_id: i64 = row.get(0_usize);
        order_id_opt = Some(order_id);
    }
    drop(order_result_set);

    if let Some(order_id) = order_id_opt {
        let line_query = Statement::builder("SELECT order_line_id, item_id, quantity, amount FROM order_line WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
            .add_param("o", &order_id)
            .set_request_tag("order_status")
            .build();
        let mut line_result_set: ResultSet = transaction.execute_query(line_query).await?;
        while let Some(row) = line_result_set.next().await.transpose()? {
            let _: i64 = black_box(row.get(0_usize));
            let _: i64 = black_box(row.get(1_usize));
            let _: i64 = black_box(row.get(2_usize));
            let _: f64 = black_box(row.get(3_usize));
        }
    }

    Ok(())
}

async fn execute_delivery(
    client: DatabaseClient,
    scale_factor: i64,
    _extended: bool,
) -> anyhow::Result<()> {
    let builder = client
        .read_write_transaction()
        .set_transaction_tag("delivery");
    let runner = builder.build().await?;
    runner.run(async move |transaction| {
        let warehouse_id = rand::random_range(1..=scale_factor);
        let carrier_id = rand::random_range(1..=10);

        let mut batch_statements = Vec::new();
        for district_id in 1..=10 {
            let new_orders_query = Statement::builder("SELECT order_id FROM new_orders WHERE warehouse_id = @w AND district_id = @d ORDER BY created_timestamp ASC LIMIT 1")
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .set_request_tag("delivery")
                .build();

            let mut new_orders_result_set: ResultSet = transaction.execute_query(new_orders_query).await?;
            let mut order_id_opt = None;
            if let Some(row) = new_orders_result_set.next().await.transpose()? {
                let order_id: i64 = row.get(0_usize);
                order_id_opt = Some(order_id);
            }
            drop(new_orders_result_set);

            if let Some(order_id) = order_id_opt {
                batch_statements.push(
                    Statement::builder("DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                        .add_param("w", &warehouse_id)
                        .add_param("d", &district_id)
                        .add_param("o", &order_id)
                        .set_request_tag("delivery")
                        .build()
                );
                batch_statements.push(
                    Statement::builder("UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                        .add_param("c", &carrier_id)
                        .add_param("w", &warehouse_id)
                        .add_param("d", &district_id)
                        .add_param("o", &order_id)
                        .set_request_tag("delivery")
                        .build()
                );
                batch_statements.push(
                    Statement::builder("UPDATE order_line SET delivery_date = CURRENT_TIMESTAMP() WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                        .add_param("w", &warehouse_id)
                        .add_param("d", &district_id)
                        .add_param("o", &order_id)
                        .set_request_tag("delivery")
                        .build()
                );
            }
        }

        if !batch_statements.is_empty() {
            let mut batch = BatchDml::builder();
            for stmt in batch_statements {
                batch = batch.add_statement(stmt);
            }
            let batch = batch.set_request_tag("delivery");
            transaction.execute_batch_update(batch.build()).await?;
        }

        Ok(())
    }).await?;

    Ok(())
}

async fn execute_stock_level(
    client: DatabaseClient,
    scale_factor: i64,
    extended: bool,
) -> anyhow::Result<()> {
    let warehouse_id = rand::random_range(1..=scale_factor);
    let district_id = rand::random_range(1..=10);
    let threshold = rand::random_range(15..=20);

    let mut builder = client.read_only_transaction();
    if extended {
        builder =
            builder.set_timestamp_bound(TimestampBound::exact_staleness(Duration::from_secs(15)));
    }
    let transaction = builder.build().await?;

    let district_query = Statement::builder(
        "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
    )
    .add_param("w", &warehouse_id)
    .add_param("d", &district_id)
    .set_request_tag("stock_level")
    .build();
    let mut district_result_set: ResultSet = transaction.execute_query(district_query).await?;
    let mut next_order_id_opt = None;
    if let Some(row) = district_result_set.next().await.transpose()? {
        let next_id: i64 = row.get(0_usize);
        next_order_id_opt = Some(next_id);
    }
    drop(district_result_set);

    if let Some(next_order_id) = next_order_id_opt {
        let min_order_id = std::cmp::max(1, next_order_id - 20);
        let stock_query = Statement::builder("SELECT COUNT(DISTINCT s.item_id) FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @min_order_id AND ol.order_id < @next_order_id AND s.quantity < @threshold")
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
            .add_param("min_order_id", &min_order_id)
            .add_param("next_order_id", &next_order_id)
            .add_param("threshold", &threshold)
            .set_request_tag("stock_level")
            .build();
        let mut stock_result_set: ResultSet = transaction.execute_query(stock_query).await?;
        while let Some(_) = stock_result_set.next().await.transpose()? {}
    }

    Ok(())
}

pub(crate) async fn run_tpcc_benchmark(
    client: DatabaseClient,
    warehouses: i64,
    clients: usize,
    items: i64,
    duration_opt: Option<Duration>,
    metrics: BenchmarkMetrics,
    base_attributes: Vec<KeyValue>,
    extended: bool,
) -> anyhow::Result<()> {
    println!(
        "Starting TPC-C Benchmark with Scale Factor (Warehouses): {}, Parallel Clients: {}, Items: {}{}",
        warehouses,
        clients,
        items,
        if extended { " [EXTENDED MODE]" } else { "" }
    );

    // Assert database capacity
    let single_use_tx = client.single_use().build();
    let capacity_statement = Statement::builder("SELECT COUNT(*) FROM warehouse").build();
    let mut count_result_set: ResultSet = single_use_tx.execute_query(capacity_statement).await?;
    if let Some(row) = count_result_set.next().await.transpose()? {
        let warehouse_count: i64 = row.get(0_usize);
        if warehouse_count < warehouses {
            anyhow::bail!(
                "Database capacity check failed: Required scale factor {} warehouses, but database only has {}",
                warehouses,
                warehouse_count
            );
        }
    }
    drop(count_result_set);

    let _start_time = Instant::now();
    let mut handles = Vec::with_capacity(clients);

    let mut attr_no = base_attributes.clone();
    attr_no.push(KeyValue::new("transaction_type", "new_order"));
    let attr_no = Arc::new(attr_no);

    let mut attr_no_mut = base_attributes.clone();
    attr_no_mut.push(KeyValue::new("transaction_type", "new_order_mutations"));
    let attr_no_mut = Arc::new(attr_no_mut);

    let mut attr_pm = base_attributes.clone();
    attr_pm.push(KeyValue::new("transaction_type", "payment"));
    let attr_pm = Arc::new(attr_pm);

    let mut attr_pm_dir = base_attributes.clone();
    attr_pm_dir.push(KeyValue::new(
        "transaction_type",
        "payment_mutations_direct",
    ));
    let attr_pm_dir = Arc::new(attr_pm_dir);

    let mut attr_os = base_attributes.clone();
    attr_os.push(KeyValue::new("transaction_type", "order_status"));
    let attr_os = Arc::new(attr_os);

    let mut attr_os_rd = base_attributes.clone();
    attr_os_rd.push(KeyValue::new("transaction_type", "order_status_reads"));
    let attr_os_rd = Arc::new(attr_os_rd);

    let mut attr_dl = base_attributes.clone();
    attr_dl.push(KeyValue::new("transaction_type", "delivery"));
    let attr_dl = Arc::new(attr_dl);

    let mut attr_sl = base_attributes.clone();
    attr_sl.push(KeyValue::new("transaction_type", "stock_level"));
    let attr_sl = Arc::new(attr_sl);

    let mut attr_sl_part = base_attributes.clone();
    attr_sl_part.push(KeyValue::new("transaction_type", "stock_level_partitioned"));
    let attr_sl_part = Arc::new(attr_sl_part);

    for _ in 0..clients {
        let db_client = client.clone();
        let metrics_clone = metrics.clone();
        let a_no = attr_no.clone();
        let a_no_mut = attr_no_mut.clone();
        let a_pm = attr_pm.clone();
        let a_pm_dir = attr_pm_dir.clone();
        let a_os = attr_os.clone();
        let a_os_rd = attr_os_rd.clone();
        let a_dl = attr_dl.clone();
        let a_sl = attr_sl.clone();
        let a_sl_part = attr_sl_part.clone();

        if let Some(duration) = duration_opt {
            handles.push(tokio::spawn(async move {
                let _ = tokio::time::timeout(
                    duration,
                    run_tpcc_worker_loop(
                        db_client,
                        warehouses,
                        items,
                        metrics_clone,
                        extended,
                        a_no,
                        a_no_mut,
                        a_pm,
                        a_pm_dir,
                        a_os,
                        a_os_rd,
                        a_dl,
                        a_sl,
                        a_sl_part,
                    ),
                )
                .await;
            }));
        } else {
            handles.push(tokio::spawn(run_tpcc_worker_loop(
                db_client,
                warehouses,
                items,
                metrics_clone,
                extended,
                a_no,
                a_no_mut,
                a_pm,
                a_pm_dir,
                a_os,
                a_os_rd,
                a_dl,
                a_sl,
                a_sl_part,
            )));
        }
    }

    for handle in handles {
        let _ = handle.await;
    }

    println!("TPC-C benchmark execution complete.");
    Ok(())
}

async fn run_tpcc_worker_loop(
    db_client: DatabaseClient,
    warehouses: i64,
    items: i64,
    metrics: BenchmarkMetrics,
    extended: bool,
    a_no: Arc<Vec<KeyValue>>,
    a_no_mut: Arc<Vec<KeyValue>>,
    a_pm: Arc<Vec<KeyValue>>,
    a_pm_dir: Arc<Vec<KeyValue>>,
    a_os: Arc<Vec<KeyValue>>,
    a_os_rd: Arc<Vec<KeyValue>>,
    a_dl: Arc<Vec<KeyValue>>,
    a_sl: Arc<Vec<KeyValue>>,
    a_sl_part: Arc<Vec<KeyValue>>,
) {
    loop {
        let prob = rand::random_range(0..100);
        let transaction_type;
        let item_attributes;
        let op_start = Instant::now();
        let res;

        if extended {
            if prob < 25 {
                transaction_type = "new_order";
                item_attributes = a_no.as_ref();
                res = execute_new_order(db_client.clone(), warehouses, items, true).await;
            } else if prob < 45 {
                transaction_type = "new_order_mutations";
                item_attributes = a_no_mut.as_ref();
                res = execute_new_order_mutations(db_client.clone(), warehouses, items).await;
            } else if prob < 78 {
                transaction_type = "payment";
                item_attributes = a_pm.as_ref();
                res = execute_payment(db_client.clone(), warehouses, true).await;
            } else if prob < 88 {
                transaction_type = "payment_mutations_direct";
                item_attributes = a_pm_dir.as_ref();
                res = execute_payment_mutations_direct(db_client.clone(), warehouses).await;
            } else if prob < 90 {
                transaction_type = "order_status";
                item_attributes = a_os.as_ref();
                res = execute_order_status(db_client.clone(), warehouses, true).await;
            } else if prob < 92 {
                transaction_type = "order_status_reads";
                item_attributes = a_os_rd.as_ref();
                res = execute_order_status_reads(db_client.clone(), warehouses).await;
            } else if prob < 96 {
                transaction_type = "delivery";
                item_attributes = a_dl.as_ref();
                res = execute_delivery(db_client.clone(), warehouses, true).await;
            } else if prob < 98 {
                transaction_type = "stock_level";
                item_attributes = a_sl.as_ref();
                res = execute_stock_level(db_client.clone(), warehouses, true).await;
            } else {
                transaction_type = "stock_level_partitioned";
                item_attributes = a_sl_part.as_ref();
                res = execute_stock_level_partitioned(db_client.clone(), warehouses).await;
            }
        } else {
            if prob < 45 {
                transaction_type = "new_order";
                item_attributes = a_no.as_ref();
                res = execute_new_order(db_client.clone(), warehouses, items, false).await;
            } else if prob < 88 {
                transaction_type = "payment";
                item_attributes = a_pm.as_ref();
                res = execute_payment(db_client.clone(), warehouses, false).await;
            } else if prob < 92 {
                transaction_type = "order_status";
                item_attributes = a_os.as_ref();
                res = execute_order_status(db_client.clone(), warehouses, false).await;
            } else if prob < 96 {
                transaction_type = "delivery";
                item_attributes = a_dl.as_ref();
                res = execute_delivery(db_client.clone(), warehouses, false).await;
            } else {
                transaction_type = "stock_level";
                item_attributes = a_sl.as_ref();
                res = execute_stock_level(db_client.clone(), warehouses, false).await;
            }
        }

        match res {
            Ok(_) => {
                let latency_us = op_start.elapsed().as_micros() as f64;
                metrics.latency.record(latency_us, item_attributes);
            }
            Err(e) => {
                eprintln!("TPC-C transaction {} failed: {:?}", transaction_type, e);
                metrics.error_count.add(1, item_attributes);
            }
        }
        metrics.operation_count.add(1, item_attributes);
    }
}

async fn execute_new_order_mutations(
    client: DatabaseClient,
    scale_factor: i64,
    total_items: i64,
) -> anyhow::Result<()> {
    let warehouse_id = rand::random_range(1..=scale_factor);
    let district_id = rand::random_range(1..=10);
    let customer_id = rand::random_range(1..=3000);
    let num_items = rand::random_range(5..=15i64);

    let mut item_ids = Vec::with_capacity(num_items as usize);
    let mut quantities = Vec::with_capacity(num_items as usize);
    for _ in 0..num_items {
        item_ids.push(rand::random_range(1..=total_items));
        quantities.push(rand::random_range(1..=10));
    }

    let runner = client
        .read_write_transaction()
        .set_transaction_tag("new_order_mutations")
        .build()
        .await?;

    runner
        .run(async move |transaction| {
            // 1. Read District next_order_id using execute_read
            let district_key = key![warehouse_id, district_id];
            let read_district = ReadRequest::builder("district", vec!["next_order_id"])
                .with_keys(district_key)
                .set_request_tag("new_order_mutations")
                .build();
            let mut result_set = transaction.execute_read(read_district).await?;
            let mut next_order_id = 1000i64;
            if let Some(row) = result_set.next().await.transpose()? {
                next_order_id = row.get(0_usize);
            }
            drop(result_set);

            // 2. Read Customer discount and last_name using execute_read
            let customer_key = key![warehouse_id, district_id, customer_id];
            let read_customer = ReadRequest::builder("customer", vec!["discount", "last_name"])
                .with_keys(customer_key)
                .set_request_tag("new_order_mutations")
                .build();
            let mut customer_result_set = transaction.execute_read(read_customer).await?;
            if let Some(row) = customer_result_set.next().await.transpose()? {
                let _: f64 = black_box(row.get(0_usize));
                let _: String = black_box(row.get(1_usize));
            }
            drop(customer_result_set);

            // 3. Read Stock quantities for all items in a single Read
            let mut stock_keys = KeySet::builder();
            for &item_id in &item_ids {
                stock_keys = stock_keys.add_key(key![warehouse_id, item_id]);
            }
            let read_stock = ReadRequest::builder("stock", vec!["item_id", "quantity"])
                .with_keys(stock_keys.build())
                .set_request_tag("new_order_mutations")
                .build();
            let mut stock_result_set = transaction.execute_read(read_stock).await?;
            let mut stock_quantities = std::collections::HashMap::new();
            while let Some(row) = stock_result_set.next().await.transpose()? {
                let item_id: i64 = row.get(0_usize);
                let quantity: i64 = row.get(1_usize);
                stock_quantities.insert(item_id, quantity);
            }
            drop(stock_result_set);

            // 4. Buffer mutations
            let now = time::OffsetDateTime::now_utc();
            let mut mutations = vec![
                Mutation::new_update_builder("district")
                    .set("warehouse_id")
                    .to(&warehouse_id)
                    .set("district_id")
                    .to(&district_id)
                    .set("next_order_id")
                    .to(&(next_order_id + 1))
                    .build(),
                Mutation::new_insert_builder("orders")
                    .set("warehouse_id")
                    .to(&warehouse_id)
                    .set("district_id")
                    .to(&district_id)
                    .set("order_id")
                    .to(&next_order_id)
                    .set("customer_id")
                    .to(&customer_id)
                    .set("entry_date")
                    .to(&now)
                    .set("item_count")
                    .to(&num_items)
                    .set("all_local")
                    .to(&1i64)
                    .build(),
                Mutation::new_insert_builder("new_orders")
                    .set("warehouse_id")
                    .to(&warehouse_id)
                    .set("district_id")
                    .to(&district_id)
                    .set("order_id")
                    .to(&next_order_id)
                    .set("created_timestamp")
                    .to(&now)
                    .build(),
            ];

            for i in 0..num_items {
                let item_id = item_ids[i as usize];
                let quantity = quantities[i as usize];
                let order_line_id = i + 1;
                let stock_qty = *stock_quantities.get(&item_id).unwrap_or(&10i64);
                let new_qty = stock_qty - quantity;

                mutations.push(
                    Mutation::new_insert_builder("order_line")
                        .set("warehouse_id")
                        .to(&warehouse_id)
                        .set("district_id")
                        .to(&district_id)
                        .set("order_id")
                        .to(&next_order_id)
                        .set("order_line_id")
                        .to(&order_line_id)
                        .set("item_id")
                        .to(&item_id)
                        .set("quantity")
                        .to(&quantity)
                        .set("amount")
                        .to(&25.0f64)
                        .set("dist_info")
                        .to(&"distinfo")
                        .build(),
                );
                mutations.push(
                    Mutation::new_update_builder("stock")
                        .set("warehouse_id")
                        .to(&warehouse_id)
                        .set("item_id")
                        .to(&item_id)
                        .set("quantity")
                        .to(&new_qty)
                        .build(),
                );
            }

            transaction.buffer(mutations)?;
            Ok(())
        })
        .await?;

    Ok(())
}

async fn execute_payment_mutations_direct(
    client: DatabaseClient,
    scale_factor: i64,
) -> anyhow::Result<()> {
    let warehouse_id = rand::random_range(1..=scale_factor);
    let district_id = rand::random_range(1..=10);
    let customer_id = rand::random_range(1..=3000);
    let amount = rand::random_range(1.0..=5000.0f64);

    let runner = client
        .read_write_transaction()
        .set_transaction_tag("payment_mutations_direct")
        .build()
        .await?;

    runner.run(async move |transaction| {
        let statements = vec![
            Statement::builder("UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w")
                .add_param("amt", &amount)
                .add_param("w", &warehouse_id)
                .build(),
            Statement::builder("UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d")
                .add_param("amt", &amount)
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .build(),
            Statement::builder("UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                .add_param("amt", &amount)
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .add_param("c", &customer_id)
                .build(),
        ];

        let mut batch = BatchDml::builder();
        for stmt in statements {
            batch = batch.add_statement(stmt);
        }
        let batch = batch.set_request_tag("payment_mutations_direct_batch_dml");
        transaction.execute_batch_update(batch.build()).await?;
        Ok(())
    })
    .await?;

    // Now write history mutation using write_only_transaction (direct apply)
    let history_id = uuid::Uuid::new_v4().to_string();
    let now = time::OffsetDateTime::now_utc();
    let history_mutation = Mutation::new_insert_builder("history")
        .set("warehouse_id")
        .to(&warehouse_id)
        .set("district_id")
        .to(&district_id)
        .set("history_id")
        .to(&history_id)
        .set("customer_id")
        .to(&customer_id)
        .set("date")
        .to(&now)
        .set("amount")
        .to(&amount)
        .set("data")
        .to(&"history")
        .build();

    let writer = client
        .write_only_transaction()
        .set_transaction_tag("payment_mutations_direct")
        .build();
    writer.write(vec![history_mutation]).await?;

    Ok(())
}

async fn execute_order_status_reads(
    client: DatabaseClient,
    scale_factor: i64,
) -> anyhow::Result<()> {
    let warehouse_id = rand::random_range(1..=scale_factor);
    let district_id = rand::random_range(1..=10);
    let customer_id = rand::random_range(1..=3000);

    let transaction = client
        .read_only_transaction()
        .set_timestamp_bound(TimestampBound::exact_staleness(Duration::from_secs(15)))
        .build()
        .await?;

    // 1. Read customer balance, first_name, last_name using execute_read
    let customer_key = key![warehouse_id, district_id, customer_id];
    let read_customer =
        ReadRequest::builder("customer", vec!["balance", "first_name", "last_name"])
            .with_keys(customer_key)
            .set_request_tag("order_status_reads")
            .build();
    let mut customer_result_set = transaction.execute_read(read_customer).await?;
    if let Some(row) = customer_result_set.next().await.transpose()? {
        let _: f64 = black_box(row.get(0_usize));
        let _: String = black_box(row.get(1_usize));
        let _: String = black_box(row.get(2_usize));
    }
    drop(customer_result_set);

    // 2. Query latest order ID
    let order_query = Statement::builder("SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1")
        .add_param("w", &warehouse_id)
        .add_param("d", &district_id)
        .add_param("c", &customer_id)
        .set_request_tag("order_status_reads")
        .build();
    let mut order_result_set = transaction.execute_query(order_query).await?;
    let mut order_id_opt = None;
    if let Some(row) = order_result_set.next().await.transpose()? {
        let order_id: i64 = row.get(0_usize);
        order_id_opt = Some(order_id);
    }
    drop(order_result_set);

    // 3. Read matching order lines using key range prefix
    if let Some(order_id) = order_id_opt {
        let start_key = key![warehouse_id, district_id, order_id];
        let end_key = key![warehouse_id, district_id, order_id + 1];
        let key_range = KeyRange::closed_open(start_key, end_key);
        let read_lines = ReadRequest::builder(
            "order_line",
            vec!["order_line_id", "item_id", "quantity", "amount"],
        )
        .with_keys(KeySet::from(key_range))
        .set_request_tag("order_status_reads")
        .build();
        let mut line_result_set = transaction.execute_read(read_lines).await?;
        while let Some(row) = line_result_set.next().await.transpose()? {
            let _: i64 = black_box(row.get(0_usize));
            let _: i64 = black_box(row.get(1_usize));
            let _: i64 = black_box(row.get(2_usize));
            let _: f64 = black_box(row.get(3_usize));
        }
    }

    Ok(())
}

async fn execute_stock_level_partitioned(
    client: DatabaseClient,
    scale_factor: i64,
) -> anyhow::Result<()> {
    let warehouse_id = rand::random_range(1..=scale_factor);
    let district_id = rand::random_range(1..=10);
    let threshold = rand::random_range(15..=20);

    let transaction = client
        .batch_read_only_transaction()
        .set_timestamp_bound(TimestampBound::exact_staleness(Duration::from_secs(15)))
        .build()
        .await?;

    // 1. Query district next_order_id inside BatchReadOnlyTransaction
    let district_query = Statement::builder(
        "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
    )
    .add_param("w", &warehouse_id)
    .add_param("d", &district_id)
    .set_request_tag("stock_level_partitioned")
    .build();
    let district_partitions = transaction
        .partition_query(district_query, PartitionOptions::default())
        .await?;
    let mut next_order_id_opt = None;
    for partition in district_partitions {
        let mut rs = partition.execute(&client).await?;
        if let Some(row) = rs.next().await.transpose()? {
            let next_id: i64 = row.get(0_usize);
            next_order_id_opt = Some(next_id);
            break;
        }
    }

    if let Some(next_order_id) = next_order_id_opt {
        let min_order_id = std::cmp::max(1, next_order_id - 20);
        let stock_query = Statement::builder("SELECT DISTINCT s.item_id FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @min_order_id AND ol.order_id < @next_order_id AND s.quantity < @threshold")
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
            .add_param("min_order_id", &min_order_id)
            .add_param("next_order_id", &next_order_id)
            .add_param("threshold", &threshold)
            .set_request_tag("stock_level_partitioned")
            .build();

        // 2. Partition query
        let partitions = transaction
            .partition_query(stock_query, PartitionOptions::default())
            .await?;

        // 3. Execute partitions in parallel using tokio::spawn
        let mut tasks = Vec::with_capacity(partitions.len());
        for partition in partitions {
            let client_clone = client.clone();
            tasks.push(tokio::spawn(async move {
                let mut rs = partition.execute(&client_clone).await?;
                let mut item_ids = Vec::new();
                while let Some(row) = rs.next().await.transpose()? {
                    let item_id: i64 = row.get(0_usize);
                    item_ids.push(item_id);
                }
                Ok::<_, anyhow::Error>(item_ids)
            }));
        }

        let mut unique_items = std::collections::HashSet::new();
        for task in tasks {
            let item_ids = task.await??;
            for id in item_ids {
                unique_items.insert(id);
            }
        }
        let _count = unique_items.len();
    }

    Ok(())
}
