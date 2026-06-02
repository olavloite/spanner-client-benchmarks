mod load_type;
mod point_select;
mod read_large_result_set;
mod select_update;
mod tpcc;

use clap::{Parser, Subcommand, ArgAction};
use futures::FutureExt;
use google_cloud_spanner::client::Spanner;
use load_type::{LoadType, RunConfig};
use opentelemetry::KeyValue;
use opentelemetry::metrics::{Counter, Histogram, MeterProvider};
use opentelemetry_gcloud_monitoring_exporter::{GCPMetricsExporter, GCPMetricsExporterConfig};
use opentelemetry_sdk::metrics::{SdkMeterProvider, Aggregation, Stream, Instrument};
use std::sync::Arc;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio::time::Instant;

#[derive(Parser, Debug)]
#[command(name = "BenchmarkApp", about = "Spanner client library benchmark tool for Rust.")]
struct Args {
    #[arg(long)]
    project: String,
    #[arg(long)]
    instance: String,
    #[arg(long)]
    database: String,
    #[arg(long, global = true)]
    table: Option<String>,
    #[arg(long, default_value = "inf")]
    duration: String,
    #[arg(long, default_value_t = false, action = ArgAction::Set)]
    for_alerting: bool,
    #[arg(long)]
    benchmark_name: Option<String>,
    #[arg(long)]
    host: Option<String>,
    #[arg(long, global = true, default_value_t = 100)]
    threads: usize,
    #[arg(long, global = true, value_enum, default_value_t = LoadType::Steady)]
    load_type: LoadType,
    #[arg(long, global = true)]
    cycle_duration: Option<String>,
    #[arg(long, global = true)]
    peak_factor: Option<f64>,
    #[arg(long, global = true)]
    burst_factor: Option<f64>,
    #[arg(long, global = true)]
    burst_duration: Option<f64>,
    #[arg(long, global = true)]
    burst_fraction: Option<f64>,
    #[arg(long, global = true, default_value = "10s")]
    resource_probe_interval: String,
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand, Debug, Clone)]
pub(crate) enum Commands {
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
    },
}


#[derive(Clone)]
pub(crate) struct BenchmarkMetrics {
    latency: Histogram<f64>,
    read_latency: Histogram<f64>,
    operation_count: Counter<u64>,
    error_count: Counter<u64>,
    memory_usage: Histogram<f64>,
    cpu_utilization: Histogram<f64>,
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

async fn setup_metrics(
    project_id: &str,
    benchmark_name: Option<String>,
) -> anyhow::Result<(BenchmarkMetrics, SdkMeterProvider)> {
    let config = GCPMetricsExporterConfig {
        project_id: Some(project_id.to_string()),
        ..Default::default()
    };
    let exporter = GCPMetricsExporter::init(config).await.map_err(|e| anyhow::anyhow!("{:?}", e))?;

    let service_name = benchmark_name.unwrap_or_else(|| "spanner-benchmark".to_string());
    let instance_id = uuid::Uuid::new_v4().to_string();
    let resource = opentelemetry_sdk::Resource::builder_empty()
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

    let latency = meter.f64_histogram("spanner_client_benchmarks/latency")
        .with_description("Query latency in microseconds")
        .with_unit("us")
        .build();

    let read_latency = meter.f64_histogram("spanner_client_benchmarks/read_latency")
        .with_description("Query latency in microseconds")
        .with_unit("us")
        .build();

    let operation_count = meter.u64_counter("spanner_client_benchmarks/operation_count")
        .with_description("Total number of benchmark operations executed")
        .with_unit("1")
        .build();

    let error_count = meter.u64_counter("spanner_client_benchmarks/error_count")
        .with_description("Total number of benchmark operations that failed with an error")
        .with_unit("1")
        .build();

    let memory_usage = meter.f64_histogram("spanner_client_benchmarks/memory_usage")
        .with_description("Active memory usage in bytes")
        .with_unit("By")
        .build();

    let cpu_utilization = meter.f64_histogram("spanner_client_benchmarks/cpu_utilization")
        .with_description("Process CPU utilization")
        .with_unit("1")
        .build();

    Ok((BenchmarkMetrics { latency, read_latency, operation_count, error_count, memory_usage, cpu_utilization }, provider))
}

pub(crate) fn run_task(
    db_client: google_cloud_spanner::client::DatabaseClient,
    table: String,
    command: Commands,
    _permit: OwnedSemaphorePermit,
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
                read_large_result_set::execute_read_large_result_set(db_client, num_rows, metrics.read_latency.clone(), attributes.clone()).await
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

fn start_resource_monitoring(
    probe_interval_str: &str,
    metrics: BenchmarkMetrics,
    attributes: Vec<KeyValue>,
) {
    if probe_interval_str != "0" && probe_interval_str != "0s" && !probe_interval_str.is_empty() {
        if let Some(probe_duration) = load_type::parse_duration(probe_interval_str) {
            if probe_duration.as_millis() > 0 {
                tokio::spawn(async move {
                    run_resource_monitor_loop(probe_duration, metrics, attributes).await;
                });
            }
        }
    }
}

async fn run_resource_monitor_loop(
    probe_duration: std::time::Duration,
    metrics: BenchmarkMetrics,
    attributes: Vec<KeyValue>,
) {
    let mut sys = sysinfo::System::new();
    if let Ok(pid) = sysinfo::get_current_pid() {
        let mut interval = tokio::time::interval(probe_duration);
        loop {
            interval.tick().await;
            sys.refresh_all();
            if let Some(process) = sys.process(pid) {
                let memory = process.memory() as f64;
                let cpu = (process.cpu_usage() / 100.0) as f64;
                metrics.memory_usage.record(memory, &attributes);
                metrics.cpu_utilization.record(cpu, &attributes);
            }
        }
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let args = Args::parse();

    // Validation
    match args.load_type {
        LoadType::Steady => {
            if args.cycle_duration.is_some() || args.peak_factor.is_some() || args.burst_factor.is_some() || args.burst_duration.is_some() || args.burst_fraction.is_some() {
                anyhow::bail!("Cannot specify burst or gradual load options when load-type is steady");
            }
        }
        LoadType::Spiky => {
            if args.cycle_duration.is_some() || args.peak_factor.is_some() {
                anyhow::bail!("Cannot specify gradual load options when load-type is spiky");
            }
        }
        LoadType::Gradual => {
            if args.burst_factor.is_some() || args.burst_duration.is_some() || args.burst_fraction.is_some() {
                anyhow::bail!("Cannot specify burst load options when load-type is gradual");
            }
        }
    }

    // Set defaults for anything still None to avoid using optional values directly
    let burst_factor = args.burst_factor.unwrap_or(1.0);
    let burst_duration = args.burst_duration.unwrap_or(1.0);
    let burst_fraction = args.burst_fraction.unwrap_or(0.1);
    let cycle_duration_str = args.cycle_duration.clone().unwrap_or_else(|| "1h".to_string());
    let peak_factor = args.peak_factor.unwrap_or(2.0);
    let cycle_duration = load_type::parse_duration(&cycle_duration_str).ok_or_else(|| anyhow::anyhow!("Failed to parse cycle duration: {}", cycle_duration_str))?;

    // Build Spanner client
    let mut builder = Spanner::builder();
    if let Some(ref host) = args.host {
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
    let (metrics, _meter_provider) = setup_metrics(&args.project, args.benchmark_name.clone()).await?;

    if let Commands::Tpcc { warehouses, clients, items } = args.command {
        let base_attributes = vec![
            KeyValue::new("benchmark_type", "tpcc"),
            KeyValue::new("for_alerting", args.for_alerting),
            KeyValue::new("benchmark_name", args.benchmark_name.unwrap_or_else(|| "".to_string())),
            KeyValue::new("client", "rust-client"),
            KeyValue::new("concurrent_clients", clients as i64),
        ];
        start_resource_monitoring(&args.resource_probe_interval, metrics.clone(), base_attributes.clone());
        tpcc::run_tpcc_benchmark(db_client, warehouses, clients, items, duration, metrics, base_attributes).await?;
        return Ok(());
    }

    // Extract subcommand parameters for standard benchmarks
    let (tps, _num_rows) = match args.command {
        Commands::PointSelect { tps, num_rows } => (tps, num_rows),
        Commands::SelectUpdate { tps, num_rows } => (tps, num_rows),
        Commands::ReadLargeResultSet { tps, num_rows } => (tps, num_rows),
        Commands::Tpcc { .. } => unreachable!(),
    };

    let benchmark_type_str = match &args.command {
        Commands::PointSelect { .. } => "point-select",
        Commands::SelectUpdate { .. } => "select-update",
        Commands::ReadLargeResultSet { .. } => "read-large-result-set",
        Commands::Tpcc { .. } => unreachable!(),
    };

    let attributes = vec![
        KeyValue::new("benchmark_type", benchmark_type_str),
        KeyValue::new("tps", tps),
        KeyValue::new("for_alerting", args.for_alerting),
        KeyValue::new("benchmark_name", args.benchmark_name.unwrap_or_else(|| "".to_string())),
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

    start_resource_monitoring(&args.resource_probe_interval, metrics.clone(), attributes.clone());

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
    Ok(())
}
