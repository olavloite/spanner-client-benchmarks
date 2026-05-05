import argparse
import os
import signal
import sys
from src.config.duration import parse_duration
from src.metrics.otel import setup_metrics, LATENCY_NAME
def main():
    """
    Main command line entry point. Parses options, setups client services,
    and handles graceful lifecycle shutdown sig-traps.
    """
    parser = argparse.ArgumentParser(
        description="High-performance Cloud Spanner client library benchmark tool for Python."
    )

    # Global flags matching Java, Go, and Node setups
    parser.add_argument("-p", "--project", required=True, help="Google Cloud Project ID")
    parser.add_argument("-i", "--instance", required=True, help="Spanner Instance ID")
    parser.add_argument("-d", "--database", required=True, help="Spanner Database ID")
    parser.add_argument("--host", help="Custom Spanner host endpoint override (e.g. for emulators)")
    parser.add_argument(
        "--duration",
        default="inf",
        help="Duration of the benchmark (e.g. '60s', '5m', 'inf'). Defaults to inf (infinite).",
    )
    parser.add_argument(
        "--for-alerting",
        action="store_true",
        default=False,
        help="Marks the metrics emitted for regression/alerting pipelines.",
    )

    # Workload Scenario Subcommands routing
    subparsers = parser.add_subparsers(dest="command", required=True, help="Workload scenario subcommands")

    # Point-Select subparser
    ps_parser = subparsers.add_parser(
        "point-select", help="Execute single point select statement workload scenario"
    )
    ps_parser.add_argument("-t", "--table", required=True, help="Target database table name")
    ps_parser.add_argument("--min-id", type=int, default=1, help="Minimum primary key row identifier")
    ps_parser.add_argument("--max-id", type=int, default=1000000, help="Maximum primary key row identifier")
    ps_parser.add_argument("--tps", type=float, default=1.0, help="Target Transactions Per Second rate limit")
    ps_parser.add_argument(
        "--threads", type=int, default=100, help="ThreadPoolExecutor worker thread concurrency cap"
    )

    # Select-Update subparser
    su_parser = subparsers.add_parser(
        "select-update", help="Execute read-modify-write transaction statement workload scenario"
    )
    su_parser.add_argument("-t", "--table", required=True, help="Target database table name")
    su_parser.add_argument("--min-id", type=int, default=1, help="Minimum primary key row identifier")
    su_parser.add_argument("--max-id", type=int, default=1000000, help="Maximum primary key row identifier")
    su_parser.add_argument("--tps", type=float, default=1.0, help="Target Transactions Per Second rate limit")
    su_parser.add_argument(
        "--threads", type=int, default=100, help="ThreadPoolExecutor worker thread concurrency cap"
    )

    args = parser.parse_args()

    # Convert human-readable duration into float seconds
    duration_sec = parse_duration(args.duration)

    # Detect if local emulator is specified via host or environment variables
    host = args.host
    is_emulator = bool(os.environ.get("SPANNER_EMULATOR_HOST")) or (
        bool(host) and ("localhost:" in host or "127.0.0.1:" in host)
    )

    # 1. Setup OpenTelemetry metrics provider and instruments
    meter, shutdown_metrics = setup_metrics(args.project, is_emulator)

    # Create shared latency histogram instrument (us unit matching standard spec)
    latency_histogram = meter.create_histogram(
        name=LATENCY_NAME,
        description="Query latency measured in microseconds",
        unit="us",
    )

    # 2. Initialize the Google Cloud Spanner Client driver
    from src.spanner.client import create_spanner_client
    from src.benchmarks.point_select import PointSelectBenchmark
    from src.benchmarks.select_update import SelectAndUpdateBenchmark

    spanner_client = create_spanner_client(args.project, host)
    instance = spanner_client.instance(args.instance)
    database = instance.database(args.database)

    # 3. Instantiate concrete designation workload task benchmark
    if args.command == "point-select":
        benchmark = PointSelectBenchmark(
            database=database,
            latency_histogram=latency_histogram,
            table_name=args.table,
            min_id=args.min_id,
            max_id=args.max_id,
            tps=args.tps,
            threads=args.threads,
            duration_sec=duration_sec,
            for_alerting=args.for_alerting,
        )
    else:
        benchmark = SelectAndUpdateBenchmark(
            database=database,
            latency_histogram=latency_histogram,
            table_name=args.table,
            min_id=args.min_id,
            max_id=args.max_id,
            tps=args.tps,
            threads=args.threads,
            duration_sec=duration_sec,
            for_alerting=args.for_alerting,
        )

    # 4. Register process lifecycle termination traps (SIGINT, SIGTERM)
    is_terminating = False

    def graceful_termination_handler(sig, frame):
        nonlocal is_terminating
        if is_terminating:
            return
        is_terminating = True
        print(f"\n[Lifecycle] Received signal {sig}. Initiating graceful termination...")
        
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
        print(f"Fatal exception encountered during benchmark execution: {err}", file=sys.stderr)
    finally:
        # Normal duration finish cleanup
        if not is_terminating:
            is_terminating = True
            shutdown_metrics()

if __name__ == "__main__":
    main()
