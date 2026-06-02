import unittest
import time
import grpc
from concurrent import futures
from google.cloud.spanner_v1 import types
from google.cloud.spanner_v1.testing.mock_spanner import SpannerServicer, DatabaseAdminServicer
import google.cloud.spanner_v1.testing.spanner_pb2_grpc as spanner_grpc
import google.cloud.spanner_v1.testing.spanner_database_admin_pb2_grpc as database_admin_grpc
from src.benchmarks.read_large_result_set import SQL as LARGE_RESULT_SET_SQL

def make_result_set(cols, rows_data, stats=None):
    fields = []
    for col_name, col_type in cols:
        fields.append(types.StructType.Field(name=col_name, type=types.Type(code=col_type)))
    metadata = types.ResultSetMetadata(row_type=types.StructType(fields=fields))
    return types.ResultSet(metadata=metadata, rows=rows_data, stats=stats)

class BaseBenchmarkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server_executor = futures.ThreadPoolExecutor(max_workers=10)
        cls.server = grpc.server(cls.server_executor)
        cls.servicer = SpannerServicer()
        spanner_grpc.add_SpannerServicer_to_server(cls.servicer, cls.server)
        cls.admin_servicer = DatabaseAdminServicer()
        database_admin_grpc.add_DatabaseAdminServicer_to_server(cls.admin_servicer, cls.server)
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
        mock = cls.servicer.mock_spanner
        mock.clear_results()

        # Point Select workload
        mock.add_result(
            "select * from test where id = @id",
            make_result_set([("id", types.TypeCode.INT64), ("value", types.TypeCode.STRING)], [["1", "value1"]])
        )

        # Select and Update workload
        mock.add_result(
            "select id from test where id = @id",
            make_result_set([("id", types.TypeCode.INT64)], [["1"]])
        )
        mock.add_result(
            "update test set value = @value where id = @id",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "insert into test (id, value) values (@id, @value)",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )

        # Read Large Result Set workload
        cols_large = [
            ("random_bool", types.TypeCode.BOOL),
            ("random_bytes", types.TypeCode.BYTES),
            ("random_date", types.TypeCode.DATE),
            ("random_float32", types.TypeCode.FLOAT64),
            ("random_float64", types.TypeCode.FLOAT64),
            ("random_interval", types.TypeCode.STRING),
            ("random_json", types.TypeCode.JSON),
            ("random_int64", types.TypeCode.INT64),
            ("random_numeric", types.TypeCode.NUMERIC),
            ("random_string", types.TypeCode.STRING),
            ("random_timestamp", types.TypeCode.TIMESTAMP),
            ("random_uuid", types.TypeCode.STRING)
        ]
        row_large = [True, "Ynl0ZXM=", "2026-06-02", 1.0, 2.0, "10s", '{"key":"val"}', "42", "10.5", "string", "2026-06-02T14:00:00Z", "uuid"]
        mock.add_result(
            LARGE_RESULT_SET_SQL,
            make_result_set(cols_large, [row_large] * 10)
        )

        # TPC-C workload
        mock.add_result(
            "select count(*) from warehouse",
            make_result_set([("count", types.TypeCode.INT64)], [["1"]])
        )
        mock.add_result(
            "select next_order_id from district where warehouse_id = @w and district_id = @d",
            make_result_set([("next_order_id", types.TypeCode.INT64)], [["1000"]])
        )
        mock.add_result(
            "select discount, last_name from customer where warehouse_id = @w and district_id = @d and customer_id = @c",
            make_result_set([("discount", types.TypeCode.FLOAT64), ("last_name", types.TypeCode.STRING)], [[0.1, "last"]])
        )
        mock.add_result(
            "select balance, first_name, last_name from customer where warehouse_id = @w and district_id = @d and customer_id = @c",
            make_result_set([("balance", types.TypeCode.FLOAT64), ("first_name", types.TypeCode.STRING), ("last_name", types.TypeCode.STRING)], [[100.0, "first", "last"]])
        )
        mock.add_result(
            "select order_id from orders where warehouse_id = @w and district_id = @d and customer_id = @c order by order_id desc limit 1",
            make_result_set([("order_id", types.TypeCode.INT64)], [["1000"]])
        )
        mock.add_result(
            "select order_line_id, item_id, quantity, amount from order_line where warehouse_id = @w and district_id = @d and order_id = @o",
            make_result_set([
                ("order_line_id", types.TypeCode.INT64),
                ("item_id", types.TypeCode.INT64),
                ("quantity", types.TypeCode.INT64),
                ("amount", types.TypeCode.FLOAT64)
            ], [["1", "100", "5", 25.0]])
        )
        mock.add_result(
            "select order_id from new_orders where warehouse_id = @w and district_id = @d order by created_timestamp asc limit 1",
            make_result_set([("order_id", types.TypeCode.INT64)], [["1000"]])
        )
        mock.add_result(
            "update district set next_order_id = @next where warehouse_id = @w and district_id = @d",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "insert into orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) values (@w, @d, @o, @c, @dt, @cnt, 1)",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "insert into new_orders (warehouse_id, district_id, order_id, created_timestamp) values (@w, @d, @o, @dt)",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "insert into order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) values (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "update stock set quantity = quantity - @qty, order_count = order_count + 1 where warehouse_id = @w and item_id = @i",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "update warehouse set ytd = ytd + @amt where warehouse_id = @w",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "update district set ytd = ytd + @amt where warehouse_id = @w and district_id = @d",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "update customer set balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 where warehouse_id = @w and district_id = @d and customer_id = @c",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "insert into history (warehouse_id, district_id, history_id, customer_id, date, amount, data) values (@w, @d, @h, @c, @dt, @amt, 'history')",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "delete from new_orders where warehouse_id = @w and district_id = @d and order_id = @o",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "update orders set carrier_id = @c where warehouse_id = @w and district_id = @d and order_id = @o",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "update order_line set delivery_date = @dt where warehouse_id = @w and district_id = @d and order_id = @o",
            make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1))
        )
        mock.add_result(
            "select count(distinct s.item_id) from order_line ol join stock s on s.warehouse_id = ol.warehouse_id and s.item_id = ol.item_id where ol.warehouse_id = @w and ol.district_id = @d and ol.order_id >= @min_order_id and ol.order_id < @next_order_id and s.quantity < @threshold",
            make_result_set([("count", types.TypeCode.INT64)], [["0"]])
        )

    def wait_for_requests(self, request_class, timeout_seconds=5.0, min_count=1):
        start_time = time.perf_counter()
        while time.perf_counter() - start_time < timeout_seconds:
            matching = [req for req in self.servicer.requests if isinstance(req, request_class)]
            if len(matching) >= min_count:
                return matching
            time.sleep(0.01)
        raise AssertionError(f"Timeout waiting for {request_class.__name__} to receive at least {min_count} requests")
