from concurrent import futures

import google.cloud.spanner_v1.testing.spanner_database_admin_pb2_grpc as database_admin_grpc
import google.cloud.spanner_v1.testing.spanner_pb2_grpc as spanner_grpc
import grpc
from google.cloud.spanner_v1 import types
from google.cloud.spanner_v1.testing.mock_spanner import (
    DatabaseAdminServicer,
    SpannerServicer,
)

from src.benchmarks.read_large_result_set import SQL as LARGE_RESULT_SET_SQL
from src.benchmarks.read_narrow_result_set import SQL as NARROW_RESULT_SET_SQL


def make_result_set(cols, rows_data, stats=None):
    fields = []
    for col_name, col_type in cols:
        fields.append(
            types.StructType.Field(name=col_name, type=types.Type(code=col_type))
        )
    metadata = types.ResultSetMetadata(row_type=types.StructType(fields=fields))
    return types.ResultSet(metadata=metadata, rows=rows_data, stats=stats)


def register_all_mock_results(mock, table_name="test"):
    mock.clear_results()

    # Point Select workload
    result_set = make_result_set(
        [("id", types.TypeCode.INT64), ("value", types.TypeCode.STRING)],
        [["1", "value1"]],
    )
    mock.add_result(f"SELECT * FROM {table_name} WHERE id = @id", result_set)
    mock.add_result(f"select * from {table_name} where id = @id", result_set)

    # Select and Update workload
    mock.add_result(
        f"select id from {table_name} where id = @id",
        make_result_set([("id", types.TypeCode.INT64)], [["1"]]),
    )
    mock.add_result(
        f"SELECT id FROM {table_name} WHERE id = @id",
        make_result_set([("id", types.TypeCode.INT64)], [["1"]]),
    )
    mock.add_result(
        f"update {table_name} set value = @value where id = @id",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        f"UPDATE {table_name} SET value = @value WHERE id = @id",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        f"insert into {table_name} (id, value) values (@id, @value)",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        f"INSERT INTO {table_name} (id, value) VALUES (@id, @value)",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
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
        ("random_uuid", types.TypeCode.STRING),
    ]
    row_large = [
        True,
        "Ynl0ZXM=",
        "2026-06-02",
        1.0,
        2.0,
        "10s",
        '{"key":"val"}',
        "42",
        "10.5",
        "string",
        "2026-06-02T14:00:00Z",
        "uuid",
    ]
    mock.add_result(LARGE_RESULT_SET_SQL, make_result_set(cols_large, [row_large] * 10))

    # Read Narrow Result Set workload
    cols_narrow = [
        ("random_int64_1", types.TypeCode.INT64),
        ("random_int64_2", types.TypeCode.INT64),
    ]
    row_narrow = ["100", "200"]
    mock.add_result(
        NARROW_RESULT_SET_SQL, make_result_set(cols_narrow, [row_narrow] * 10)
    )

    # TPC-C workload
    mock.add_result(
        "select count(*) from warehouse",
        make_result_set([("count", types.TypeCode.INT64)], [["1"]]),
    )
    mock.add_result(
        "SELECT COUNT(*) FROM warehouse",
        make_result_set([("count", types.TypeCode.INT64)], [["1"]]),
    )
    mock.add_result(
        "select next_order_id from district where warehouse_id = @w and district_id = @d",
        make_result_set([("next_order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d",
        make_result_set([("next_order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "select next_order_id from district where warehouse_id = @w and district_id = @d for update",
        make_result_set([("next_order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d FOR UPDATE",
        make_result_set([("next_order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "select discount, last_name from customer where warehouse_id = @w and district_id = @d and customer_id = @c",
        make_result_set(
            [
                ("discount", types.TypeCode.FLOAT64),
                ("last_name", types.TypeCode.STRING),
            ],
            [[0.1, "last"]],
        ),
    )
    mock.add_result(
        "SELECT discount, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
        make_result_set(
            [
                ("discount", types.TypeCode.FLOAT64),
                ("last_name", types.TypeCode.STRING),
            ],
            [[0.1, "last"]],
        ),
    )
    mock.add_result(
        "select balance, first_name, last_name from customer where warehouse_id = @w and district_id = @d and customer_id = @c",
        make_result_set(
            [
                ("balance", types.TypeCode.FLOAT64),
                ("first_name", types.TypeCode.STRING),
                ("last_name", types.TypeCode.STRING),
            ],
            [[100.0, "first", "last"]],
        ),
    )
    mock.add_result(
        "SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
        make_result_set(
            [
                ("balance", types.TypeCode.FLOAT64),
                ("first_name", types.TypeCode.STRING),
                ("last_name", types.TypeCode.STRING),
            ],
            [[100.0, "first", "last"]],
        ),
    )
    mock.add_result(
        "select order_id from orders where warehouse_id = @w and district_id = @d and customer_id = @c order by order_id desc limit 1",
        make_result_set([("order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "SELECT order_id FROM orders WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c ORDER BY order_id DESC LIMIT 1",
        make_result_set([("order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "select order_line_id, item_id, quantity, amount from order_line where warehouse_id = @w and district_id = @d and order_id = @o",
        make_result_set(
            [
                ("order_line_id", types.TypeCode.INT64),
                ("item_id", types.TypeCode.INT64),
                ("quantity", types.TypeCode.INT64),
                ("amount", types.TypeCode.FLOAT64),
            ],
            [["1", "100", "5", 25.0]],
        ),
    )
    mock.add_result(
        "SELECT order_line_id, item_id, quantity, amount FROM order_line WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
        make_result_set(
            [
                ("order_line_id", types.TypeCode.INT64),
                ("item_id", types.TypeCode.INT64),
                ("quantity", types.TypeCode.INT64),
                ("amount", types.TypeCode.FLOAT64),
            ],
            [["1", "100", "5", 25.0]],
        ),
    )
    mock.add_result(
        "select order_id from new_orders where warehouse_id = @w and district_id = @d order by created_timestamp asc limit 1",
        make_result_set([("order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "SELECT order_id FROM new_orders WHERE warehouse_id = @w AND district_id = @d ORDER BY created_timestamp ASC LIMIT 1",
        make_result_set([("order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "select order_id from new_orders where warehouse_id = @w and district_id = @d order by created_timestamp asc limit 1 for update",
        make_result_set([("order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "SELECT order_id FROM new_orders WHERE warehouse_id = @w AND district_id = @d ORDER BY created_timestamp ASC LIMIT 1 FOR UPDATE",
        make_result_set([("order_id", types.TypeCode.INT64)], [["1000"]]),
    )
    mock.add_result(
        "select count(distinct s.item_id) from order_line ol join stock s on s.warehouse_id = ol.warehouse_id and s.item_id = ol.item_id where ol.warehouse_id = @w and ol.district_id = @d and ol.order_id >= @min_order_id and ol.order_id < @next_order_id and s.quantity < @threshold",
        make_result_set([("count", types.TypeCode.INT64)], [["0"]]),
    )
    mock.add_result(
        "SELECT COUNT(DISTINCT s.item_id) FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @min_order_id AND ol.order_id < @next_order_id AND s.quantity < @threshold",
        make_result_set([("count", types.TypeCode.INT64)], [["0"]]),
    )
    mock.add_result(
        "SELECT item_id, quantity FROM stock WHERE warehouse_id = @w AND item_id IN UNNEST(@items)",
        make_result_set(
            [("item_id", types.TypeCode.INT64), ("quantity", types.TypeCode.INT64)],
            [["100", "50"]],
        ),
    )
    mock.add_result(
        "select item_id, quantity from stock where warehouse_id = @w and item_id in unnest(@items)",
        make_result_set(
            [("item_id", types.TypeCode.INT64), ("quantity", types.TypeCode.INT64)],
            [["100", "50"]],
        ),
    )
    mock.add_result(
        "SELECT DISTINCT s.item_id FROM order_line ol JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w AND ol.district_id = @d AND ol.order_id >= @min_order_id AND ol.order_id < @next_order_id AND s.quantity < @threshold",
        make_result_set([("item_id", types.TypeCode.INT64)], [["100"]]),
    )
    mock.add_result(
        "select distinct s.item_id from order_line ol join stock s on s.warehouse_id = ol.warehouse_id and s.item_id = ol.item_id where ol.warehouse_id = @w and ol.district_id = @d and ol.order_id >= @min_order_id and ol.order_id < @next_order_id and s.quantity < @threshold",
        make_result_set([("item_id", types.TypeCode.INT64)], [["100"]]),
    )
    mock.add_result(
        "read:customer",
        make_result_set(
            [
                ("balance", types.TypeCode.FLOAT64),
                ("first_name", types.TypeCode.STRING),
                ("last_name", types.TypeCode.STRING),
            ],
            [[100.0, "first", "last"]],
        ),
    )
    mock.add_result(
        "read:order_line",
        make_result_set(
            [
                ("order_line_id", types.TypeCode.INT64),
                ("item_id", types.TypeCode.INT64),
                ("quantity", types.TypeCode.INT64),
                ("amount", types.TypeCode.FLOAT64),
            ],
            [["1", "100", "5", 25.0]],
        ),
    )

    # DML statements for TPC-C
    mock.add_result(
        "update district set next_order_id = @next where warehouse_id = @w and district_id = @d",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "insert into orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) values (@w, @d, @o, @c, @dt, @cnt, 1)",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) VALUES (@w, @d, @o, @c, @dt, @cnt, 1)",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "insert into new_orders (warehouse_id, district_id, order_id, created_timestamp) values (@w, @d, @o, @dt)",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) VALUES (@w, @d, @o, @dt)",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "insert into order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) values (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "update stock set quantity = quantity - @qty, order_count = order_count + 1 where warehouse_id = @w and item_id = @i",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 WHERE warehouse_id = @w AND item_id = @i",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "update warehouse set ytd = ytd + @amt where warehouse_id = @w",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "update district set ytd = ytd + @amt where warehouse_id = @w and district_id = @d",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "update customer set balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 where warehouse_id = @w and district_id = @d and customer_id = @c",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "insert into history (warehouse_id, district_id, history_id, customer_id, date, amount, data) values (@w, @d, @h, @c, @dt, @amt, 'history')",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) VALUES (@w, @d, @h, @c, @dt, @amt, 'history')",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "delete from new_orders where warehouse_id = @w and district_id = @d and order_id = @o",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "update orders set carrier_id = @c where warehouse_id = @w and district_id = @d and order_id = @o",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "update order_line set delivery_date = @dt where warehouse_id = @w and district_id = @d and order_id = @o",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )
    mock.add_result(
        "UPDATE order_line SET delivery_date = @dt WHERE warehouse_id = @w AND district_id = @d AND order_id = @o",
        make_result_set([], [], stats=types.ResultSetStats(row_count_exact=1)),
    )


def start_mock_spanner(table_name: str = "test", socket_path: str = None):
    """Starts an in-memory gRPC mock Spanner server.

    If socket_path is provided, binds to a Unix Domain Socket. Otherwise, binds
    to TCP loopback.
    """
    server_executor = futures.ThreadPoolExecutor(max_workers=10)
    server = grpc.server(server_executor)
    servicer = SpannerServicer()

    # Custom streaming read mapping (matches test suite setup)
    def custom_streaming_read(request, context):
        servicer._requests.append(request)
        servicer.mock_spanner.pop_error(context)
        started_transaction = servicer._SpannerServicer__maybe_create_transaction(
            request
        )
        sql_key = f"read:{request.table}"
        partials = servicer.mock_spanner.get_result_as_partial_result_sets(sql_key)
        if started_transaction:
            partials[0].metadata.transaction = started_transaction
        for result in partials:
            yield result

    servicer.StreamingRead = custom_streaming_read
    spanner_grpc.add_SpannerServicer_to_server(servicer, server)

    admin_servicer = DatabaseAdminServicer()
    database_admin_grpc.add_DatabaseAdminServicer_to_server(admin_servicer, server)

    if socket_path:
        server.add_insecure_port(f"unix://{socket_path}")
        port = 0
    else:
        port = server.add_insecure_port("127.0.0.1:0")

    server.start()

    # Register all mock results
    register_all_mock_results(servicer.mock_spanner, table_name)

    return server, server_executor, port
