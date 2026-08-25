use clap::Parser;
use opentelemetry_sdk::metrics::{InMemoryMetricExporter, SdkMeterProvider};
use spanner_grpc_mock::{MockSpanner, start};
use spanner_rust_benchmark::{Args, TEST_METER_PROVIDER, run_benchmark};

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn benchmark_workloads() -> anyhow::Result<()> {
    // Setup Mock Spanner Server
    let mut mock = MockSpanner::new();

    spanner_rust_benchmark::register_all_mock_results(&mut mock, "test");

    let (address, _server) = start("127.0.0.1:0", mock)
        .await
        .expect("Failed to start mock server");
    unsafe {
        std::env::set_var("SPANNER_EMULATOR_HOST", &address);
    }

    // Initialize InMemory Metric Exporter & SdkMeterProvider
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

    // Test PointSelect Workload
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

    // Test SelectUpdate Workload
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

    // Test ReadLargeResultSet Workload
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

    // Test ReadNarrowResultSet Workload
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
            "read-narrow-result-set",
            "--tps",
            "10",
        ])?;

        run_benchmark(args).await?;
    }

    // Test Tpcc Workload
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

    // Test Tpcc Extended Workload
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

    // Test YCSB Init
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
            "usertable",
            "--host",
            &address,
            "--mock",
            "ycsb-init",
            "--record-count",
            "100",
            "--threads",
            "2",
            "--batch-size",
            "10",
        ])?;

        run_benchmark(args).await?;
    }

    // Test YCSB Workloads A through F
    let ycsb_workloads = vec![
        ("a", false),
        ("b", false),
        ("b", true), // Test Workload B with --use-read-row
        ("c", false),
        ("d", false),
        ("e", false),
        ("f", false),
    ];

    for (workload_str, use_read_row) in ycsb_workloads {
        let mut cli_args = vec![
            "benchmark",
            "--project",
            "test-project",
            "--instance",
            "test-instance",
            "--database",
            "test-database",
            "--table",
            "usertable",
            "--duration",
            "1s",
            "--host",
            &address,
            "--threads",
            "2",
            "--resource-probe-interval",
            "10ms",
            "--mock",
            "ycsb",
            "--workload",
            workload_str,
            "--record-count",
            "100",
            "--tps",
            "10",
        ];

        if use_read_row {
            cli_args.push("--use-read-row");
        }

        let args = Args::try_parse_from(cli_args)?;
        run_benchmark(args).await?;
    }

    // Test Spiky Load Type with PointSelect
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
            "--load-type",
            "spiky",
            "--burst-factor",
            "2.0",
            "--burst-duration",
            "0.1",
            "--burst-fraction",
            "0.5",
            "point-select",
            "--tps",
            "10",
        ])?;

        run_benchmark(args).await?;
    }

    // Test Gradual Load Type with SelectUpdate
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
            "--load-type",
            "gradual",
            "--cycle-duration",
            "500ms",
            "--peak-factor",
            "1.5",
            "select-update",
            "--tps",
            "10",
        ])?;

        run_benchmark(args).await?;
    }

    // Test ClosedLoop Load Type with YCSB Workload A
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
            "usertable",
            "--duration",
            "1s",
            "--host",
            &address,
            "--threads",
            "2",
            "--resource-probe-interval",
            "10ms",
            "--load-type",
            "closed-loop",
            "--mock",
            "ycsb",
            "--workload",
            "a",
            "--record-count",
            "100",
        ])?;

        run_benchmark(args).await?;
    }

    // Assert metrics have been emitted correctly
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
                if m.name() == "spanner_client_benchmarks/error_count" {
                    assert!(
                        !debug_str.contains("value: Sum")
                            || debug_str.contains("value: Sum(0)")
                            || debug_str.contains("value: 0"),
                        "Expected 0 error count, but found: {}",
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
