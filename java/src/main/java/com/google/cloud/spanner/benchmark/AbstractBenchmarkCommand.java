package com.google.cloud.spanner.benchmark;

import static com.google.cloud.spanner.benchmark.BenchmarkApp.LATENCY_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.METER_NAME;
import static com.google.cloud.spanner.benchmark.BenchmarkApp.initializeOpenTelemetry;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.MockSpannerServiceImpl;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.admin.database.v1.MockDatabaseAdminImpl;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.spanner.v1.ResultSet;
import com.google.spanner.v1.ResultSetMetadata;
import com.google.spanner.v1.StructType;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import java.time.Duration;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

public abstract class AbstractBenchmarkCommand implements Runnable {
  @ParentCommand protected BenchmarkApp parent;

  @Option(
      names = {"-t", "--table"},
      description = "Table name",
      required = true)
  protected String tableName;

  @Option(
      names = {"--num-rows"},
      description = "Number of rows to generate/select")
  protected long numRows = 1000000;

  @Option(
      names = {"--tps"},
      description = "Target transactions per second")
  protected double tps = 10.0;

  @Option(
      names = {"--threads"},
      description = "Number of threads in the pool",
      defaultValue = "10")
  protected int threads;

  @Option(
      names = {"--load-type"},
      description = "Load type (STEADY, SPIKY, GRADUAL, CLOSED_LOOP)",
      defaultValue = "STEADY",
      converter = LoadTypeConverter.class)
  protected LoadType loadType = LoadType.STEADY;

  @Option(
      names = {"--cycle-duration"},
      description = "Duration of a full cycle for gradual load")
  protected String cycleDuration;

  @Option(
      names = {"--peak-factor"},
      description = "Ratio of peak rate to average rate for gradual load")
  protected Double peakFactor;

  @Option(
      names = {"--burst-factor"},
      description = "Ratio of burst rate to average rate")
  protected Double burstFactor;

  @Option(
      names = {"--burst-duration"},
      description = "Average duration of a burst in seconds")
  protected Double burstDuration;

  @Option(
      names = {"--burst-fraction"},
      description = "Fraction of total time spent in the burst state")
  protected Double burstFraction;

  protected String getMetricName() {
    return LATENCY_NAME;
  }

  @Override
  public void run() {
    // Validation
    if (parent.isMock() && !(this instanceof PointSelectCommand)) {
      throw new IllegalArgumentException("mock is only supported for point-select benchmark");
    }

    if (loadType == LoadType.STEADY) {
      if (cycleDuration != null
          || peakFactor != null
          || burstFactor != null
          || burstDuration != null
          || burstFraction != null) {
        throw new IllegalArgumentException(
            "Cannot specify burst or gradual load options when load-type is steady");
      }
    } else if (loadType == LoadType.SPIKY) {
      if (cycleDuration != null || peakFactor != null) {
        throw new IllegalArgumentException(
            "Cannot specify gradual load options when load-type is spiky");
      }
      // Set defaults if not specified
      if (burstFactor == null) burstFactor = 1.0;
      if (burstDuration == null) burstDuration = 1.0;
      if (burstFraction == null) burstFraction = 0.1;
    } else if (loadType == LoadType.GRADUAL) {
      if (burstFactor != null || burstDuration != null || burstFraction != null) {
        throw new IllegalArgumentException(
            "Cannot specify burst load options when load-type is gradual");
      }
      // Set defaults if not specified
      if (cycleDuration == null) cycleDuration = "1h";
      if (peakFactor == null) peakFactor = 2.0;
    }

    // Set defaults for anything still null to avoid NPE during unboxing
    if (burstFactor == null) burstFactor = 1.0;
    if (burstDuration == null) burstDuration = 1.0;
    if (burstFraction == null) burstFraction = 0.1;
    if (cycleDuration == null) cycleDuration = "1h";
    if (peakFactor == null) peakFactor = 2.0;

    Server server = null;
    if (parent.isMock()) {
      server = startMockSpannerServer();
    }

    try {
      // Initialize OpenTelemetry
      OpenTelemetry openTelemetry =
          initializeOpenTelemetry(
              parent.getProjectId(), parent.getHost(), parent.getBenchmarkName(), parent.isMock());
      Meter meter = openTelemetry.getMeter(METER_NAME);
      BenchmarkMetrics metrics = BenchmarkApp.createBenchmarkMetrics(meter, getMetricName());

      // Initialize Spanner
      SpannerOptions.Builder spannerOptionsBuilder =
          SpannerOptions.newBuilder().setProjectId(parent.getProjectId());
      if (parent.getHost() != null) {
        spannerOptionsBuilder.setHost(parent.getHost());
        spannerOptionsBuilder.setChannelConfigurator(builder -> builder.usePlaintext());
        spannerOptionsBuilder.setCredentials(NoCredentials.getInstance());
      }
      String numChannelsStr = System.getenv("SPANNER_NUM_CHANNELS");
      if (numChannelsStr != null && !numChannelsStr.isEmpty()) {
        try {
          int numChannels = Integer.parseInt(numChannelsStr);
          spannerOptionsBuilder.setNumChannels(numChannels);
          System.out.println("Configured Spanner Java client with " + numChannels + " channels.");
        } catch (NumberFormatException e) {
          System.err.println("Invalid SPANNER_NUM_CHANNELS value: " + numChannelsStr);
        }
      }
      SpannerOptions spannerOptions = spannerOptionsBuilder.build();
      try (Spanner spanner = spannerOptions.getService()) {
        DatabaseClient client =
            spanner.getDatabaseClient(
                DatabaseId.of(
                    parent.getProjectId(), parent.getInstanceId(), parent.getDatabaseId()));

        Duration duration = AbstractBenchmark.parseDuration(parent.getDuration());
        boolean forAlerting = parent.isForAlerting();
        String benchmarkName = parent.getBenchmarkName();
        AbstractBenchmark benchmark =
            createBenchmark(
                client,
                metrics.latencyHistogram,
                metrics.operationCounter,
                metrics.errorCounter,
                metrics.memoryUsageHistogram,
                metrics.cpuUtilizationHistogram,
                parent.getResourceProbeInterval(),
                duration,
                forAlerting,
                benchmarkName,
                parent.isMock());
        benchmark.run();
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (server != null) {
        server.shutdown();
      }
    }
  }

  protected abstract AbstractBenchmark createBenchmark(
      DatabaseClient client,
      LongHistogram latencyHistogram,
      LongCounter operationCounter,
      LongCounter errorCounter,
      LongHistogram memoryUsageHistogram,
      DoubleHistogram cpuUtilizationHistogram,
      String resourceProbeInterval,
      Duration duration,
      boolean forAlerting,
      String benchmarkName,
      boolean isMock);

  private Server startMockSpannerServer() {
    MockSpannerServiceImpl mockSpanner = new MockSpannerServiceImpl();
    MockDatabaseAdminImpl mockDatabaseAdmin = new MockDatabaseAdminImpl();

    ResultSetMetadata metadata =
        ResultSetMetadata.newBuilder()
            .setRowType(
                StructType.newBuilder()
                    .addFields(
                        StructType.Field.newBuilder()
                            .setName("id")
                            .setType(Type.newBuilder().setCode(TypeCode.INT64)))
                    .addFields(
                        StructType.Field.newBuilder()
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
    mockSpanner.putStatementResult(
        MockSpannerServiceImpl.StatementResult.query(
            Statement.newBuilder("SELECT * FROM " + tableName + " WHERE id = @id")
                .bind("id")
                .to(1L)
                .build(),
            resultSet));
    mockSpanner.putStatementResult(
        MockSpannerServiceImpl.StatementResult.query(
            Statement.newBuilder("SELECT id FROM " + tableName + " WHERE id = @id")
                .bind("id")
                .to(1L)
                .build(),
            resultSet));

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

  public static class LoadTypeConverter implements picocli.CommandLine.ITypeConverter<LoadType> {
    @Override
    public LoadType convert(String value) throws Exception {
      return LoadType.valueOf(value.toUpperCase().replace('-', '_'));
    }
  }
}
