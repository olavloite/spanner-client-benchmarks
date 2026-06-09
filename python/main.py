import argparse
import os
import signal
import sys

from src.metrics.otel import (
    CPU_UTILIZATION_NAME,
    ERROR_COUNT_NAME,
    LATENCY_NAME,
    MEMORY_USAGE_NAME,
    OPERATION_COUNT_NAME,
    READ_LATENCY_NAME,
    setup_metrics,
)
from src.utils.duration import parse_duration


def _safe_call(action):
    try:
        action()
    except Exception:
        pass


def validate_and_fill_load_params(args):
    if args.load_type in ("steady", "closed-loop"):
        if (
            args.cycle_duration is not None
            or args.peak_factor is not None
            or args.burst_factor is not None
            or args.burst_duration is not None
            or args.burst_fraction is not None
        ):
            print(
                f"Error: Cannot specify burst or gradual load options when load-type is {args.load_type}",
                file=sys.stderr,
            )
            sys.exit(1)
    elif args.load_type == "spiky":
        if args.cycle_duration is not None or args.peak_factor is not None:
            print(
                "Error: Cannot specify gradual load options when load-type is spiky",
                file=sys.stderr,
            )
            sys.exit(1)
    elif args.load_type == "gradual":
        if (
            args.burst_factor is not None
            or args.burst_duration is not None
            or args.burst_fraction is not None
        ):
            print(
                "Error: Cannot specify burst load options when load-type is gradual",
                file=sys.stderr,
            )
            sys.exit(1)

    burst_factor = args.burst_factor if args.burst_factor is not None else 1.0
    burst_duration = args.burst_duration if args.burst_duration is not None else 1.0
    burst_fraction = args.burst_fraction if args.burst_fraction is not None else 0.1
    cycle_duration_str = (
        args.cycle_duration if args.cycle_duration is not None else "1h"
    )
    peak_factor = args.peak_factor if args.peak_factor is not None else 2.0

    return burst_factor, burst_duration, burst_fraction, cycle_duration_str, peak_factor


def main():
    """
    Main command line entry point. Parses options, setups client services,
    and handles graceful lifecycle shutdown sig-traps.
    """
    parser = argparse.ArgumentParser(
        description="High-performance Cloud Spanner client library benchmark tool for Python."
    )

    def str2bool(v):
        if isinstance(v, bool):
            return v
        if v.lower() in ("yes", "true", "t", "y", "1"):
            return True
        elif v.lower() in ("no", "false", "f", "n", "0"):
            return False
        else:
            raise argparse.ArgumentTypeError("Boolean value expected.")

    # Global flags matching Java, Go, and Node setups
    parser.add_argument(
        "-p", "--project", required=True, help="Google Cloud Project ID"
    )
    parser.add_argument("-i", "--instance", required=True, help="Spanner Instance ID")
    parser.add_argument("-d", "--database", required=True, help="Spanner Database ID")
    parser.add_argument(
        "--host", help="Custom Spanner host endpoint override (e.g. for emulators)"
    )
    parser.add_argument(
        "--duration",
        default="inf",
        help="Duration of the benchmark (e.g. '60s', '5m', 'inf'). Defaults to inf (infinite).",
    )
    parser.add_argument(
        "--for-alerting",
        type=str2bool,
        nargs="?",
        const=True,
        default=False,
        help="Marks the metrics emitted for regression/alerting pipelines.",
    )
    parser.add_argument(
        "--benchmark-name",
        default="",
        help="Optional name to identify this benchmark run in metrics",
    )
    parser.add_argument(
        "--resource-probe-interval",
        default="10s",
        help="Interval for probing resource usage (e.g. 10s, 1m). Set to 0 to disable.",
    )
    parser.add_argument(
        "--load-type",
        default="steady",
        choices=["steady", "spiky", "gradual", "closed-loop"],
        help="Load type",
    )
    parser.add_argument(
        "--cycle-duration", help="Duration of a full cycle for gradual load"
    )
    parser.add_argument(
        "--peak-factor",
        type=float,
        help="Ratio of peak rate to average rate for gradual load",
    )
    parser.add_argument(
        "--burst-factor", type=float, help="Ratio of burst rate to average rate"
    )
    parser.add_argument(
        "--burst-duration", type=float, help="Average duration of a burst in seconds"
    )
    parser.add_argument(
        "--burst-fraction",
        type=float,
        help="Fraction of total time spent in the burst state",
    )
    parser.add_argument(
        "--mock",
        action="store_true",
        default=False,
        help="Connect to a local in-memory mock Spanner server (only supported with point-select workload).",
    )
    parser.add_argument(
        "--use-uds",
        action="store_true",
        default=False,
        help="Use a Unix Domain Socket instead of TCP loopback for the mock server connection.",
    )

    # Common workload flags for all subparsers
    workload_parser = argparse.ArgumentParser(add_help=False)
    workload_parser.add_argument(
        "-t", "--table", required=True, help="Target database table name"
    )
    workload_parser.add_argument(
        "--threads",
        type=int,
        default=100,
        help="ThreadPoolExecutor worker thread concurrency cap",
    )
    workload_parser.add_argument(
        "--load-type",
        default="steady",
        choices=["steady", "spiky", "gradual", "closed-loop"],
        help="Load type",
    )
    workload_parser.add_argument(
        "--cycle-duration", help="Duration of a full cycle for gradual load"
    )
    workload_parser.add_argument(
        "--peak-factor",
        type=float,
        help="Ratio of peak rate to average rate for gradual load",
    )
    workload_parser.add_argument(
        "--burst-factor", type=float, help="Ratio of burst rate to average rate"
    )
    workload_parser.add_argument(
        "--burst-duration", type=float, help="Average duration of a burst in seconds"
    )
    workload_parser.add_argument(
        "--burst-fraction",
        type=float,
        help="Fraction of total time spent in the burst state",
    )

    # Workload Scenario Subcommands routing
    subparsers = parser.add_subparsers(
        dest="command", required=True, help="Workload scenario subcommands"
    )

    # Point-Select subparser
    ps_parser = subparsers.add_parser(
        "point-select",
        parents=[workload_parser],
        help="Execute single point select statement workload scenario",
    )
    ps_parser.add_argument(
        "--tps",
        type=float,
        default=10.0,
        help="Target Transactions Per Second rate limit",
    )
    ps_parser.add_argument(
        "--num-rows",
        type=int,
        default=1000000,
        help="Number of rows in target database table",
    )

    # Select-Update subparser
    su_parser = subparsers.add_parser(
        "select-update",
        parents=[workload_parser],
        help="Execute read-modify-write transaction statement workload scenario",
    )
    su_parser.add_argument(
        "--tps",
        type=float,
        default=10.0,
        help="Target Transactions Per Second rate limit",
    )
    su_parser.add_argument(
        "--num-rows",
        type=int,
        default=1000000,
        help="Number of rows in target database table",
    )

    # Read-Large subparser
    rl_parser = subparsers.add_parser(
        "read-large-result-set",
        parents=[workload_parser],
        help="Execute dynamic large result set iteration scenario",
    )
    rl_parser.add_argument(
        "--tps",
        type=float,
        default=0.05,
        help="Target Transactions Per Second rate limit",
    )
    rl_parser.add_argument(
        "--num-rows",
        type=int,
        default=100000,
        help="Number of rows to dynamically generate",
    )

    # TPC-C subparser
    tpcc_parser = subparsers.add_parser(
        "tpcc", help="Execute closed-loop TPC-C benchmark"
    )
    tpcc_parser.add_argument(
        "--warehouses", type=int, default=1, help="Scale factor (number of warehouses)"
    )
    tpcc_parser.add_argument(
        "--clients", type=int, default=10, help="Number of parallel worker clients"
    )
    tpcc_parser.add_argument(
        "--items", type=int, default=100000, help="Number of items in catalog"
    )
    tpcc_parser.add_argument(
        "--extended",
        action="store_true",
        default=False,
        help="Run TPC-C benchmark with extended coverage of client library features",
    )

    args = parser.parse_args()

    # Validation of --mock constraint
    if args.mock:
        if args.command != "point-select":
            print(
                "Error: The --mock option is currently only supported with the 'point-select' workload.",
                file=sys.stderr,
            )
            sys.exit(1)

    # Validation and filling defaults
    burst_factor, burst_duration, burst_fraction, cycle_duration_str, peak_factor = (
        validate_and_fill_load_params(args)
    )

    min_id = 1
    max_id = getattr(args, "num_rows", 1000000)

    # Convert human-readable duration into float seconds
    duration_sec = parse_duration(args.duration)

    # Detect if local emulator is specified via host or environment variables
    host = args.host
    is_emulator = bool(os.environ.get("SPANNER_EMULATOR_HOST")) or (
        bool(host) and ("localhost:" in host or "127.0.0.1:" in host)
    )

    mock_server = None
    mock_executor = None
    if args.mock:
        from src.spanner.mock_server import start_mock_spanner
        table_name = getattr(args, "table", "test")
        print(f"Starting local mock Spanner server for table '{table_name}'...")
        if args.use_uds:
            socket_path = f"/tmp/spanner_mock_{os.getpid()}.sock"
            if os.path.exists(socket_path):
                try:
                    os.remove(socket_path)
                except OSError:
                    pass
            mock_server, mock_executor, _ = start_mock_spanner(table_name, socket_path=socket_path)
            host = f"unix://{socket_path}"
        else:
            mock_server, mock_executor, mock_port = start_mock_spanner(table_name)
            host = f"127.0.0.1:{mock_port}"
        is_emulator = True

    # 1. Setup OpenTelemetry metrics provider and instruments
    meter, shutdown_metrics = setup_metrics(
        args.project, is_emulator and not args.mock, args.benchmark_name
    )

    # Create shared metrics instruments (us unit matching standard spec)
    metric_name = (
        READ_LATENCY_NAME if args.command == "read-large-result-set" else LATENCY_NAME
    )
    latency_histogram = meter.create_histogram(
        name=metric_name,
        description="Query latency measured in microseconds",
        unit="us",
    )

    operation_counter = meter.create_counter(
        name=OPERATION_COUNT_NAME,
        description="Total number of benchmark operations executed",
        unit="1",
    )

    error_counter = meter.create_counter(
        name=ERROR_COUNT_NAME,
        description="Total number of benchmark operations that failed with an error",
        unit="1",
    )

    memory_usage_histogram = meter.create_histogram(
        name=MEMORY_USAGE_NAME,
        description="Active memory usage in bytes",
        unit="By",
    )

    cpu_utilization_histogram = meter.create_histogram(
        name=CPU_UTILIZATION_NAME,
        description="Process CPU utilization",
        unit="1",
    )

    # 2. Initialize the Google Cloud Spanner Client driver
    from src.benchmarks.point_select import PointSelectBenchmark
    from src.benchmarks.read_large_result_set import ReadLargeResultSetBenchmark
    from src.benchmarks.select_update import SelectAndUpdateBenchmark
    from src.benchmarks.tpcc.benchmark import TpccBenchmarkRunner
    from src.spanner.client import create_spanner_client

    spanner_client = create_spanner_client(args.project, host)
    instance = spanner_client.instance(args.instance)
    database = instance.database(args.database)

    # 3. Instantiate concrete designation workload task benchmark
    cycle_duration_sec = parse_duration(cycle_duration_str)
    if args.command == "point-select":
        benchmark = PointSelectBenchmark(
            database=database,
            latency_histogram=latency_histogram,
            operation_counter=operation_counter,
            error_counter=error_counter,
            memory_usage_histogram=memory_usage_histogram,
            cpu_utilization_histogram=cpu_utilization_histogram,
            resource_probe_interval_str=args.resource_probe_interval,
            table_name=args.table,
            min_id=min_id,
            max_id=max_id,
            tps=args.tps,
            threads=args.threads,
            duration_sec=duration_sec,
            for_alerting=args.for_alerting,
            benchmark_name=args.benchmark_name,
            load_type=args.load_type,
            cycle_duration_sec=cycle_duration_sec,
            peak_factor=peak_factor,
            burst_factor=burst_factor,
            burst_duration=burst_duration,
            burst_fraction=burst_fraction,
            is_mock=args.mock,
        )
    elif args.command == "select-update":
        benchmark = SelectAndUpdateBenchmark(
            database=database,
            latency_histogram=latency_histogram,
            operation_counter=operation_counter,
            error_counter=error_counter,
            memory_usage_histogram=memory_usage_histogram,
            cpu_utilization_histogram=cpu_utilization_histogram,
            resource_probe_interval_str=args.resource_probe_interval,
            table_name=args.table,
            min_id=min_id,
            max_id=max_id,
            tps=args.tps,
            threads=args.threads,
            duration_sec=duration_sec,
            for_alerting=args.for_alerting,
            benchmark_name=args.benchmark_name,
            load_type=args.load_type,
            cycle_duration_sec=cycle_duration_sec,
            peak_factor=peak_factor,
            burst_factor=burst_factor,
            burst_duration=burst_duration,
            burst_fraction=burst_fraction,
        )
    elif args.command == "read-large-result-set":
        benchmark = ReadLargeResultSetBenchmark(
            database=database,
            latency_histogram=latency_histogram,
            operation_counter=operation_counter,
            error_counter=error_counter,
            memory_usage_histogram=memory_usage_histogram,
            cpu_utilization_histogram=cpu_utilization_histogram,
            resource_probe_interval_str=args.resource_probe_interval,
            table_name=args.table,
            min_id=min_id,
            max_id=max_id,
            tps=args.tps,
            threads=args.threads,
            duration_sec=duration_sec,
            for_alerting=args.for_alerting,
            benchmark_name=args.benchmark_name,
            num_rows=args.num_rows,
            load_type=args.load_type,
            cycle_duration_sec=cycle_duration_sec,
            peak_factor=peak_factor,
            burst_factor=burst_factor,
            burst_duration=burst_duration,
            burst_fraction=burst_fraction,
        )
    elif args.command == "tpcc":
        benchmark = TpccBenchmarkRunner(
            database=database,
            latency_histogram=latency_histogram,
            operation_counter=operation_counter,
            error_counter=error_counter,
            memory_usage_histogram=memory_usage_histogram,
            cpu_utilization_histogram=cpu_utilization_histogram,
            resource_probe_interval_str=args.resource_probe_interval,
            scale_factor=args.warehouses,
            clients=args.clients,
            items=args.items,
            duration_sec=duration_sec,
            for_alerting=args.for_alerting,
            benchmark_name=args.benchmark_name,
            extended=args.extended,
        )
    else:
        print(
            f"Error: Unsupported benchmark type: '{args.command}'. Valid options are: 'point-select', 'select-update', 'read-large-result-set', 'tpcc'.",
            file=sys.stderr,
        )
        sys.exit(1)

    # 4. Register process lifecycle termination traps (SIGINT, SIGTERM)
    is_terminating = False

    def graceful_termination_handler(sig, frame):
        nonlocal is_terminating
        if is_terminating:
            return
        is_terminating = True
        print(
            f"\n[Lifecycle] Received signal {sig}. Initiating graceful termination..."
        )

        # Tell workload generator to stop spawning new executor tasks
        benchmark.stop()

        # Shutdown metrics PeriodicExportingMetricReader
        shutdown_metrics()
        print("[Lifecycle] Graceful shutdown complete. Exiting.")
        sys.exit(0)

    signal.signal(signal.SIGINT, graceful_termination_handler)
    signal.signal(signal.SIGTERM, graceful_termination_handler)

    # 5. Run workload scheduler loop
    try:
        benchmark.run()
    except Exception as err:
        print(
            f"Fatal exception encountered during benchmark execution: {err}",
            file=sys.stderr,
        )
    finally:
        # Normal duration finish cleanup
        if not is_terminating:
            is_terminating = True
            shutdown_metrics()

        # Close all Spanner client transports and pool cleanly to release threads
        _safe_call(lambda: database.pool.close())
        _safe_call(lambda: database.spanner_api.transport.close())
        _safe_call(lambda: spanner_client.database_admin_api.transport.close())
        _safe_call(lambda: spanner_client.instance_admin_api.transport.close())
        _safe_call(lambda: spanner_client.close())

        if mock_server:
            print("[Lifecycle] Stopping local mock Spanner server...")
            shutdown_event = mock_server.stop(0)
            shutdown_event.wait()
            if mock_executor:
                mock_executor.shutdown(wait=True)
            if args.use_uds:
                socket_path = f"/tmp/spanner_mock_{os.getpid()}.sock"
                if os.path.exists(socket_path):
                    try:
                        os.remove(socket_path)
                    except OSError:
                        pass


if __name__ == "__main__":
    main()
