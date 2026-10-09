package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertTrue;

import com.google.cloud.spanner.MockSpannerServiceImpl;
import com.google.cloud.spanner.MockSpannerServiceImpl.StatementResult;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.admin.database.v1.MockDatabaseAdminImpl;
import com.google.common.base.Stopwatch;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.spanner.v1.ExecuteSqlRequest;
import com.google.spanner.v1.ResultSet;
import com.google.spanner.v1.ResultSetMetadata;
import com.google.spanner.v1.StructType;
import com.google.spanner.v1.StructType.Field;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;

public abstract class AbstractBenchmarkTest {

  protected static MockSpannerServiceImpl mockSpanner;
  protected static MockDatabaseAdminImpl mockDatabaseAdmin;
  protected static Server server;
  protected static int port;

  protected SimpleMetricReader metricReader;
  protected SdkMeterProvider meterProvider;

  @BeforeClass
  public static void startServer() throws Exception {
    Logger.getLogger("com.google").setLevel(Level.WARNING);
    Logger.getLogger("io.grpc").setLevel(Level.WARNING);

    mockSpanner = new MockSpannerServiceImpl();
    mockDatabaseAdmin = new MockDatabaseAdminImpl();
    registerMockResults();

    server =
        ServerBuilder.forPort(0)
            .addService(mockSpanner)
            .addService(mockDatabaseAdmin)
            .build()
            .start();
    port = server.getPort();
  }

  @AfterClass
  public static void stopServer() {
    if (server != null) {
      server.shutdown();
    }
  }

  @Before
  public void setupMetrics() {
    metricReader = new SimpleMetricReader();
    meterProvider = SdkMeterProvider.builder().registerMetricReader(metricReader).build();
    OpenTelemetry openTelemetry =
        OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
    BenchmarkApp.setTestingOpenTelemetry(openTelemetry);
  }

  @After
  public void teardownMetrics() {
    BenchmarkApp.setTestingOpenTelemetry(null);
    if (meterProvider != null) {
      meterProvider.shutdown();
    }
    mockSpanner.clearRequests();
  }

  protected void waitForRequest(Predicate<ExecuteSqlRequest> predicate)
      throws InterruptedException {
    waitForRequest(predicate, null);
  }

  protected void waitForRequest(Predicate<ExecuteSqlRequest> predicate, Thread applicationThread)
      throws InterruptedException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    boolean received = false;
    while (stopwatch.elapsed(TimeUnit.MILLISECONDS) < 30000) {
      boolean hasRequest =
          mockSpanner.getRequestsOfType(ExecuteSqlRequest.class).stream().anyMatch(predicate);
      if (hasRequest) {
        received = true;
        break;
      }
      if (applicationThread != null && !applicationThread.isAlive()) {
        break;
      }
      Thread.sleep(5);
    }
    assertTrue(
        "Should have received the expected request"
            + (applicationThread != null && !applicationThread.isAlive()
                ? " (application thread terminated prematurely)"
                : ""),
        received);
  }

  private static void registerMockResults() {
    ResultSetMetadata metadata =
        ResultSetMetadata.newBuilder()
            .setRowType(
                StructType.newBuilder()
                    .addFields(
                        Field.newBuilder()
                            .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                            .setName("id")
                            .build())
                    .addFields(
                        Field.newBuilder()
                            .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                            .setName("value")
                            .build())
                    .build())
            .build();

    ResultSet resultSet =
        ResultSet.newBuilder()
            .setMetadata(metadata)
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1").build())
                    .addValues(Value.newBuilder().setStringValue("test-value").build())
                    .build())
            .build();

    ResultSet largeResultSet =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_bool")
                                    .setType(Type.newBuilder().setCode(TypeCode.BOOL).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_bytes")
                                    .setType(Type.newBuilder().setCode(TypeCode.BYTES).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_date")
                                    .setType(Type.newBuilder().setCode(TypeCode.DATE).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_float32")
                                    .setType(Type.newBuilder().setCode(TypeCode.FLOAT32).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_float64")
                                    .setType(Type.newBuilder().setCode(TypeCode.FLOAT64).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_interval")
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_json")
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_int64")
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_numeric")
                                    .setType(Type.newBuilder().setCode(TypeCode.NUMERIC).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_string")
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_timestamp")
                                    .setType(Type.newBuilder().setCode(TypeCode.TIMESTAMP).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_uuid")
                                    .setType(Type.newBuilder().setCode(TypeCode.STRING).build())
                                    .build())
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

    ResultSet narrowResultSet =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_int64_1")
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .build())
                            .addFields(
                                Field.newBuilder()
                                    .setName("random_int64_2")
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("100").build())
                    .addValues(Value.newBuilder().setStringValue("200").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT\n  FARM_FINGERPRINT(GENERATE_UUID()) AS random_int64_1"),
            narrowResultSet));

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

    ResultSet stockCountResult =
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
                    .addValues(Value.newBuilder().setStringValue("10").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(
            Statement.of("SELECT COUNT(DISTINCT s.item_id) FROM order_line ol"), stockCountResult));

    ResultSet stockResult =
        ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
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
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1").build())
                    .addValues(Value.newBuilder().setStringValue("50").build())
                    .build())
            .build();
    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT item_id, quantity FROM stock"), stockResult));

    // Point Select / Select and Update query results for both named and positional parameters
    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT * FROM my_table WHERE id = @id"), resultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT * FROM my_table WHERE id = @p1"), resultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT id FROM my_table WHERE id = @id"), resultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT id FROM my_table WHERE id = @p1"), resultSet));

    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of("UPDATE my_table SET value = @value WHERE id = @id"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("UPDATE my_table SET value = @p1 WHERE id = @p2"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of("INSERT INTO my_table (id, value) VALUES (@id, @value)"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of("INSERT INTO my_table (id, value) VALUES (@p1, @p2)"), 1L));

    // TPCC updates / inserts
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("UPDATE district SET"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("INSERT INTO orders"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("INSERT INTO new_orders"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("INSERT INTO order_line"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("UPDATE stock SET"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("UPDATE warehouse SET"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("UPDATE customer SET"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("INSERT INTO history"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("DELETE FROM new_orders"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("UPDATE orders SET"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(Statement.of("UPDATE order_line SET"), 1L));
  }

  protected static class SimpleMetricReader implements MetricReader {
    private CollectionRegistration registration;
    private boolean isShutdown = false;

    @Override
    public void register(CollectionRegistration registration) {
      this.registration = registration;
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return AggregationTemporality.CUMULATIVE;
    }

    public Collection<MetricData> collectAllMetrics() {
      if (registration != null) {
        return registration.collectAllMetrics();
      }
      return Collections.emptyList();
    }

    @Override
    public CompletableResultCode forceFlush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      this.isShutdown = true;
      return CompletableResultCode.ofSuccess();
    }
  }

  protected void assertNoErrors() {
    Collection<MetricData> metricData = metricReader.collectAllMetrics();
    MetricData errorCount =
        metricData.stream()
            .filter(metric -> metric.getName().equals(BenchmarkApp.ERROR_COUNT_NAME))
            .findFirst()
            .orElse(null);
    if (errorCount != null) {
      for (Object point : errorCount.getData().getPoints()) {
        if (point instanceof LongPointData) {
          Assert.assertEquals("Should have 0 errors", 0L, ((LongPointData) point).getValue());
        }
      }
    }
  }
}
