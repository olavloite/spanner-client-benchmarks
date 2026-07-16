import uuid
from unittest.mock import patch

from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.resources import Resource

from main import main
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
            attrs = resource_metric.resource.attributes
            if attrs.get("service.name") == "spanner-benchmark":
                found_service_name = True
        self.assertTrue(
            found_service_name,
            "Resource should contain 'service.name' as 'spanner-benchmark'",
        )

    def assert_metric_attributes(self, metric, expected_attrs):
        self.assertIsNotNone(metric, "Metric should exist")
        self.assertTrue(
            len(metric.data.data_points) > 0, "Metric should have data points"
        )
        for dp in metric.data.data_points:
            for key, value in expected_attrs.items():
                self.assertEqual(
                    dp.attributes.get(key),
                    value,
                    f"Expected attribute {key} to be {value}, got {dp.attributes.get(key)}",
                )

    def assert_error_count_is_zero(self, metrics_data, benchmark_type, extended=None):
        error_metric = self.find_metric(metrics_data, ERROR_COUNT_NAME)
        if error_metric:
            expected = {
                "client": "python-client",
                "benchmark_type": benchmark_type,
            }
            if extended is not None:
                expected["extended"] = extended
            self.assert_metric_attributes(error_metric, expected)
            for dp in error_metric.data.data_points:
                self.assertEqual(dp.value, 0, f"Expected 0 errors, got {dp.value}")

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

        expected_attrs = {
            "client": "python-client",
            "benchmark_type": "point-select",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attrs)

        latency_metric = self.find_metric(metrics_data, LATENCY_NAME)
        self.assert_metric_attributes(latency_metric, expected_attrs)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attrs)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attrs)

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
        expected_attrs = {
            "client": "python-client",
            "benchmark_type": "point-select-mock",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attrs)

        latency_metric = self.find_metric(metrics_data, LATENCY_NAME)
        self.assert_metric_attributes(latency_metric, expected_attrs)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attrs)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attrs)

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

        # Read-write transaction starts inline inside ExecuteSqlRequest, then finishes with CommitRequest
        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)
        self.wait_for_requests(spanner_types.CommitRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attrs = {
            "client": "python-client",
            "benchmark_type": "select-update",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attrs)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attrs)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attrs)

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

        expected_attrs = {
            "client": "python-client",
            "benchmark_type": "read-large-result-set",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attrs)

        # Large read latency name is spanner_client_benchmarks/read_latency
        read_latency_metric = self.find_metric(metrics_data, READ_LATENCY_NAME)
        self.assert_metric_attributes(read_latency_metric, expected_attrs)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attrs)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attrs)

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

        expected_attrs = {
            "client": "python-client",
            "benchmark_type": "read-narrow-result-set",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attrs)

        # Large read latency name is spanner_client_benchmarks/read_latency
        read_latency_metric = self.find_metric(metrics_data, READ_LATENCY_NAME)
        self.assert_metric_attributes(read_latency_metric, expected_attrs)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attrs)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attrs)

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

        # Verify that at least capacity check count(*) from warehouse query is executed
        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attrs = {
            "client": "python-client",
            "benchmark_type": "tpcc",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attrs)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attrs)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attrs)

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

        # Verify that at least one execute sql request is executed
        self.wait_for_requests(spanner_types.ExecuteSqlRequest, min_count=1)

        metrics_data = self.reader.get_metrics_data()
        self.assert_resource_attributes(metrics_data)

        expected_attrs = {
            "client": "python-client",
            "benchmark_type": "tpcc",
            "extended": "true",
        }

        op_count_metric = self.find_metric(metrics_data, OPERATION_COUNT_NAME)
        self.assert_metric_attributes(op_count_metric, expected_attrs)

        mem_metric = self.find_metric(metrics_data, MEMORY_USAGE_NAME)
        self.assert_metric_attributes(mem_metric, expected_attrs)

        cpu_metric = self.find_metric(metrics_data, CPU_UTILIZATION_NAME)
        self.assert_metric_attributes(cpu_metric, expected_attrs)

        self.assert_error_count_is_zero(metrics_data, "tpcc", extended="true")
