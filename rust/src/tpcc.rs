use std::hint::black_box;
use google_cloud_spanner::client::DatabaseClient;
use google_cloud_spanner::statement::Statement;
use google_cloud_spanner::batch::BatchDml;
use google_cloud_spanner::result::ResultSet;
use std::sync::Arc;
use tokio::time::{Duration, Instant};
use opentelemetry::KeyValue;
use crate::BenchmarkMetrics;

async fn execute_new_order(client: DatabaseClient, scale_factor: i64, total_items: i64) -> anyhow::Result<()> {
    let runner = client.read_write_transaction().build().await?;
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

        let sql = "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d";
        let statement = Statement::builder(sql)
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
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
            .build();
        let mut customer_result_set: ResultSet = transaction.execute_query(customer_query).await?;
        while let Some(row) = customer_result_set.next().await.transpose()? {
            let _: f64 = black_box(row.get(0_usize));
            let _: String = black_box(row.get(1_usize));
        }
        drop(customer_result_set);

        let mut statements = Vec::with_capacity(num_items as usize * 2 + 3);

        statements.push(Statement::builder("UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d")
            .add_param("next", &(next_order_id + 1))
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
            .build());

        statements.push(Statement::builder("INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) VALUES (@w, @d, @o, @c, CURRENT_TIMESTAMP(), @cnt, 1)")
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
            .add_param("o", &next_order_id)
            .add_param("c", &customer_id)
            .add_param("cnt", &num_items)
            .build());

        statements.push(Statement::builder("INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) VALUES (@w, @d, @o, CURRENT_TIMESTAMP())")
            .add_param("w", &warehouse_id)
            .add_param("d", &district_id)
            .add_param("o", &next_order_id)
            .build());

        for i in 0..num_items {
            let item_id = item_ids[i as usize];
            let quantity = quantities[i as usize];
            let order_line_id = i + 1;

            statements.push(Statement::builder("INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')")
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .add_param("o", &next_order_id)
                .add_param("ol", &order_line_id)
                .add_param("i", &item_id)
                .add_param("qty", &quantity)
                .add_param("amt", &25.0f64)
                .build());

            statements.push(Statement::builder("UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 WHERE warehouse_id = @w AND item_id = @i")
                .add_param("qty", &quantity)
                .add_param("w", &warehouse_id)
                .add_param("i", &item_id)
                .build());
        }

        if !statements.is_empty() {
            let mut batch = BatchDml::builder();
            for stmt in statements {
                batch = batch.add_statement(stmt);
            }
            transaction.execute_batch_update(batch.build()).await?;
        }

        Ok(())
    }).await?;

    Ok(())
}

async fn execute_payment(client: DatabaseClient, scale_factor: i64) -> anyhow::Result<()> {
    let runner = client.read_write_transaction().build().await?;
    runner.run(async move |transaction| {
        let warehouse_id = rand::random_range(1..=scale_factor);
        let district_id = rand::random_range(1..=10);
        let customer_id = rand::random_range(1..=3000);
        let amount = rand::random_range(1.0..=5000.0f64);

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
            Statement::builder("INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) VALUES (@w, @d, GENERATE_UUID(), @c, CURRENT_TIMESTAMP(), @amt, 'history')")
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .add_param("c", &customer_id)
                .add_param("amt", &amount)
                .build(),
        ];

        let mut batch = BatchDml::builder();
        for stmt in statements {
            batch = batch.add_statement(stmt);
        }
        transaction.execute_batch_update(batch.build()).await?;
        Ok(())
    }).await?;

    Ok(())
}

async fn execute_order_status(client: DatabaseClient, scale_factor: i64) -> anyhow::Result<()> {
    let warehouse_id = rand::random_range(1..=scale_factor);
    let district_id = rand::random_range(1..=10);
    let customer_id = rand::random_range(1..=3000);

    let transaction = client.read_only_transaction().build().await?;

    let customer_query = Statement::builder("SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
        .add_param("w", &warehouse_id)
        .add_param("d", &district_id)
        .add_param("c", &customer_id)
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

async fn execute_delivery(client: DatabaseClient, scale_factor: i64) -> anyhow::Result<()> {
    let runner = client.read_write_transaction().build().await?;
    runner.run(async move |transaction| {
        let warehouse_id = rand::random_range(1..=scale_factor);
        let carrier_id = rand::random_range(1..=10);

        let mut batch_statements = Vec::new();
        for district_id in 1..=10 {
            let new_orders_query = Statement::builder("SELECT order_id FROM new_orders WHERE warehouse_id = @w AND district_id = @d ORDER BY created_timestamp ASC LIMIT 1")
                .add_param("w", &warehouse_id)
                .add_param("d", &district_id)
                .build();

            let mut new_orders_result_set: ResultSet = transaction.execute_query(new_orders_query).await?;
            let mut order_id_opt = None;
            if let Some(row) = new_orders_result_set.next().await.transpose()? {
                let order_id: i64 = row.get(0_usize);
                order_id_opt = Some(order_id);
            }
            drop(new_orders_result_set);

            if let Some(order_id) = order_id_opt {
                batch_statements.push(Statement::builder("DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                    .add_param("w", &warehouse_id)
                    .add_param("d", &district_id)
                    .add_param("o", &order_id)
                    .build());
                batch_statements.push(Statement::builder("UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                    .add_param("c", &carrier_id)
                    .add_param("w", &warehouse_id)
                    .add_param("d", &district_id)
                    .add_param("o", &order_id)
                    .build());
                batch_statements.push(Statement::builder("UPDATE order_line SET delivery_date = CURRENT_TIMESTAMP() WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                    .add_param("w", &warehouse_id)
                    .add_param("d", &district_id)
                    .add_param("o", &order_id)
                    .build());
            }
        }

        if !batch_statements.is_empty() {
            let mut batch = BatchDml::builder();
            for stmt in batch_statements {
                batch = batch.add_statement(stmt);
            }
            transaction.execute_batch_update(batch.build()).await?;
        }

        Ok(())
    }).await?;

    Ok(())
}

async fn execute_stock_level(client: DatabaseClient, scale_factor: i64) -> anyhow::Result<()> {
    let warehouse_id = rand::random_range(1..=scale_factor);
    let district_id = rand::random_range(1..=10);
    let threshold = rand::random_range(15..=20);

    let transaction = client.read_only_transaction().build().await?;

    let district_query = Statement::builder("SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d")
        .add_param("w", &warehouse_id)
        .add_param("d", &district_id)
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
) -> anyhow::Result<()> {
    println!("Starting TPC-C Benchmark with Scale Factor (Warehouses): {}, Parallel Clients: {}, Items: {}", warehouses, clients, items);

    // Assert database capacity
    let single_use_tx = client.single_use().build();
    let capacity_statement = Statement::builder("SELECT COUNT(*) FROM warehouse").build();
    let mut count_result_set: ResultSet = single_use_tx.execute_query(capacity_statement).await?;
    if let Some(row) = count_result_set.next().await.transpose()? {
        let warehouse_count: i64 = row.get(0_usize);
        if warehouse_count < warehouses {
            anyhow::bail!("Database capacity check failed: Required scale factor {} warehouses, but database only has {}", warehouses, warehouse_count);
        }
    }
    drop(count_result_set);

    let _start_time = Instant::now();
    let mut handles = Vec::with_capacity(clients);

    let mut attr_no = base_attributes.clone();
    attr_no.push(KeyValue::new("transaction_type", "new_order"));
    let attr_no = Arc::new(attr_no);

    let mut attr_pm = base_attributes.clone();
    attr_pm.push(KeyValue::new("transaction_type", "payment"));
    let attr_pm = Arc::new(attr_pm);

    let mut attr_os = base_attributes.clone();
    attr_os.push(KeyValue::new("transaction_type", "order_status"));
    let attr_os = Arc::new(attr_os);

    let mut attr_dl = base_attributes.clone();
    attr_dl.push(KeyValue::new("transaction_type", "delivery"));
    let attr_dl = Arc::new(attr_dl);

    let mut attr_sl = base_attributes.clone();
    attr_sl.push(KeyValue::new("transaction_type", "stock_level"));
    let attr_sl = Arc::new(attr_sl);

    for _ in 0..clients {
        let db_client = client.clone();
        let metrics_clone = metrics.clone();
        let a_no = attr_no.clone();
        let a_pm = attr_pm.clone();
        let a_os = attr_os.clone();
        let a_dl = attr_dl.clone();
        let a_sl = attr_sl.clone();

        if let Some(duration) = duration_opt {
            handles.push(tokio::spawn(async move {
                let _ = tokio::time::timeout(duration, run_tpcc_worker_loop(db_client, warehouses, items, metrics_clone, a_no, a_pm, a_os, a_dl, a_sl)).await;
            }));
        } else {
            handles.push(tokio::spawn(run_tpcc_worker_loop(db_client, warehouses, items, metrics_clone, a_no, a_pm, a_os, a_dl, a_sl)));
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
    a_no: Arc<Vec<KeyValue>>,
    a_pm: Arc<Vec<KeyValue>>,
    a_os: Arc<Vec<KeyValue>>,
    a_dl: Arc<Vec<KeyValue>>,
    a_sl: Arc<Vec<KeyValue>>,
) {
    loop {
        let prob = rand::random_range(0..100);
        let transaction_type;
        let item_attributes;
        let op_start = Instant::now();
        let res;

        if prob < 45 {
            transaction_type = "new_order";
            item_attributes = a_no.as_ref();
            res = execute_new_order(db_client.clone(), warehouses, items).await;
        } else if prob < 88 {
            transaction_type = "payment";
            item_attributes = a_pm.as_ref();
            res = execute_payment(db_client.clone(), warehouses).await;
        } else if prob < 92 {
            transaction_type = "order_status";
            item_attributes = a_os.as_ref();
            res = execute_order_status(db_client.clone(), warehouses).await;
        } else if prob < 96 {
            transaction_type = "delivery";
            item_attributes = a_dl.as_ref();
            res = execute_delivery(db_client.clone(), warehouses).await;
        } else {
            transaction_type = "stock_level";
            item_attributes = a_sl.as_ref();
            res = execute_stock_level(db_client.clone(), warehouses).await;
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
