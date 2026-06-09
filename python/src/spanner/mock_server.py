import grpc
from concurrent import futures
from google.cloud.spanner_v1 import types
import google.cloud.spanner_v1.testing.spanner_database_admin_pb2_grpc as database_admin_grpc
import google.cloud.spanner_v1.testing.spanner_pb2_grpc as spanner_grpc
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

def start_mock_spanner(table_name: str = "test", socket_path: str = None):
    """
    Starts an in-memory gRPC mock Spanner server.
    If socket_path is provided, binds to a Unix Domain Socket. Otherwise, binds to TCP loopback.
    """
    server_executor = futures.ThreadPoolExecutor(max_workers=10)
    server = grpc.server(server_executor)
    servicer = SpannerServicer()
    
    # Custom streaming read mapping (matches test suite setup)
    def custom_streaming_read(request, context):
        servicer._requests.append(request)
        servicer.mock_spanner.pop_error(context)
        started_transaction = (
            servicer._SpannerServicer__maybe_create_transaction(request)
        )
        sql_key = f"read:{request.table}"
        partials = servicer.mock_spanner.get_result_as_partial_result_sets(
            sql_key
        )
        if started_transaction:
            partials[0].metadata.transaction = started_transaction
        for result in partials:
            yield result

    servicer.StreamingRead = custom_streaming_read
    spanner_grpc.add_SpannerServicer_to_server(servicer, server)
    
    admin_servicer = DatabaseAdminServicer()
    database_admin_grpc.add_DatabaseAdminServicer_to_server(
        admin_servicer, server
    )
    
    if socket_path:
        server.add_insecure_port(f"unix://{socket_path}")
        port = 0
    else:
        port = server.add_insecure_port("127.0.0.1:0")
        
    server.start()

    # Pre-register Point Select mock results
    mock = servicer.mock_spanner
    mock.clear_results()
    
    # Add both lowercase and uppercase variations to ensure matching
    result_set = make_result_set(
        [("id", types.TypeCode.INT64), ("value", types.TypeCode.STRING)],
        [["1", "value1"]],
    )
    mock.add_result(f"SELECT * FROM {table_name} WHERE id = @id", result_set)
    mock.add_result(f"select * from {table_name} where id = @id", result_set)
    
    return server, server_executor, port
