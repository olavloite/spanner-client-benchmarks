package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.MockSpannerServiceImpl;
import com.google.cloud.spanner.MockSpannerServiceImpl.StatementResult;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.admin.database.v1.MockDatabaseAdminImpl;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.spanner.v1.ResultSet;
import com.google.spanner.v1.ResultSetMetadata;
import com.google.spanner.v1.StructType;
import com.google.spanner.v1.StructType.Field;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import io.grpc.Server;
import io.grpc.ServerBuilder;

public class MockServerUtil {

  public static Server startMockSpannerServer(BenchmarkApp parent, String tableName) {
    if (tableName == null || tableName.isEmpty()) {
      tableName = "my_table";
    }

    MockSpannerServiceImpl mockSpanner = new MockSpannerServiceImpl();
    MockDatabaseAdminImpl mockDatabaseAdmin = new MockDatabaseAdminImpl();

    ResultSetMetadata metadata =
        ResultSetMetadata.newBuilder()
            .setRowType(
                StructType.newBuilder()
                    .addFields(
                        Field.newBuilder()
                            .setName("id")
                            .setType(Type.newBuilder().setCode(TypeCode.INT64)))
                    .addFields(
                        Field.newBuilder()
                            .setName("value")
                            .setType(Type.newBuilder().setCode(TypeCode.STRING))))
            .build();
    ResultSet resultSet =
        ResultSet.newBuilder()
            .setMetadata(metadata)
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1"))
                    .addValues(Value.newBuilder().setStringValue("test-value")))
            .build();

    // Point Select / Select and Update queries
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT * FROM " + tableName + " WHERE id = @id"), resultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT id FROM " + tableName + " WHERE id = @id"), resultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of("UPDATE " + tableName + " SET value = @value WHERE id = @id"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of("INSERT INTO " + tableName + " (id, value) VALUES (@id, @value)"), 1L));

    // YCSB Queries mock
    StructType.Builder ycsbRowTypeBuilder =
        StructType.newBuilder()
            .addFields(
                Field.newBuilder()
                    .setType(Type.newBuilder().setCode(TypeCode.STRING))
                    .setName("id"));
    ListValue.Builder ycsbRowValuesBuilder =
        ListValue.newBuilder()
            .addValues(com.google.protobuf.Value.newBuilder().setStringValue("user000000000001"));
    for (int i = 0; i < 10; i++) {
      ycsbRowTypeBuilder.addFields(
          Field.newBuilder()
              .setType(Type.newBuilder().setCode(TypeCode.STRING))
              .setName("field" + i));
      ycsbRowValuesBuilder.addValues(
          com.google.protobuf.Value.newBuilder().setStringValue("testvalue" + i));
    }
    ResultSet ycsbResultSet =
        ResultSet.newBuilder()
            .setMetadata(ResultSetMetadata.newBuilder().setRowType(ycsbRowTypeBuilder))
            .addRows(ycsbRowValuesBuilder)
            .build();
    String ycsbFields =
        "id, field0, field1, field2, field3, field4, field5, field6, field7, field8, field9";
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT " + ycsbFields + " FROM " + tableName + " WHERE id = @id"),
            ycsbResultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT " + ycsbFields + " FROM usertable WHERE id = @id"),
            ycsbResultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of(
                "SELECT "
                    + ycsbFields
                    + " FROM "
                    + tableName
                    + " WHERE id >= @startKey ORDER BY id LIMIT @scanLength"),
            ycsbResultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of(
                "SELECT "
                    + ycsbFields
                    + " FROM usertable WHERE id >= @startKey ORDER BY id LIMIT @scanLength"),
            ycsbResultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of(
                "SELECT field0, field1, field2, field3, field4, field5, field6, field7, field8, field9 FROM usertable"),
            ycsbResultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of(
                "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '' AND TABLE_NAME = @tableName"),
            ycsbResultSet));

    // Large result set mock
    ResultSet largeResultSet =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_bool")
                                    .setType(Type.newBuilder().setCode(TypeCode.BOOL).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_bytes")
                                    .setType(Type.newBuilder().setCode(TypeCode.BYTES).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_date")
                                    .setType(Type.newBuilder().setCode(TypeCode.DATE).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_float32")
                                    .setType(Type.newBuilder().setCode(TypeCode.FLOAT32).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_float64")
                                    .setType(Type.newBuilder().setCode(TypeCode.FLOAT64).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_interval")
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_json")
                                    .setType(Type.newBuilder().setCode(TypeCode.JSON).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_int64")
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_numeric")
                                    .setType(Type.newBuilder().setCode(TypeCode.NUMERIC).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_string")
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_timestamp")
                                    .setType(Type.newBuilder().setCode(TypeCode.TIMESTAMP).build()))
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_uuid")
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build()))
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setBoolValue(true).build())
                    .addValues(Value.newBuilder().setStringValue("YWJj").build())
                    .addValues(Value.newBuilder().setStringValue("2026-06-02").build())
                    .addValues(Value.newBuilder().setNumberValue(1.23).build())
                    .addValues(Value.newBuilder().setNumberValue(4.56).build())
                    .addValues(Value.newBuilder().setStringValue("0-0 0 0:0:0").build())
                    .addValues(Value.newBuilder().setStringValue("{\"key\":\"val\"}").build())
                    .addValues(Value.newBuilder().setStringValue("100").build())
                    .addValues(Value.newBuilder().setStringValue("12.34").build())
                    .addValues(Value.newBuilder().setStringValue("hello").build())
                    .addValues(Value.newBuilder().setStringValue("2026-06-02T13:43:09Z").build())
                    .addValues(
                        Value.newBuilder()
                            .setStringValue("00000000-0000-0000-0000-000000000000")
                            .build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT\n  MOD(FARM_FINGERPRINT"), largeResultSet));

    // TPCC Queries mock
    ResultSet warehouseCountResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("count")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT COUNT(*) FROM warehouse"), warehouseCountResult));

    ResultSet districtResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("next_order_id")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.FLOAT64).build())
                                    .setName("tax")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1000").build())
                    .addValues(Value.newBuilder().setNumberValue(0.1).build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT next_order_id, tax FROM district"), districtResult));

    ResultSet customerResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.FLOAT64).build())
                                    .setName("discount")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                                    .setName("last_name")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setNumberValue(0.15).build())
                    .addValues(Value.newBuilder().setStringValue("Smith").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT discount, last_name FROM customer"), customerResult));

    ResultSet customerBalanceResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.FLOAT64).build())
                                    .setName("balance")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                                    .setName("first_name")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                                    .setName("last_name")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setNumberValue(100.0).build())
                    .addValues(Value.newBuilder().setStringValue("John").build())
                    .addValues(Value.newBuilder().setStringValue("Smith").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT balance, first_name, last_name FROM customer"),
            customerBalanceResult));

    ResultSet ordersResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("order_id")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.TIMESTAMP).build())
                                    .setName("entry_date")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("carrier_id")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1000").build())
                    .addValues(Value.newBuilder().setStringValue("2026-06-02T13:43:09Z").build())
                    .addValues(Value.newBuilder().setStringValue("1").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT order_id, entry_date, carrier_id FROM orders"), ordersResult));

    ResultSet orderLineResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("order_line_id")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("item_id")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("quantity")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.FLOAT64).build())
                                    .setName("amount")
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.TIMESTAMP).build())
                                    .setName("delivery_date")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1").build())
                    .addValues(Value.newBuilder().setStringValue("1").build())
                    .addValues(Value.newBuilder().setStringValue("5").build())
                    .addValues(Value.newBuilder().setNumberValue(25.0).build())
                    .addValues(Value.newBuilder().setStringValue("2026-06-02T13:43:09Z").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of(
                "SELECT order_line_id, item_id, quantity, amount, delivery_date FROM order_line"),
            orderLineResult));

    ResultSet newOrdersResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("order_id")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1000").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT order_id FROM new_orders"), newOrdersResult));

    ResultSet nextOrderIdResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("next_order_id")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1000").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT next_order_id FROM district"), nextOrderIdResult));

    // DML statements for TPC-C
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) "
                    + "VALUES (@w, @d, @o, @c, @dt, @cnt, 1)"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) "
                    + "VALUES (@w, @d, @o, @dt)"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) "
                    + "VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 "
                    + "WHERE warehouse_id = @w AND item_id = @i"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of("UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 "
                    + "WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) "
                    + "VALUES (@w, @d, @h, @c, @dt, @amt, 'history')"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o"),
            1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of(
                "UPDATE order_line SET delivery_date = @dt WHERE warehouse_id = @w AND district_id = @d AND order_id = @o"),
            1L));

    try {
      Server server =
          ServerBuilder.forPort(0)
              .addService(mockSpanner)
              .addService(mockDatabaseAdmin)
              .build()
              .start();
      int port = server.getPort();
      parent.setHost("http://localhost:" + port);
      return server;
    } catch (Exception e) {
      throw new RuntimeException("Failed to start local mock Spanner server", e);
    }
  }
}
