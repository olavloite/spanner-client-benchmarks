package com.google.cloud.spanner.benchmark;

import static org.junit.Assert.assertTrue;

import com.google.cloud.spanner.MockSpannerServiceImpl;
import com.google.cloud.spanner.MockSpannerServiceImpl.StatementResult;
import com.google.cloud.spanner.Statement;
import com.google.common.base.Stopwatch;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.spanner.v1.ResultSetMetadata;
import com.google.spanner.v1.StructType;
import com.google.spanner.v1.StructType.Field;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

public abstract class AbstractBenchmarkTest {

  protected static MockSpannerServiceImpl mockSpanner;
  protected static Server server;
  protected static int port;

  protected SimpleMetricReader metricReader;
  protected SdkMeterProvider meterProvider;

  @BeforeClass
  public static void startServer() throws Exception {
    java.util.logging.Logger.getLogger("com.google").setLevel(java.util.logging.Level.WARNING);
    java.util.logging.Logger.getLogger("io.grpc").setLevel(java.util.logging.Level.WARNING);

    mockSpanner = new MockSpannerServiceImpl();
    registerMockResults();

    server = ServerBuilder.forPort(0).addService(mockSpanner).build().start();
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

  protected void waitForRequest(Predicate<com.google.spanner.v1.ExecuteSqlRequest> predicate)
      throws InterruptedException {
    waitForRequest(predicate, null);
  }

  protected void waitForRequest(
      Predicate<com.google.spanner.v1.ExecuteSqlRequest> predicate, Thread appThread)
      throws InterruptedException {
    Stopwatch stopwatch = Stopwatch.createStarted();
    boolean received = false;
    while (stopwatch.elapsed(TimeUnit.MILLISECONDS) < 30000) {
      boolean hasRequest =
          mockSpanner.getRequestsOfType(com.google.spanner.v1.ExecuteSqlRequest.class).stream()
              .anyMatch(predicate);
      if (hasRequest) {
        received = true;
        break;
      }
      if (appThread != null && !appThread.isAlive()) {
        break;
      }
      Thread.sleep(5);
    }
    assertTrue(
        "Should have received the expected request"
            + (appThread != null && !appThread.isAlive()
                ? " (application thread terminated prematurely)"
                : ""),
        received);
  }

  private static void registerMockResults() {
    // Build a valid ResultSet with metadata to avoid "Missing type metadata" error
    com.google.spanner.v1.ResultSet resultSet =
        com.google.spanner.v1.ResultSet.newBuilder()
            .setMetadata(
                ResultSetMetadata.newBuilder()
                    .setRowType(
                        StructType.newBuilder()
                            .addFields(
                                Field.newBuilder()
                                    .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                    .setName("id")
                                    .build())
                            .build())
                    .build())
            .addRows(
                ListValue.newBuilder()
                    .addValues(Value.newBuilder().setStringValue("1").build())
                    .build())
            .build();

    // Large result set mock
    com.google.spanner.v1.ResultSet largeResultSet =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    // TPCC Queries mock
    com.google.spanner.v1.ResultSet warehouseCountResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    com.google.spanner.v1.ResultSet districtResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    com.google.spanner.v1.ResultSet customerResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    com.google.spanner.v1.ResultSet customerBalanceResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    com.google.spanner.v1.ResultSet ordersResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    com.google.spanner.v1.ResultSet orderLineResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    com.google.spanner.v1.ResultSet newOrdersResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    com.google.spanner.v1.ResultSet nextOrderIdResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    com.google.spanner.v1.ResultSet stockCountResult =
        com.google.spanner.v1.ResultSet.newBuilder()
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

    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT * FROM my_table WHERE id = @id"), resultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.query(Statement.of("SELECT id FROM my_table WHERE id = @id"), resultSet));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of("UPDATE my_table SET value = @value WHERE id = @id"), 1L));
    mockSpanner.putPartialStatementResult(
        StatementResult.update(
            Statement.of("INSERT INTO my_table (id, value) VALUES (@id, @value)"), 1L));
  }

  protected static class SimpleMetricReader
      implements io.opentelemetry.sdk.metrics.export.MetricReader {
    private io.opentelemetry.sdk.metrics.export.CollectionRegistration registration;
    private boolean isShutdown = false;

    @Override
    public void register(io.opentelemetry.sdk.metrics.export.CollectionRegistration registration) {
      this.registration = registration;
    }

    @Override
    public io.opentelemetry.sdk.metrics.data.AggregationTemporality getAggregationTemporality(
        io.opentelemetry.sdk.metrics.InstrumentType instrumentType) {
      return io.opentelemetry.sdk.metrics.data.AggregationTemporality.CUMULATIVE;
    }

    public java.util.Collection<io.opentelemetry.sdk.metrics.data.MetricData> collectAllMetrics() {
      if (registration != null) {
        return registration.collectAllMetrics();
      }
      return java.util.Collections.emptyList();
    }

    @Override
    public io.opentelemetry.sdk.common.CompletableResultCode forceFlush() {
      return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
    }

    @Override
    public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
      this.isShutdown = true;
      return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
    }
  }

  protected void assertNoErrors() {
    java.util.Collection<io.opentelemetry.sdk.metrics.data.MetricData> metricData =
        metricReader.collectAllMetrics();
    io.opentelemetry.sdk.metrics.data.MetricData errorCount =
        metricData.stream()
            .filter(md -> md.getName().equals(BenchmarkApp.ERROR_COUNT_NAME))
            .findFirst()
            .orElse(null);
    if (errorCount != null) {
      for (Object point : errorCount.getData().getPoints()) {
        if (point instanceof io.opentelemetry.sdk.metrics.data.LongPointData) {
          org.junit.Assert.assertEquals(
              "Should have 0 errors",
              0L,
              ((io.opentelemetry.sdk.metrics.data.LongPointData) point).getValue());
        }
      }
    }
  }
}
