pub mod load_type;
pub mod point_select;
pub mod read_large_result_set;
pub mod resource_monitor;
pub mod select_update;
pub mod tpcc;

use clap::{ArgAction, Parser, Subcommand};
use futures::FutureExt;
use google_cloud_spanner::client::Spanner;
use load_type::{LoadType, RunConfig};
use opentelemetry::KeyValue;
use opentelemetry::metrics::{Counter, Histogram, MeterProvider};
use opentelemetry_gcloud_monitoring_exporter::{GCPMetricsExporter, GCPMetricsExporterConfig};
use opentelemetry_sdk::metrics::{Aggregation, Instrument, SdkMeterProvider, Stream};
use std::sync::Arc;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio::time::Instant;

use google_cloud_auth::credentials::anonymous;
use prost_types::{Value, value::Kind};
use spanner_grpc_mock::MockSpanner;
use spanner_grpc_mock::google::spanner::v1::{
    CommitResponse, ExecuteBatchDmlResponse, PartialResultSet, ResultSet, ResultSetMetadata,
    ResultSetStats, Session, StructType, Transaction, Type, TypeCode, result_set_stats::RowCount,
    struct_type::Field, transaction_selector::Selector,
};

pub static TEST_METER_PROVIDER: std::sync::OnceLock<SdkMeterProvider> = std::sync::OnceLock::new();

#[derive(Parser, Debug, Clone)]
#[command(
    name = "BenchmarkApp",
    about = "Spanner client library benchmark tool for Rust."
)]
pub struct Args {
    #[arg(short, long)]
    pub project: String,
    #[arg(short, long)]
    pub instance: String,
    #[arg(short, long)]
    pub database: String,
    #[arg(short, long, global = true)]
    pub table: Option<String>,
    #[arg(long, default_value = "inf")]
    pub duration: String,
    #[arg(long, default_value_t = false, action = ArgAction::Set)]
    pub for_alerting: bool,
    #[arg(long)]
    pub benchmark_name: Option<String>,
    #[arg(long)]
    pub host: Option<String>,
    #[arg(long, global = true, default_value_t = 10)]
    pub threads: usize,
    #[arg(long, global = true, value_enum, default_value_t = LoadType::Steady)]
    pub load_type: LoadType,
    #[arg(long, global = true)]
    pub cycle_duration: Option<String>,
    #[arg(long, global = true)]
    pub peak_factor: Option<f64>,
    #[arg(long, global = true)]
    pub burst_factor: Option<f64>,
    #[arg(long, global = true)]
    pub burst_duration: Option<f64>,
    #[arg(long, global = true)]
    pub burst_fraction: Option<f64>,
    #[arg(long, global = true, default_value = "10s")]
    pub resource_probe_interval: String,
    #[arg(long, action = ArgAction::SetTrue)]
    pub mock: bool,
    #[arg(long, action = ArgAction::SetTrue)]
    pub no_metrics: bool,
    #[command(subcommand)]
    pub command: Commands,
}

#[derive(Subcommand, Debug, Clone)]
pub enum Commands {
    PointSelect {
        #[arg(long, default_value_t = 10.0)]
        tps: f64,
        #[arg(long, default_value_t = 1000000)]
        num_rows: i64,
    },
    SelectUpdate {
        #[arg(long, default_value_t = 10.0)]
        tps: f64,
        #[arg(long, default_value_t = 1000000)]
        num_rows: i64,
    },
    ReadLargeResultSet {
        #[arg(long, default_value_t = 0.05)]
        tps: f64,
        #[arg(long, default_value_t = 100000)]
        num_rows: i64,
    },
    Tpcc {
        #[arg(long, default_value_t = 1)]
        warehouses: i64,
        #[arg(long, default_value_t = 10)]
        clients: usize,
        #[arg(long, default_value_t = 100000)]
        items: i64,
        #[arg(long, default_value_t = false, action = ArgAction::Set)]
        extended: bool,
    },
}

#[derive(Clone)]
pub struct BenchmarkMetrics {
    pub latency: Histogram<f64>,
    pub read_latency: Histogram<f64>,
    pub operation_count: Counter<u64>,
    pub error_count: Counter<u64>,
    pub memory_usage: Histogram<f64>,
    pub cpu_utilization: Histogram<f64>,
}

fn get_latency_buckets() -> Vec<f64> {
    let mut buckets = Vec::with_capacity(120);
    for i in 1..=100 {
        buckets.push(i as f64 * 50.0);
    }
    buckets.extend_from_slice(&[
        6000.0, 7000.0, 8000.0, 9000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0, 20000.0,
        25000.0, 30000.0, 40000.0, 50000.0, 75000.0, 100000.0, 150000.0, 200000.0,
    ]);
    buckets
}

fn create_benchmark_metrics(provider: &SdkMeterProvider) -> BenchmarkMetrics {
    let meter = provider.meter("spanner-benchmark");
    let latency = meter
        .f64_histogram("spanner_client_benchmarks/latency")
        .with_description("Query latency in microseconds")
        .with_unit("us")
        .build();
    let read_latency = meter
        .f64_histogram("spanner_client_benchmarks/read_latency")
        .with_description("Query latency in microseconds")
        .with_unit("us")
        .build();
    let operation_count = meter
        .u64_counter("spanner_client_benchmarks/operation_count")
        .with_description("Total number of benchmark operations executed")
        .with_unit("1")
        .build();
    let error_count = meter
        .u64_counter("spanner_client_benchmarks/error_count")
        .with_description("Total number of benchmark operations that failed with an error")
        .with_unit("1")
        .build();
    let memory_usage = meter
        .f64_histogram("spanner_client_benchmarks/memory_usage")
        .with_description("Active memory usage in bytes")
        .with_unit("By")
        .build();
    let cpu_utilization = meter
        .f64_histogram("spanner_client_benchmarks/cpu_utilization")
        .with_description("Process CPU utilization")
        .with_unit("1")
        .build();
    BenchmarkMetrics {
        latency,
        read_latency,
        operation_count,
        error_count,
        memory_usage,
        cpu_utilization,
    }
}

async fn setup_metrics(
    project_id: &str,
    benchmark_name: Option<String>,
    no_metrics: bool,
) -> anyhow::Result<(BenchmarkMetrics, SdkMeterProvider, bool)> {
    if let Some(provider) = TEST_METER_PROVIDER.get() {
        let metrics = create_benchmark_metrics(provider);
        return Ok((metrics, provider.clone(), false));
    }

    if no_metrics {
        let provider = SdkMeterProvider::builder().build();
        let metrics = create_benchmark_metrics(&provider);
        return Ok((metrics, provider, true));
    }

    let config = GCPMetricsExporterConfig {
        project_id: Some(project_id.to_string()),
        ..Default::default()
    };
    let exporter = GCPMetricsExporter::init(config)
        .await
        .map_err(|e| anyhow::anyhow!("{:?}", e))?;

    let service_name = benchmark_name.unwrap_or_else(|| "spanner-benchmark".to_string());
    let instance_id = uuid::Uuid::new_v4().to_string();
    let resource = opentelemetry_sdk::Resource::builder()
        .with_attributes(vec![
            KeyValue::new("service.name", service_name),
            KeyValue::new("service.instance.id", instance_id),
        ])
        .build();

    let provider = SdkMeterProvider::builder()
        .with_resource(resource)
        .with_reader(opentelemetry_sdk::metrics::periodic_reader_with_async_runtime::PeriodicReader::builder(exporter, opentelemetry_sdk::runtime::Tokio).build())
        .with_view(|i: &Instrument| {
            if i.name() == "spanner_client_benchmarks/latency" {
                Some(Stream::builder()
                    .with_aggregation(Aggregation::ExplicitBucketHistogram {
                        boundaries: get_latency_buckets(),
                        record_min_max: true,
                    })
                    .build()
                    .unwrap())
            } else if i.name() == "spanner_client_benchmarks/read_latency" {
                Some(Stream::builder()
                    .with_aggregation(Aggregation::ExplicitBucketHistogram {
                        boundaries: vec![
                            50000.0, 100000.0, 250000.0, 500000.0, 750000.0,
                            1000000.0, 1250000.0, 1500000.0, 1750000.0, 2000000.0, 2250000.0, 2500000.0, 2750000.0, 3000000.0, 3250000.0, 3500000.0, 3750000.0, 4000000.0, 4250000.0, 4500000.0, 4750000.0, 5000000.0,
                            5500000.0, 6000000.0, 6500000.0, 7000000.0, 7500000.0, 8000000.0, 8500000.0, 9000000.0, 9500000.0, 10000000.0,
                            12500000.0, 15000000.0, 20000000.0, 30000000.0,
                        ],
                        record_min_max: true,
                    })
                    .build()
                    .unwrap())
            } else if i.name() == "spanner_client_benchmarks/memory_usage" {
                const MB: f64 = 1024.0 * 1024.0;
                Some(Stream::builder()
                    .with_aggregation(Aggregation::ExplicitBucketHistogram {
                        boundaries: vec![
                            2.5 * MB, 5.0 * MB, 7.5 * MB, 10.0 * MB, 20.0 * MB, 30.0 * MB, 40.0 * MB, 50.0 * MB, 60.0 * MB, 70.0 * MB, 80.0 * MB, 90.0 * MB, 100.0 * MB,
                            200.0 * MB, 300.0 * MB, 400.0 * MB, 500.0 * MB, 750.0 * MB, 1000.0 * MB, 1500.0 * MB, 2000.0 * MB, 3000.0 * MB, 5000.0 * MB, 10000.0 * MB
                        ],
                        record_min_max: true,
                    })
                    .build()
                    .unwrap())
            } else if i.name() == "spanner_client_benchmarks/cpu_utilization" {
                Some(Stream::builder()
                    .with_aggregation(Aggregation::ExplicitBucketHistogram {
                        boundaries: vec![0.01, 0.02, 0.03, 0.04, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 1.0],
                        record_min_max: true,
                    })
                    .build()
                    .unwrap())
            } else {
                None
            }
        })
        .build();

    let meter = provider.meter("spanner-benchmark");

    let latency = meter
        .f64_histogram("spanner_client_benchmarks/latency")
        .with_description("Query latency in microseconds")
        .with_unit("us")
        .build();

    let read_latency = meter
        .f64_histogram("spanner_client_benchmarks/read_latency")
        .with_description("Query latency in microseconds")
        .with_unit("us")
        .build();

    let operation_count = meter
        .u64_counter("spanner_client_benchmarks/operation_count")
        .with_description("Total number of benchmark operations executed")
        .with_unit("1")
        .build();

    let error_count = meter
        .u64_counter("spanner_client_benchmarks/error_count")
        .with_description("Total number of benchmark operations that failed with an error")
        .with_unit("1")
        .build();

    let memory_usage = meter
        .f64_histogram("spanner_client_benchmarks/memory_usage")
        .with_description("Active memory usage in bytes")
        .with_unit("By")
        .build();

    let cpu_utilization = meter
        .f64_histogram("spanner_client_benchmarks/cpu_utilization")
        .with_description("Process CPU utilization")
        .with_unit("1")
        .build();

    Ok((
        BenchmarkMetrics {
            latency,
            read_latency,
            operation_count,
            error_count,
            memory_usage,
            cpu_utilization,
        },
        provider,
        true,
    ))
}

pub fn run_task(
    db_client: google_cloud_spanner::client::DatabaseClient,
    table: String,
    command: Commands,
    permit: OwnedSemaphorePermit,
    metrics: BenchmarkMetrics,
    attributes: Vec<KeyValue>,
) -> futures::future::BoxFuture<'static, ()> {
    async move {
        let _permit = permit;
        let start = Instant::now();
        let is_read_large = matches!(command, Commands::ReadLargeResultSet { .. });
        let res = match command {
            Commands::PointSelect { num_rows, .. } => {
                point_select::execute_point_select(db_client, table, 1, num_rows).await
            }
            Commands::SelectUpdate { num_rows, .. } => {
                select_update::execute_select_update(db_client, table, 1, num_rows).await
            }
            Commands::ReadLargeResultSet { num_rows, .. } => {
                read_large_result_set::execute_read_large_result_set(
                    db_client,
                    num_rows,
                    metrics.read_latency.clone(),
                    attributes.clone(),
                )
                .await
            }
            Commands::Tpcc { .. } => unreachable!(),
        };
        let duration_us = start.elapsed().as_micros() as f64;

        metrics.operation_count.add(1, &attributes);

        if let Err(e) = &res {
            metrics.error_count.add(1, &attributes);
            eprintln!("Operation failed: {:?}", e);
        }

        if !is_read_large {
            metrics.latency.record(duration_us, &attributes);
        }
    }
    .boxed()
}

pub fn run_task_closed_loop(
    db_client: google_cloud_spanner::client::DatabaseClient,
    table: String,
    command: Commands,
    metrics: BenchmarkMetrics,
    attributes: Vec<KeyValue>,
) -> futures::future::BoxFuture<'static, ()> {
    async move {
        let start = Instant::now();
        let is_read_large = matches!(command, Commands::ReadLargeResultSet { .. });
        let res = match command {
            Commands::PointSelect { num_rows, .. } => {
                point_select::execute_point_select(db_client, table, 1, num_rows).await
            }
            Commands::SelectUpdate { num_rows, .. } => {
                select_update::execute_select_update(db_client, table, 1, num_rows).await
            }
            Commands::ReadLargeResultSet { num_rows, .. } => {
                read_large_result_set::execute_read_large_result_set(
                    db_client,
                    num_rows,
                    metrics.read_latency.clone(),
                    attributes.clone(),
                )
                .await
            }
            Commands::Tpcc { .. } => unreachable!(),
        };
        let duration_us = start.elapsed().as_micros() as f64;

        metrics.operation_count.add(1, &attributes);

        if let Err(e) = &res {
            metrics.error_count.add(1, &attributes);
            eprintln!("Operation failed: {:?}", e);
        }

        if !is_read_large {
            metrics.latency.record(duration_us, &attributes);
        }
    }
    .boxed()
}

pub async fn run_benchmark(args: Args) -> anyhow::Result<()> {
    // Validation

    match args.load_type {
        LoadType::Steady => {
            if args.cycle_duration.is_some()
                || args.peak_factor.is_some()
                || args.burst_factor.is_some()
                || args.burst_duration.is_some()
                || args.burst_fraction.is_some()
            {
                anyhow::bail!(
                    "Cannot specify burst or gradual load options when load-type is steady"
                );
            }
        }
        LoadType::Spiky => {
            if args.cycle_duration.is_some() || args.peak_factor.is_some() {
                anyhow::bail!("Cannot specify gradual load options when load-type is spiky");
            }
        }
        LoadType::Gradual => {
            if args.burst_factor.is_some()
                || args.burst_duration.is_some()
                || args.burst_fraction.is_some()
            {
                anyhow::bail!("Cannot specify burst load options when load-type is gradual");
            }
        }
        LoadType::ClosedLoop => {
            if args.cycle_duration.is_some()
                || args.peak_factor.is_some()
                || args.burst_factor.is_some()
                || args.burst_duration.is_some()
                || args.burst_fraction.is_some()
            {
                anyhow::bail!(
                    "Cannot specify burst or gradual load options when load-type is closed-loop"
                );
            }
        }
    }

    // Set defaults for anything still None to avoid using optional values directly
    let burst_factor = args.burst_factor.unwrap_or(1.0);
    let burst_duration = args.burst_duration.unwrap_or(1.0);
    let burst_fraction = args.burst_fraction.unwrap_or(0.1);
    let cycle_duration_str = args
        .cycle_duration
        .clone()
        .unwrap_or_else(|| "1h".to_string());
    let peak_factor = args.peak_factor.unwrap_or(2.0);
    let cycle_duration = load_type::parse_duration(&cycle_duration_str)
        .ok_or_else(|| anyhow::anyhow!("Failed to parse cycle duration: {}", cycle_duration_str))?;

    let table_name = args.table.clone().unwrap_or_else(|| "test".to_string());
    let mut _mock_server = None;
    let mut mock_address = None;
    if args.mock {
        let (addr, srv) = start_mock_spanner_server(&table_name).await?;
        mock_address = Some(addr);
        _mock_server = Some(srv);
    }

    // Build Spanner client
    let mut builder = Spanner::builder();
    if args.mock {
        let addr = mock_address.as_ref().unwrap();
        let endpoint = if addr.starts_with("http://") {
            addr.clone()
        } else {
            format!("http://{}", addr)
        };
        builder = builder
            .with_endpoint(endpoint)
            .with_credentials(anonymous::Builder::new().build());
    } else if let Some(ref host) = args.host {
        builder = builder.with_endpoint(host);
    }
    let spanner = builder.build().await?;
    let db_client = spanner
        .database_client(format!(
            "projects/{}/instances/{}/databases/{}",
            args.project, args.instance, args.database
        ))
        .build()
        .await?;

    let duration = load_type::parse_duration(&args.duration);
    let (metrics, _meter_provider, is_owned_provider) =
        setup_metrics(&args.project, args.benchmark_name.clone(), args.no_metrics).await?;

    if let Commands::Tpcc {
        warehouses,
        clients,
        items,
        extended,
    } = args.command
    {
        let mut base_attributes = vec![
            KeyValue::new("benchmark_type", "tpcc"),
            KeyValue::new("for_alerting", args.for_alerting),
            KeyValue::new(
                "benchmark_name",
                args.benchmark_name.unwrap_or_else(|| "".to_string()),
            ),
            KeyValue::new("client", "rust-client"),
            KeyValue::new("concurrent_clients", clients as i64),
        ];
        if extended {
            base_attributes.push(KeyValue::new("extended", true));
        }
        let _monitor = resource_monitor::ResourceMonitor::start(
            &args.resource_probe_interval,
            metrics.clone(),
            base_attributes.clone(),
        );
        let res = tpcc::run_tpcc_benchmark(
            db_client,
            warehouses,
            clients,
            items,
            duration,
            metrics,
            base_attributes,
            extended,
        )
        .await;
        if is_owned_provider {
            let _ = _meter_provider.shutdown();
        }
        return res;
    }

    // Extract subcommand parameters for standard benchmarks
    let (tps, _num_rows) = match args.command {
        Commands::PointSelect { tps, num_rows } => (tps, num_rows),
        Commands::SelectUpdate { tps, num_rows } => (tps, num_rows),
        Commands::ReadLargeResultSet { tps, num_rows } => (tps, num_rows),
        Commands::Tpcc { .. } => unreachable!(),
    };

    let benchmark_type_str = match &args.command {
        Commands::PointSelect { .. } => {
            if args.mock {
                "point-select-mock"
            } else {
                "point-select"
            }
        }
        Commands::SelectUpdate { .. } => "select-update",
        Commands::ReadLargeResultSet { .. } => "read-large-result-set",
        Commands::Tpcc { .. } => unreachable!(),
    };

    let attributes = vec![
        KeyValue::new("benchmark_type", benchmark_type_str),
        KeyValue::new("tps", format!("{:.1}", tps)),
        KeyValue::new("for_alerting", args.for_alerting),
        KeyValue::new(
            "benchmark_name",
            args.benchmark_name.unwrap_or_else(|| "".to_string()),
        ),
        KeyValue::new("client", "rust-client"),
        KeyValue::new("load_type", format!("{:?}", args.load_type).to_lowercase()),
        KeyValue::new("burst_factor", burst_factor),
        KeyValue::new("burst_duration", burst_duration),
        KeyValue::new("burst_fraction", burst_fraction),
        KeyValue::new("cycle_duration_ms", cycle_duration.as_millis() as i64),
        KeyValue::new("peak_factor", peak_factor),
        KeyValue::new("transaction_type", "none"),
    ];

    println!(
        "Starting Spanner Rust Benchmark preset: {:?} for duration: {}, target TPS: {}, threads: {}",
        args.command, args.duration, tps, args.threads
    );

    let _monitor = resource_monitor::ResourceMonitor::start(
        &args.resource_probe_interval,
        metrics.clone(),
        attributes.clone(),
    );

    let semaphore = Arc::new(Semaphore::new(args.threads));
    let table = args.table.clone().expect("--table is required");
    let command = args.command.clone();

    // Loop to generate tasks with Poisson delays
    let start_time = Instant::now();

    let config = RunConfig {
        db_client,
        table,
        command,
        semaphore: semaphore.clone(),
        threads: args.threads,
        metrics,
        attributes,
        tps,
        duration,
        start_time,
        burst_factor,
        burst_duration,
        burst_fraction,
        cycle_duration,
        peak_factor,
    };
    args.load_type.run(config).await;

    // Wait for all active worker tasks to complete
    let _ = semaphore.acquire_many(args.threads as u32).await;

    println!("Benchmark completed successfully.");
    if is_owned_provider {
        let _ = _meter_provider.shutdown();
    }
    Ok(())
}

fn mock_field(name: &str, code: TypeCode) -> Field {
    Field {
        name: name.to_string(),
        r#type: Some(Type {
            code: code as i32,
            array_element_type: None,
            struct_type: None,
            type_annotation: 0,
            proto_type_fqn: "".to_string(),
        }),
    }
}

fn string_value(val: &str) -> Value {
    Value {
        kind: Some(Kind::StringValue(val.to_string())),
    }
}

fn bool_value(val: bool) -> Value {
    Value {
        kind: Some(Kind::BoolValue(val)),
    }
}

fn number_value(val: f64) -> Value {
    Value {
        kind: Some(Kind::NumberValue(val)),
    }
}

pub fn register_all_mock_results(mock: &mut MockSpanner, table_name: &str) {
    mock.expect_create_session().returning(|_| {
        Ok(tonic::Response::new(Session {
            name: "projects/p/instances/i/databases/d/sessions/123".to_string(),
            ..Default::default()
        }))
    });

    mock.expect_begin_transaction().returning(|_| {
        Ok(tonic::Response::new(Transaction {
            id: vec![1, 2, 3],
            ..Default::default()
        }))
    });

    mock.expect_commit().returning(|_| {
        Ok(tonic::Response::new(CommitResponse {
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
                Selector::Begin(_) | Selector::Id(_) => Some(Transaction {
                    id: vec![1, 2, 3],
                    ..Default::default()
                }),
                _ => None,
            });
        let result_sets = (0..count)
            .map(|_| ResultSet {
                stats: Some(ResultSetStats {
                    row_count: Some(RowCount::RowCountExact(1)),
                    ..Default::default()
                }),
                metadata: Some(ResultSetMetadata {
                    transaction: transaction.clone(),
                    ..Default::default()
                }),
                ..Default::default()
            })
            .collect();
        Ok(tonic::Response::new(ExecuteBatchDmlResponse {
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
                Selector::Begin(_) | Selector::Id(_) => Some(Transaction {
                    id: vec![1, 2, 3],
                    ..Default::default()
                }),
                _ => None,
            });
        Ok(tonic::Response::new(ResultSet {
            stats: Some(ResultSetStats {
                row_count: Some(RowCount::RowCountExact(1)),
                ..Default::default()
            }),
            metadata: Some(ResultSetMetadata {
                transaction,
                ..Default::default()
            }),
            ..Default::default()
        }))
    });

    let target_table = table_name.to_string();
    mock.expect_execute_streaming_sql().returning(move |req| {
        let req = req.into_inner();
        let sql = req.sql;
        let transaction = req
            .transaction
            .and_then(|t| t.selector)
            .and_then(|s| match s {
                Selector::Begin(_) | Selector::Id(_) => Some(Transaction {
                    id: vec![1, 2, 3],
                    ..Default::default()
                }),
                _ => None,
            });
        let (tx, rx) = tokio::sync::mpsc::channel(1);

        let result_set = if sql.contains("SELECT id, value FROM")
            || sql.contains(&format!("FROM {}", target_table))
        {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![
                            mock_field("id", TypeCode::Int64),
                            mock_field("value", TypeCode::String),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1"), string_value("test-value")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("SELECT id FROM") {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![mock_field("id", TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("random_bool") {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![
                            mock_field("random_bool", TypeCode::Bool),
                            mock_field("random_bytes", TypeCode::Bytes),
                            mock_field("random_date", TypeCode::Date),
                            mock_field("random_float32", TypeCode::Float32),
                            mock_field("random_float64", TypeCode::Float64),
                            mock_field("random_json", TypeCode::Json),
                            mock_field("random_int64", TypeCode::Int64),
                            mock_field("random_string", TypeCode::String),
                            mock_field("random_timestamp", TypeCode::Timestamp),
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
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![mock_field("count", TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("SELECT next_order_id") {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![mock_field("next_order_id", TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1000")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("discount, last_name FROM customer") {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![
                            mock_field("discount", TypeCode::Float64),
                            mock_field("last_name", TypeCode::String),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![number_value(0.10), string_value("last_name")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("balance, first_name, last_name FROM customer") {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![
                            mock_field("balance", TypeCode::Float64),
                            mock_field("first_name", TypeCode::String),
                            mock_field("last_name", TypeCode::String),
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
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![mock_field("order_id", TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("5")],
                last: true,
                ..Default::default()
            }
        } else if sql.contains("order_line_id, item_id, quantity, amount") {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![
                            mock_field("order_line_id", TypeCode::Int64),
                            mock_field("item_id", TypeCode::Int64),
                            mock_field("quantity", TypeCode::Int64),
                            mock_field("amount", TypeCode::Float64),
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
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![mock_field("order_id", TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("5")],
                last: true,
                ..Default::default()
            }
        } else {
            PartialResultSet {
                last: true,
                ..Default::default()
            }
        };

        let mut result_set = result_set;
        if let Some(meta) = &mut result_set.metadata {
            meta.transaction = transaction;
        } else {
            result_set.metadata = Some(ResultSetMetadata {
                transaction,
                ..Default::default()
            });
        }
        tx.try_send(Ok(result_set))
            .expect("Failed to send mock result_set");

        Ok(tonic::Response::from(rx))
    });

    mock.expect_streaming_read().returning(move |req| {
        let req = req.into_inner();
        let table = req.table;
        let transaction = req
            .transaction
            .and_then(|t| t.selector)
            .and_then(|s| match s {
                Selector::Begin(_) | Selector::Id(_) => Some(Transaction {
                    id: vec![1, 2, 3],
                    ..Default::default()
                }),
                _ => None,
            });
        let (tx, rx) = tokio::sync::mpsc::channel(1);
        let mut result_set = if table == "district" {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![mock_field("next_order_id", TypeCode::Int64)],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("1000")],
                last: true,
                ..Default::default()
            }
        } else if table == "customer" {
            if req.columns.len() == 2 {
                PartialResultSet {
                    metadata: Some(ResultSetMetadata {
                        row_type: Some(StructType {
                            fields: vec![
                                mock_field("discount", TypeCode::Float64),
                                mock_field("last_name", TypeCode::String),
                            ],
                        }),
                        ..Default::default()
                    }),
                    values: vec![number_value(0.10), string_value("last_name")],
                    last: true,
                    ..Default::default()
                }
            } else {
                PartialResultSet {
                    metadata: Some(ResultSetMetadata {
                        row_type: Some(StructType {
                            fields: vec![
                                mock_field("balance", TypeCode::Float64),
                                mock_field("first_name", TypeCode::String),
                                mock_field("last_name", TypeCode::String),
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
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![
                            mock_field("item_id", TypeCode::Int64),
                            mock_field("quantity", TypeCode::Int64),
                        ],
                    }),
                    ..Default::default()
                }),
                values: vec![string_value("123"), string_value("50")],
                last: true,
                ..Default::default()
            }
        } else if table == "order_line" {
            PartialResultSet {
                metadata: Some(ResultSetMetadata {
                    row_type: Some(StructType {
                        fields: vec![
                            mock_field("order_line_id", TypeCode::Int64),
                            mock_field("item_id", TypeCode::Int64),
                            mock_field("quantity", TypeCode::Int64),
                            mock_field("amount", TypeCode::Float64),
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
            PartialResultSet {
                last: true,
                ..Default::default()
            }
        };

        if let Some(meta) = &mut result_set.metadata {
            meta.transaction = transaction;
        } else {
            result_set.metadata = Some(ResultSetMetadata {
                transaction,
                ..Default::default()
            });
        }
        tx.try_send(Ok(result_set))
            .expect("Failed to send mock result_set");

        Ok(tonic::Response::from(rx))
    });
}

async fn start_mock_spanner_server(
    table_name: &str,
) -> anyhow::Result<(String, tokio::task::JoinHandle<()>)> {
    let mut mock = MockSpanner::new();
    register_all_mock_results(&mut mock, table_name);

    let (address, server) = spanner_grpc_mock::start("127.0.0.1:0", mock)
        .await
        .map_err(|e| anyhow::anyhow!("Failed to start mock server: {:?}", e))?;

    Ok((address, server))
}
