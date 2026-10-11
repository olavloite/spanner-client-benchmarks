import time
import uuid
from unittest.mock import MagicMock, patch

from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.resources import Resource

from main import main
from src.benchmarks.abstract_benchmark import AbstractBenchmark, LoadType
from src.metrics.otel import (
    CPU_UTILIZATION_NAME,
    ERROR_COUNT_NAME,
    LATENCY_NAME,
    MEMORY_USAGE_NAME,
    OPERATION_COUNT_NAME,
    READ_LATENCY_NAME,
    set_testing_meter_provider,
)
from tests.base_test import BaseBenchmarkTest


class DummyBenchmark(AbstractBenchmark):
    def get_benchmark_name(self):
        return "Dummy"

    def get_benchmark_type(self):
        return "dummy"

    async def execute_operation(self, database, table_name, min_id, max_id):
        pass


class TestBenchmarkWorkloads(BaseBenchmarkTest):
    def setUp(self):
        super().setUp()
        # Configure in-memory metrics reader and provider
        self.resource = Resource.create(
            {
                "service.name": "spanner-benchmark",
                "service.instance.id": str(uuid.uuid4()),
            }
        )
        self.reader = InMemoryMetricReader()
        self.provider = MeterProvider(
            metric_readers=[self.reader], resource=self.resource
        )
        set_testing_meter_provider(self.provider)

    def tearDown(self):
        set_testing_meter_provider(None)
        self.provider.shutdown()
        from opentelemetry.metrics import _internal

        _internal._METER_PROVIDER_SET_ONCE._done = False
        _internal._METER_PROVIDER = None

    def find_metric(self, metrics_data, name):
        for resource_metric in metrics_data.resource_metrics:
            for scope_metric in resource_metric.scope_metrics:
                for metric in scope_metric.metrics:
                    if metric.name == name:
                        return metric
        return None

    def assert_resource_attributes(self, metrics_data):
        found_service_name = False
        for resource_metric in metrics_data.resource_metrics:
            attributes = resource_metric.resource.attributes
            if attributes.get("service.name") == "spanner-benchmark":
                found_service_name = True
        self.assertTrue(
            found_service_name,
            "Resource should contain 'service.name' as 'spanner-benchmark'",
        )

    def assert_metric_attributes(self, metric, expected_attributes):
        self.assertIsNotNone(metric, "Metric should exist")
        self.assertTrue(
            len(metric.data.data_points) > 0, "Metric should have data points"
        )
        for data_point in metric.data.data_points:
            for key, value in expected_attributes.items():
                self.assertEqual(
                    data_point.attributes.get(key),
                    value,
                    f"Expected attribute {key} to be {value}, got {data_point.attributes.get(key)}",
                )

    def assert_error_count_is_zero(self, metrics_data, benchmark_type, extended=None):
        error_metric = self.find_metric(metrics_data, ERROR_COUNT_NAME)
        if error_metric:
            expected = {
                "client": "python-async-client",
                "benchmark_type": benchmark_type,
            }
            if extended is not None:
                expected["extended"] = extended
            self.assert_metric_attributes(error_metric, expected)
            for data_point in error_metric.data.data_points:
                self.assertEqual(
                    data_point.value, 0, f"Expected 0 errors, got {data_point.value}"
                )

    def test_point_select_workload(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "point-select",
            "--table",
            "test",
            "--tps",
            "10",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        # Verify mock received request
        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        # Retrieve and verify metrics
        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "point-select",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        latency_metric = self.find_metric(metrics_data, LATENCY_NAME)
        self.assert_metric_attributes(latency_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "point-select")

    def test_point_select_with_mock_flag(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "--mock",
            "point-select",
            "--table",
            "test",
            "--tps",
            "10",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        # Retrieve and verify metrics
        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        # Expected benchmark type for mock should be point-select-mock!
        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "point-select-mock",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        latency_metric = self.find_metric(metrics_data, LATENCY_NAME)
        self.assert_metric_attributes(latency_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "point-select-mock")

    def test_select_update_workload(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "select-update",
            "--table",
            "test",
            "--tps",
            "10",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)
        self.wait_for_requests(spanner_types.CommitRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "select-update",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "select-update")

    def test_read_large_result_set_workload(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "read-large-result-set",
            "--table",
            "test",
            "--tps",
            "10",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "read-large-result-set",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        read_latency_metric = self.find_metric(metrics_data, READ_LATENCY_NAME)
        self.assert_metric_attributes(read_latency_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "read-large-result-set")

    def test_read_narrow_result_set_workload(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "read-narrow-result-set",
            "--table",
            "test",
            "--tps",
            "10",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "read-narrow-result-set",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        read_latency_metric = self.find_metric(metrics_data, READ_LATENCY_NAME)
        self.assert_metric_attributes(read_latency_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "read-narrow-result-set")

    def test_tpcc_workload(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "tpcc",
            "--warehouses",
            "1",
            "--clients",
            "2",
            "--items",
            "10",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "tpcc",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "tpcc")

    def test_tpcc_extended_workload(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "tpcc",
            "--warehouses",
            "1",
            "--clients",
            "2",
            "--items",
            "10",
            "--extended",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "tpcc",
            "extended": "true",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "tpcc", extended="true")

    def test_spanner_channel_pool_flag(self):
        import os

        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "--spanner-enable-channel-pool",
            "point-select",
            "--table",
            "test",
            "--tps",
            "10",
        ]

        old_env = os.environ.get("SPANNER_ENABLE_CHANNEL_POOL")
        try:
            if "SPANNER_ENABLE_CHANNEL_POOL" in os.environ:
                del os.environ["SPANNER_ENABLE_CHANNEL_POOL"]
            with patch("sys.argv", args), patch("os._exit"):
                main()
            self.assertEqual(os.environ.get("SPANNER_ENABLE_CHANNEL_POOL"), "true")
        finally:
            if old_env is not None:
                os.environ["SPANNER_ENABLE_CHANNEL_POOL"] = old_env
            elif "SPANNER_ENABLE_CHANNEL_POOL" in os.environ:
                del os.environ["SPANNER_ENABLE_CHANNEL_POOL"]

    def test_single_worker_mode(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "point-select",
            "--table",
            "test",
            "--tps",
            "10",
            "--workers",
            "1",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "point-select",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)
        self.assert_error_count_is_zero(metrics_data, "point-select")

    def test_point_select_multi_worker(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "point-select",
            "--table",
            "test",
            "--tps",
            "10",
            "--workers",
            "2",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "point-select",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        latency_metric = self.find_metric(metrics_data, LATENCY_NAME)
        self.assert_metric_attributes(latency_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "point-select")

    def test_select_update_multi_worker(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "select-update",
            "--table",
            "test",
            "--tps",
            "10",
            "--workers",
            "2",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)
        self.wait_for_requests(spanner_types.CommitRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "select-update",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "select-update")

    def test_read_large_result_set_multi_worker(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "read-large-result-set",
            "--table",
            "test",
            "--tps",
            "10",
            "--workers",
            "2",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "read-large-result-set",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        read_latency_metric = self.find_metric(metrics_data, READ_LATENCY_NAME)
        self.assert_metric_attributes(read_latency_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "read-large-result-set")

    def test_read_narrow_result_set_multi_worker(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "read-narrow-result-set",
            "--table",
            "test",
            "--tps",
            "10",
            "--workers",
            "2",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "read-narrow-result-set",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        read_latency_metric = self.find_metric(metrics_data, READ_LATENCY_NAME)
        self.assert_metric_attributes(read_latency_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "read-narrow-result-set")

    def test_tpcc_multi_worker(self):
        args = [
            "main.py",
            "-p",
            "fake-project",
            "-i",
            "fake-instance",
            "-d",
            "fake-database",
            "--host",
            f"localhost:{self.port}",
            "--duration",
            "1s",
            "--resource-probe-interval",
            "10ms",
            "tpcc",
            "--warehouses",
            "1",
            "--clients",
            "2",
            "--items",
            "10",
            "--workers",
            "2",
        ]

        with patch("sys.argv", args), patch("os._exit"):
            main()

        from google.cloud.spanner_v1.types import spanner as spanner_types

        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attributes = {
            "client": "python-async-client",
            "benchmark_type": "tpcc",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attributes)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attributes)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attributes)

        self.assert_error_count_is_zero(metrics_data, "tpcc")

    def test_resource_monitor_cpu_and_rss_aggregation(self):
        import os

        from src.utils.resources import (
            get_current_resident_set_size,
            get_pid_cpu_time,
            get_pid_resident_set_size,
            get_total_cpu_time,
        )

        my_pid = os.getpid()
        rss = get_pid_resident_set_size(my_pid)
        self.assertGreater(rss, 0, "Process RSS should be greater than 0")

        total_rss = get_current_resident_set_size([my_pid])
        self.assertGreaterEqual(
            total_rss, rss, "Total RSS should include worker PID RSS"
        )

        cpu_time = get_pid_cpu_time(my_pid)
        self.assertGreaterEqual(
            cpu_time, 0.0, "Process CPU time should be non-negative"
        )

        total_cpu = get_total_cpu_time([my_pid])
        self.assertGreaterEqual(
            total_cpu, cpu_time, "Total CPU time should aggregate across PIDs"
        )

    def test_worker_crash_task_reclamation(self):
        benchmark = DummyBenchmark(
            database=None,
            latency_histogram=MagicMock(),
            operation_counter=MagicMock(),
            error_counter=MagicMock(),
            memory_usage_histogram=None,
            cpu_utilization_histogram=None,
            resource_probe_interval_str="0",
            table_name="test",
            min_id=1,
            max_id=10,
            tps=10,
            threads=4,
            duration_sec=1.0,
            for_alerting=False,
            benchmark_name="test",
            load_type=LoadType.CLOSED_LOOP,
            workers=2,
            project_id="p",
            instance_id="i",
            database_id="d",
        )

        benchmark._worker_active = [True, True]
        benchmark._worker_in_flight = [3, 1]
        benchmark._outstanding_tasks = 4
        mock_conn = MagicMock()
        mock_conn.poll.side_effect = EOFError("Pipe closed")
        mock_healthy_conn = MagicMock()
        benchmark._worker_conns = [mock_conn, mock_healthy_conn]

        mock_key = MagicMock()
        mock_key.data = 0
        mock_key.fileobj = mock_conn

        benchmark._selector = MagicMock()
        benchmark._selector.select.side_effect = [
            [(mock_key, None)],
            Exception("Exit loop"),
        ]

        benchmark._worker_response_listener()

        self.assertFalse(
            benchmark._worker_active[0], "Worker 0 should be marked inactive"
        )
        self.assertEqual(
            benchmark._outstanding_tasks,
            4,
            "Outstanding tasks should be replenished to 4",
        )
        self.assertEqual(
            benchmark._error_count, 3, "Error count should reflect 3 lost tasks"
        )
        benchmark.error_counter.add.assert_called_with(3, benchmark.get_attributes())
        self.assertEqual(
            mock_healthy_conn.send.call_count,
            3,
            "Lost tasks should be replenished to healthy worker 1",
        )

    def test_argparse_workers_flag_positions(self):
        """Verifies that --workers can be specified before or after subcommand without being overridden."""
        from main import build_parser

        parser = build_parser()
        args_before = parser.parse_args(
            [
                "--workers",
                "3",
                "-p",
                "p",
                "-i",
                "i",
                "-d",
                "d",
                "point-select",
                "--table",
                "t",
            ]
        )
        self.assertEqual(args_before.workers, 3)

        args_after = parser.parse_args(
            [
                "-p",
                "p",
                "-i",
                "i",
                "-d",
                "d",
                "point-select",
                "--workers",
                "3",
                "--table",
                "t",
            ]
        )
        self.assertEqual(args_after.workers, 3)

        args_omitted = parser.parse_args(
            ["-p", "p", "-i", "i", "-d", "d", "point-select", "--table", "t"]
        )
        self.assertIsNone(getattr(args_omitted, "workers", None))

    def test_cpu_utilization_negative_clamping(self):
        """Verifies that ResourceMonitor clamps CPU utilization to 0.0 when a worker crashes."""
        from src.utils.resources import ResourceMonitor

        mock_cpu_hist = MagicMock()
        monitor = ResourceMonitor(
            probe_interval_str="0",
            memory_usage_histogram=None,
            cpu_utilization_histogram=mock_cpu_hist,
            attributes={},
            is_stopped_check=lambda: False,
            worker_pids_supplier=lambda: [12345],
        )
        monitor._last_cpu_time = 100.0
        monitor._last_wall_time = time.perf_counter() - 1.0

        with patch("src.utils.resources.get_total_cpu_time", return_value=90.0):
            monitor._probe_resource_usage()

        # Utilization recorded should be 0.0 (not negative)
        mock_cpu_hist.record.assert_called_once()
        recorded_val = mock_cpu_hist.record.call_args[0][0]
        self.assertEqual(recorded_val, 0.0, "CPU utilization must be clamped to 0.0")

    def test_stop_does_not_prematurely_cleanup_workers(self):
        """Verifies that stop() does not invoke _cleanup_workers immediately, allowing task draining."""
        benchmark = DummyBenchmark(
            database=None,
            latency_histogram=MagicMock(),
            operation_counter=MagicMock(),
            error_counter=MagicMock(),
            memory_usage_histogram=None,
            cpu_utilization_histogram=None,
            resource_probe_interval_str="0",
            table_name="test",
            min_id=1,
            max_id=10,
            tps=10,
            threads=2,
            duration_sec=1.0,
            for_alerting=False,
            benchmark_name="test",
            load_type=LoadType.CLOSED_LOOP,
            workers=2,
            project_id="p",
            instance_id="i",
            database_id="d",
        )
        benchmark._cleanup_workers = MagicMock()
        benchmark.stop()
        self.assertTrue(benchmark.is_stopped, "is_stopped should be True after stop()")
        benchmark._cleanup_workers.assert_not_called()
