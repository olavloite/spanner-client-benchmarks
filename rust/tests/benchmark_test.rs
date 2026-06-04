use clap::Parser;
use opentelemetry_sdk::metrics::{InMemoryMetricExporter, SdkMeterProvider};
use spanner_grpc_mock::google::spanner::v1 as mock_v1;
use spanner_grpc_mock::{MockSpanner, start};
use spanner_rust_benchmark::{Args, TEST_METER_PROVIDER, run_benchmark};
use tonic::Response;

fn mock_field(name: &str, code: mock_v1::TypeCode) -> mock_v1::struct_type::Field {
    mock_v1::struct_type::Field {
        name: name.to_string(),
        r#type: Some(mock_v1::Type {
            code: code as i32,
            array_element_type: None,
            struct_type: None,
            type_annotation: 0,
            proto_type_fqn: "".to_string(),
        }),
    }
}

fn string_value(val: &str) -> prost_types::Value {
    prost_types::Value {
        kind: Some(prost_types::value::Kind::StringValue(val.to_string())),
    }
}

fn bool_value(val: bool) -> prost_types::Value {
    prost_types::Value {
        kind: Some(prost_types::value::Kind::BoolValue(val)),
    }
}

fn number_value(val: f64) -> prost_types::Value {
    prost_types::Value {
        kind: Some(prost_types::value::Kind::NumberValue(val)),
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn test_benchmark_workloads() -> anyhow::Result<()> {
    // 1. Setup Mock Spanner Server
    let mut mock = MockSpanner::new();

    mock.expect_create_session().returning(|_| {
        Ok(Response::new(mock_v1::Session {
            name: "projects/p/instances/i/databases/d/sessions/123".to_string(),
            ..Default::default()
        }))
    });

    mock.expect_begin_transaction().returning(|_| {
        Ok(Response::new(mock_v1::Transaction {
            id: vec![1, 2, 3],
            ..Default::default()
        }))
    });

    mock.expect_commit().returning(|_| {
        Ok(Response::new(mock_v1::CommitResponse {
            commit_timestamp: Some(prost_types::Timestamp {
                seconds: 12345,
                nanos: 0,
            }),
            ..Default::default()
        }))
    });

    mock.expect_execute_batch_dml().returning(|req| {
        let req = req.into_inner();
        let count = req.statements.len();
        let transaction = req
            .transaction
            .and_then(|t| t.selector)
            .and_then(|s| match s {
                mock_v1::transaction_selector::Selector::Begin(_)
                | mock_v1::transaction_selector::Selector::Id(_) => Some(mock_v1::Transaction {
                    id: vec![1, 2, 3],
                    ..Default::default()
                }),
                _ => None,
            });
        let result_sets = (0..count)
            .map(|_| mock_v1::ResultSet {
                stats: Some(mock_v1::ResultSetStats {
                    row_count: Some(mock_v1::result_set_stats::RowCount::RowCountExact(1)),
                    ..Default::default()
                }),
                metadata: Some(mock_v1::ResultSetMetadata {
                    transaction: transaction.clone(),
                    ..Default::default()
                }),
                ..Default::default()
            })
            .collect();
        Ok(Response::new(mock_v1::ExecuteBatchDmlResponse {
            result_sets,
            status: Some(spanner_grpc_mock::google::rpc::Status {
                code: 0,
                message: "OK".to_string(),
                ..Default::default()
            }),
            ..Default::default()
        }))
    });

    mock.expect_execute_sql().returning(|req| {
        let req = req.into_inner();
        let transaction = req
            .transaction
            .and_then(|t| t.selector)
            .and_then(|s| match s {
                mock_v1::transaction_selector::Selector::Begin(_)
                | mock_v1::transaction_selector::Selector::Id(_) => Some(mock_v1::Transaction {
                    id: vec![1, 2, 3],
                    ..Default::default()
                }),
                _ => None,
            });
        Ok(Response::new(mock_v1::ResultSet {
            stats: Some(mock_v1::ResultSetStats {
                row_count: Some(mock_v1::result_set_stats::RowCount::RowCountExact(1)),
                ..Default::default()
            }),
            metadata: Some(mock_v1::ResultSetMetadata {
                transaction,
                ..Default::default()
            }),
            ..Default::default()
        }))
    });

    mock.expect_execute_streaming_sql().returning(move |req| {
        let req = req.into_inner();
        let sql = req.sql;
        let transaction = req
            .transaction
            .and_then(|t| t.selector)
            .and_then(|s| match s {
                mock_v1::transaction_selector::Selector::Begin(_)
                | mock_v1::transaction_selector::Selector::Id(_) => Some(mock_v1::Transaction {
                    id: vec![1, 2, 3],
                    ..Default::default()
                }),
                _ => None,
            });
        let (tx, rx) = tokio::sync::mpsc::channel(1);

        let mut result_set = if sql.contains("SELECT id, value FROM") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![
                            mock_field("id", mock_v1::TypeCode::Int64),
                            mock_field("value", mock_v1::TypeCode::String),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1"), string_value("test-value")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("SELECT id FROM") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![mock_field("id", mock_v1::TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("random_bool") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![
                            mock_field("random_bool", mock_v1::TypeCode::Bool),
                            mock_field("random_bytes", mock_v1::TypeCode::Bytes),
                            mock_field("random_date", mock_v1::TypeCode::Date),
                            mock_field("random_float32", mock_v1::TypeCode::Float32),
                            mock_field("random_float64", mock_v1::TypeCode::Float64),
                            mock_field("random_json", mock_v1::TypeCode::Json),
                            mock_field("random_int64", mock_v1::TypeCode::Int64),
                            mock_field("random_string", mock_v1::TypeCode::String),
                            mock_field("random_timestamp", mock_v1::TypeCode::Timestamp),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![
                    bool_value(true),
                    string_value("YWJj"),
                    string_value("2026-06-02"),
                    number_value(1.23),
                    number_value(4.56),
                    string_value("{\"key\": \"val\"}"),
                    string_value("100"),
                    string_value("hello"),
                    string_value("2026-06-02T13:43:09Z"),
                ],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("SELECT COUNT(*) FROM warehouse") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![mock_field("count", mock_v1::TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("SELECT next_order_id FROM district") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![mock_field("next_order_id", mock_v1::TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1000")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("discount, last_name FROM customer") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![
                            mock_field("discount", mock_v1::TypeCode::Float64),
                            mock_field("last_name", mock_v1::TypeCode::String),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![number_value(0.10), string_value("last_name")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("balance, first_name, last_name FROM customer") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![
                            mock_field("balance", mock_v1::TypeCode::Float64),
                            mock_field("first_name", mock_v1::TypeCode::String),
                            mock_field("last_name", mock_v1::TypeCode::String),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![
                    number_value(100.0),
                    string_value("first"),
                    string_value("last"),
                ],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("order_id FROM orders") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![mock_field("order_id", mock_v1::TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("5")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("order_line_id, item_id, quantity, amount") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![
                            mock_field("order_line_id", mock_v1::TypeCode::Int64),
                            mock_field("item_id", mock_v1::TypeCode::Int64),
                            mock_field("quantity", mock_v1::TypeCode::Int64),
                            mock_field("amount", mock_v1::TypeCode::Float64),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![
                    string_value("1"),
                    string_value("123"),
                    string_value("5"),
                    number_value(25.0),
                ],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("order_id FROM new_orders") {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![mock_field("order_id", mock_v1::TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("5")],
                last: true,
                ..Default::default()
            }
        } else {
            mock_v1::PartialResultSet {
                last: true,
                ..Default::default()
            }
        };

        if let Some(meta) = &mut result_set.metadata {
            meta.transaction = transaction;
        } else {
            result_set.metadata = Some(mock_v1::ResultSetMetadata {
                transaction,
                ..Default::default()
            });
        }
        tx.try_send(Ok(result_set))
            .expect("Failed to send mock result_set");

        Ok(Response::from(rx))
    });

    mock.expect_streaming_read().returning(move |req| {
        let req = req.into_inner();
        let table = req.table;
        let transaction = req
            .transaction
            .and_then(|t| t.selector)
            .and_then(|s| match s {
                mock_v1::transaction_selector::Selector::Begin(_)
                | mock_v1::transaction_selector::Selector::Id(_) => Some(mock_v1::Transaction {
                    id: vec![1, 2, 3],
                    ..Default::default()
                }),
                _ => None,
            });
        let (tx, rx) = tokio::sync::mpsc::channel(1);
        let mut result_set = if table == "district" {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![mock_field("next_order_id", mock_v1::TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1000")],
                last: true,
                ..Default::default()
            }
        } else if table == "customer" {
            if req.columns.len() == 2 {
                mock_v1::PartialResultSet {
                    metadata: Some(mock_v1::ResultSetMetadata {
                        row_type: Some(mock_v1::StructType {
                            fields: vec![
                                mock_field("discount", mock_v1::TypeCode::Float64),
                                mock_field("last_name", mock_v1::TypeCode::String),
                            ],
                        }),
                        ..Default::default()
                    }),
                    values: vec![number_value(0.10), string_value("last_name")],
                    last: true,
                    ..Default::default()
                }
            } else {
                mock_v1::PartialResultSet {
                    metadata: Some(mock_v1::ResultSetMetadata {
                        row_type: Some(mock_v1::StructType {
                            fields: vec![
                                mock_field("balance", mock_v1::TypeCode::Float64),
                                mock_field("first_name", mock_v1::TypeCode::String),
                                mock_field("last_name", mock_v1::TypeCode::String),
                            ],
                        }),
                        ..Default::default()
                    }),
                    values: vec![
                        number_value(100.0),
                        string_value("first"),
                        string_value("last"),
                    ],
                    last: true,
                    ..Default::default()
                }
            }
        } else if table == "stock" {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![
                            mock_field("item_id", mock_v1::TypeCode::Int64),
                            mock_field("quantity", mock_v1::TypeCode::Int64),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("123"), string_value("50")],
                last: true,
                ..Default::default()
            }
        } else if table == "order_line" {
            mock_v1::PartialResultSet {
                metadata: Some(mock_v1::ResultSetMetadata {
                    row_type: Some(mock_v1::StructType {
                        fields: vec![
                            mock_field("order_line_id", mock_v1::TypeCode::Int64),
                            mock_field("item_id", mock_v1::TypeCode::Int64),
                            mock_field("quantity", mock_v1::TypeCode::Int64),
                            mock_field("amount", mock_v1::TypeCode::Float64),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![
                    string_value("1"),
                    string_value("123"),
                    string_value("5"),
                    number_value(25.0),
                ],
                last: true,
                ..Default::default()
            }
        } else {
            mock_v1::PartialResultSet {
                last: true,
                ..Default::default()
            }
        };

        if let Some(meta) = &mut result_set.metadata {
            meta.transaction = transaction;
        } else {
            result_set.metadata = Some(mock_v1::ResultSetMetadata {
                transaction,
                ..Default::default()
            });
        }
        tx.try_send(Ok(result_set))
            .expect("Failed to send mock result_set");

        Ok(Response::from(rx))
    });

    let (address, _server) = start("127.0.0.1:0", mock)
        .await
        .expect("Failed to start mock server");
    unsafe {
        std::env::set_var("SPANNER_EMULATOR_HOST", &address);
    }

    // 2. Initialize InMemory Metric Exporter & SdkMeterProvider
    let exporter = InMemoryMetricExporter::default();
    let resource = opentelemetry_sdk::Resource::builder_empty()
        .with_attributes(vec![opentelemetry::KeyValue::new(
            "service.name",
            "spanner-benchmark-test",
        )])
        .build();

    let provider = SdkMeterProvider::builder()
        .with_resource(resource)
        .with_reader(opentelemetry_sdk::metrics::periodic_reader_with_async_runtime::PeriodicReader::builder(exporter.clone(), opentelemetry_sdk::runtime::Tokio).build())
        .build();

    // Register test meter provider globally in our library OnceLock
    let _ = TEST_METER_PROVIDER.set(provider.clone());

    // 3. Test PointSelect Workload
    {
        let args = Args::try_parse_from(vec![
            "benchmark",
            "--project",
            "test-project",
            "--instance",
            "test-instance",
            "--database",
            "test-database",
            "--table",
            "test",
            "--duration",
            "1s",
            "--host",
            &address,
            "--threads",
            "2",
            "--resource-probe-interval",
            "10ms",
            "point-select",
            "--tps",
            "10",
        ])?;

        run_benchmark(args).await?;
    }

    // 4. Test SelectUpdate Workload
    {
        let args = Args::try_parse_from(vec![
            "benchmark",
            "--project",
            "test-project",
            "--instance",
            "test-instance",
            "--database",
            "test-database",
            "--table",
            "test",
            "--duration",
            "1s",
            "--host",
            &address,
            "--threads",
            "2",
            "--resource-probe-interval",
            "10ms",
            "select-update",
            "--tps",
            "10",
        ])?;

        run_benchmark(args).await?;
    }

    // 5. Test ReadLargeResultSet Workload
    {
        let args = Args::try_parse_from(vec![
            "benchmark",
            "--project",
            "test-project",
            "--instance",
            "test-instance",
            "--database",
            "test-database",
            "--table",
            "test",
            "--duration",
            "1s",
            "--host",
            &address,
            "--threads",
            "1",
            "--resource-probe-interval",
            "10ms",
            "read-large-result-set",
            "--tps",
            "10",
        ])?;

        run_benchmark(args).await?;
    }

    // 6. Test Tpcc Workload
    {
        let args = Args::try_parse_from(vec![
            "benchmark",
            "--project",
            "test-project",
            "--instance",
            "test-instance",
            "--database",
            "test-database",
            "--duration",
            "1s",
            "--host",
            &address,
            "--threads",
            "2",
            "--resource-probe-interval",
            "10ms",
            "tpcc",
            "--warehouses",
            "1",
            "--clients",
            "2",
        ])?;

        run_benchmark(args).await?;
    }

    // 7. Test Tpcc Extended Workload
    {
        let args = Args::try_parse_from(vec![
            "benchmark",
            "--project",
            "test-project",
            "--instance",
            "test-instance",
            "--database",
            "test-database",
            "--duration",
            "1s",
            "--host",
            &address,
            "--threads",
            "2",
            "--resource-probe-interval",
            "10ms",
            "tpcc",
            "--warehouses",
            "1",
            "--clients",
            "2",
            "--extended",
            "true",
        ])?;

        run_benchmark(args).await?;
    }

    // 8. Assert metrics have been emitted correctly
    provider.force_flush()?;
    let metrics = exporter.get_finished_metrics()?;
    assert!(
        !metrics.is_empty(),
        "Metric collection is unexpectedly empty; should have collected metrics"
    );

    let mut found_operation_count = false;
    let mut found_latency = false;
    let mut found_memory = false;
    let mut found_cpu = false;

    for rm in metrics {
        for sm in rm.scope_metrics() {
            for m in sm.metrics() {
                let debug_str = format!("{:?}", m);
                if m.name() == "spanner_client_benchmarks/operation_count" {
                    found_operation_count = true;
                    assert!(
                        debug_str.contains("rust-client"),
                        "Metric spanner_client_benchmarks/operation_count did not contain expected 'rust-client' client attribute: {}",
                        debug_str
                    );
                    assert!(
                        debug_str.contains("benchmark_type"),
                        "Metric spanner_client_benchmarks/operation_count did not contain 'benchmark_type' attribute: {}",
                        debug_str
                    );
                }
                if m.name() == "spanner_client_benchmarks/latency"
                    || m.name() == "spanner_client_benchmarks/read_latency"
                {
                    found_latency = true;
                    assert!(
                        debug_str.contains("rust-client"),
                        "Metric {} did not contain expected 'rust-client' client attribute: {}",
                        m.name(),
                        debug_str
                    );
                    assert!(
                        debug_str.contains("benchmark_type"),
                        "Metric {} did not contain 'benchmark_type' attribute: {}",
                        m.name(),
                        debug_str
                    );
                }
                if m.name() == "spanner_client_benchmarks/memory_usage" {
                    found_memory = true;
                    assert!(
                        debug_str.contains("rust-client"),
                        "Metric spanner_client_benchmarks/memory_usage did not contain expected 'rust-client' client attribute: {}",
                        debug_str
                    );
                    assert!(
                        debug_str.contains("benchmark_type"),
                        "Metric spanner_client_benchmarks/memory_usage did not contain 'benchmark_type' attribute: {}",
                        debug_str
                    );
                }
                if m.name() == "spanner_client_benchmarks/cpu_utilization" {
                    found_cpu = true;
                    assert!(
                        debug_str.contains("rust-client"),
                        "Metric spanner_client_benchmarks/cpu_utilization did not contain expected 'rust-client' client attribute: {}",
                        debug_str
                    );
                    assert!(
                        debug_str.contains("benchmark_type"),
                        "Metric spanner_client_benchmarks/cpu_utilization did not contain 'benchmark_type' attribute: {}",
                        debug_str
                    );
                }
            }
        }
    }

    assert!(
        found_operation_count,
        "Failed to find spanner_client_benchmarks/operation_count metric in the collected metrics"
    );
    assert!(
        found_latency,
        "Failed to find spanner_client_benchmarks/latency or read_latency metric in the collected metrics"
    );
    assert!(
        found_memory,
        "Failed to find spanner_client_benchmarks/memory_usage metric in the collected metrics"
    );
    assert!(
        found_cpu,
        "Failed to find spanner_client_benchmarks/cpu_utilization metric in the collected metrics"
    );

    drop(_server);
    provider.shutdown()?;
    Ok(())
}
