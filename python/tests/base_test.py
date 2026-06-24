import time
import unittest
from concurrent import futures

import google.cloud.spanner_v1.testing.spanner_database_admin_pb2_grpc as database_admin_grpc
import google.cloud.spanner_v1.testing.spanner_pb2_grpc as spanner_grpc
import grpc
from google.cloud.spanner_v1 import types
from google.cloud.spanner_v1.testing.mock_spanner import (
    DatabaseAdminServicer,
    SpannerServicer,
)


def make_result_set(cols, rows_data, stats=None):
    fields = []
    for col_name, col_type in cols:
        fields.append(
            types.StructType.Field(name=col_name, type=types.Type(code=col_type))
        )
    metadata = types.ResultSetMetadata(row_type=types.StructType(fields=fields))
    return types.ResultSet(metadata=metadata, rows=rows_data, stats=stats)


class BaseBenchmarkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server_executor = futures.ThreadPoolExecutor(max_workers=10)
        cls.server = grpc.server(cls.server_executor)
        cls.servicer = SpannerServicer()

        def custom_streaming_read(request, context):
            cls.servicer._requests.append(request)
            cls.servicer.mock_spanner.pop_error(context)
            started_transaction = (
                cls.servicer._SpannerServicer__maybe_create_transaction(request)
            )
            sql_key = f"read:{request.table}"
            partials = cls.servicer.mock_spanner.get_result_as_partial_result_sets(
                sql_key
            )
            if started_transaction:
                partials[0].metadata.transaction = started_transaction
            for result in partials:
                yield result

        cls.servicer.StreamingRead = custom_streaming_read
        spanner_grpc.add_SpannerServicer_to_server(cls.servicer, cls.server)
        cls.admin_servicer = DatabaseAdminServicer()
        database_admin_grpc.add_DatabaseAdminServicer_to_server(
            cls.admin_servicer, cls.server
        )
        cls.port = cls.server.add_insecure_port("[::]:0")
        cls.server.start()
        cls.register_mock_results()

    @classmethod
    def tearDownClass(cls):
        shutdown_event = cls.server.stop(0)
        shutdown_event.wait()
        cls.server_executor.shutdown(wait=True)

    def setUp(self):
        self.servicer.clear_requests()

    @classmethod
    def register_mock_results(cls):
        from src.spanner.mock_server import register_all_mock_results

        register_all_mock_results(cls.servicer.mock_spanner, "test")

    def wait_for_requests(self, request_class, timeout_seconds=5.0, min_count=1):
        start_time = time.perf_counter()
        while time.perf_counter() - start_time < timeout_seconds:
            matching = [
                req for req in self.servicer.requests if isinstance(req, request_class)
            ]
            if len(matching) >= min_count:
                return matching
            time.sleep(0.01)
        raise AssertionError(
            f"Timeout waiting for {request_class.__name__} to receive at least {min_count} requests"
        )
