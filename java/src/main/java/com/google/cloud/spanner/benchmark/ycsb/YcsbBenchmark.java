package com.google.cloud.spanner.benchmark.ycsb;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.KeyRange;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Options;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.StructReader;
import com.google.cloud.spanner.benchmark.AbstractBenchmark;
import com.google.cloud.spanner.benchmark.LoadType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;

/**
 * Standard YCSB benchmark workload runner for Cloud Spanner.
 *
 * <p>Workload F (Read-Modify-Write) Note: This implementation executes an ACID read-write
 * transaction via {@code client.readWriteTransaction().run(...)} with row read locking and atomic
 * 2PC commit. This provides true transactional consistency and prevents lost updates under
 * concurrent write contention on Spanner, as opposed to upstream YCSB's non-transactional
 * blind-read + blind-write pattern.
 */
public class YcsbBenchmark extends AbstractBenchmark {

  private static final long MIN_RECORD_ID = 0L;

  // Lock-free striped accumulator to prevent cache-line bouncing across worker threads
  public static final LongAdder blackhole = new LongAdder();

  private final LongAdder readOperationCount = new LongAdder();
  private final LongAdder readTotalDurationNs = new LongAdder();
  private final LongAdder updateOperationCount = new LongAdder();
  private final LongAdder updateTotalDurationNs = new LongAdder();
  private final LongAdder insertOperationCount = new LongAdder();
  private final LongAdder insertTotalDurationNs = new LongAdder();
  private final LongAdder scanOperationCount = new LongAdder();
  private final LongAdder scanTotalDurationNs = new LongAdder();
  private final LongAdder rmwOperationCount = new LongAdder();
  private final LongAdder rmwTotalDurationNs = new LongAdder();

  private final YcsbWorkload workload;
  private final KeyDistribution distribution;
  private final long recordCount;
  private final int zeroPadding;
  private final int fieldCount;
  private final int fieldLength;
  private final boolean useReadRow;
  private final boolean isMock;

  private final ZipfianGenerator zipfianGenerator;
  private final ScrambledZipfianGenerator scrambledZipfianGenerator;
  private final SkewedLatestGenerator skewedLatestGenerator;
  private final AtomicLong insertKeySequence;
  private final List<String> fieldNames;

  // Precomputed SQL queries to eliminate string allocations in the hot path
  private final String readSql;
  private final String scanSql;

  // Enriched telemetry attributes with workload dimension
  private final Attributes ycsbAttributes;

  public YcsbBenchmark(
      DatabaseClient client,
      LongHistogram latencyHistogram,
      LongCounter operationCounter,
      LongCounter errorCounter,
      LongHistogram memoryUsageHistogram,
      DoubleHistogram cpuUtilizationHistogram,
      String resourceProbeInterval,
      String tableName,
      YcsbWorkload workload,
      KeyDistribution distribution,
      long recordCount,
      int zeroPadding,
      int fieldCount,
      int fieldLength,
      boolean useReadRow,
      double tps,
      int threads,
      Duration duration,
      boolean forAlerting,
      String benchmarkName,
      LoadType loadType,
      Duration cycleDuration,
      double peakFactor,
      double burstFactor,
      double burstDuration,
      double burstFraction,
      boolean isMock) {
    super(
        client,
        latencyHistogram,
        operationCounter,
        errorCounter,
        memoryUsageHistogram,
        cpuUtilizationHistogram,
        resourceProbeInterval,
        tableName,
        MIN_RECORD_ID,
        recordCount,
        tps,
        threads,
        duration,
        forAlerting,
        benchmarkName,
        loadType,
        cycleDuration,
        peakFactor,
        burstFactor,
        burstDuration,
        burstFraction,
        isMock);
    this.workload = workload != null ? workload : YcsbWorkload.B;
    this.distribution = distribution != null ? distribution : KeyDistribution.SCRAMBLED_ZIPFIAN;
    this.recordCount = recordCount;
    this.zeroPadding = zeroPadding;
    this.fieldCount = fieldCount;
    this.fieldLength = fieldLength;
    this.useReadRow = useReadRow;
    this.isMock = isMock;

    long maxRecordId = Math.max(0, recordCount - 1);
    this.zipfianGenerator = new ZipfianGenerator(MIN_RECORD_ID, maxRecordId);
    this.scrambledZipfianGenerator = new ScrambledZipfianGenerator(MIN_RECORD_ID, maxRecordId);
    this.insertKeySequence = new AtomicLong(recordCount);
    this.skewedLatestGenerator = new SkewedLatestGenerator(insertKeySequence);

    List<String> fields = new ArrayList<>(fieldCount);
    for (int i = 0; i < fieldCount; i++) {
      fields.add("field" + i);
    }
    this.fieldNames = Collections.unmodifiableList(fields);

    String projection = String.join(", ", this.fieldNames);
    this.readSql = "SELECT " + projection + " FROM " + tableName + " WHERE id = @id";
    this.scanSql =
        "SELECT "
            + projection
            + " FROM "
            + tableName
            + " WHERE id >= @startKey ORDER BY id LIMIT @scanLength";

    this.ycsbAttributes =
        super.getAttributes().toBuilder()
            .put("workload", this.workload.name())
            .put("transaction_type", "ycsb-" + this.workload.name().toLowerCase())
            .build();
  }

  @Nonnull
  @Override
  protected Attributes getAttributes() {
    return this.ycsbAttributes;
  }

  @Override
  protected void executeOperation() throws Exception {
    YcsbWorkload.Operation operation = workload.nextOperation();
    switch (operation) {
      case READ:
        executeRead();
        break;
      case UPDATE:
        executeUpdate();
        break;
      case INSERT:
        executeInsert();
        break;
      case SCAN:
        executeScan();
        break;
      case READ_MODIFY_WRITE:
        executeReadModifyWrite();
        break;
      default:
        executeRead();
        break;
    }
  }

  private String getRandomKey() {
    if (isMock) {
      return ZipfianGenerator.buildKeyName(MIN_RECORD_ID, zeroPadding);
    }
    switch (distribution) {
      case ZIPFIAN:
        return zipfianGenerator.nextKey(zeroPadding);
      case UNIFORM:
        long id = ThreadLocalRandom.current().nextLong(MIN_RECORD_ID, recordCount);
        return ZipfianGenerator.buildKeyName(id, zeroPadding);
      case SCRAMBLED_ZIPFIAN:
      default:
        return scrambledZipfianGenerator.nextKey(zeroPadding);
    }
  }

  private void executeRead() throws Exception {
    long startTime = System.nanoTime();
    try {
      String key =
          workload == YcsbWorkload.D && !isMock
              ? skewedLatestGenerator.nextKey(zeroPadding)
              : getRandomKey();
      if (useReadRow) {
        Struct row = client.singleUse().readRow(tableName, Key.of(key), fieldNames);
        if (row == null) {
          throw new IllegalStateException("Row not found for key: " + key);
        }
        consumeStructReader(row);
      } else {
        Statement statement = Statement.newBuilder(readSql).bind("id").to(key).build();
        try (ResultSet resultSet = client.singleUse().executeQuery(statement)) {
          if (!resultSet.next()) {
            throw new IllegalStateException("Row not found for key: " + key);
          }
          consumeStructReader(resultSet);
        }
      }
    } finally {
      readTotalDurationNs.add(System.nanoTime() - startTime);
      readOperationCount.increment();
    }
  }

  private void executeUpdate() throws Exception {
    long startTime = System.nanoTime();
    try {
      String key = getRandomKey();
      int fieldIndex = ThreadLocalRandom.current().nextInt(fieldCount);
      String fieldName = "field" + fieldIndex;
      String value = YcsbUtils.generateRandomString(fieldLength);

      Mutation mutation =
          Mutation.newInsertOrUpdateBuilder(tableName)
              .set("id")
              .to(key)
              .set(fieldName)
              .to(value)
              .build();
      // Consistent with upstream YCSB CloudSpannerClient.update()
      client.writeAtLeastOnce(Collections.singletonList(mutation));
    } finally {
      updateTotalDurationNs.add(System.nanoTime() - startTime);
      updateOperationCount.increment();
    }
  }

  private void executeInsert() throws Exception {
    long startTime = System.nanoTime();
    try {
      long recordNumber = isMock ? MIN_RECORD_ID : insertKeySequence.getAndIncrement();
      String key = ZipfianGenerator.buildKeyName(recordNumber, zeroPadding);
      Mutation.WriteBuilder builder =
          Mutation.newInsertOrUpdateBuilder(tableName).set("id").to(key);
      for (int i = 0; i < fieldCount; i++) {
        builder.set("field" + i).to(YcsbUtils.generateRandomString(fieldLength));
      }
      // Consistent with upstream YCSB CloudSpannerClient.insert()
      client.writeAtLeastOnce(Collections.singletonList(builder.build()));
    } finally {
      insertTotalDurationNs.add(System.nanoTime() - startTime);
      insertOperationCount.increment();
    }
  }

  private void executeScan() throws Exception {
    long startTime = System.nanoTime();
    try {
      String startKey = getRandomKey();
      int scanLength = isMock ? 10 : ThreadLocalRandom.current().nextInt(1, 101);
      if (useReadRow) {
        KeySet keySet = KeySet.range(KeyRange.closedOpen(Key.of(startKey), Key.of()));
        try (ResultSet resultSet =
            client.singleUse().read(tableName, keySet, fieldNames, Options.limit(scanLength))) {
          consumeResultSet(resultSet);
        }
      } else {
        Statement statement =
            Statement.newBuilder(scanSql)
                .bind("startKey")
                .to(startKey)
                .bind("scanLength")
                .to(scanLength)
                .build();
        try (ResultSet resultSet = client.singleUse().executeQuery(statement)) {
          consumeResultSet(resultSet);
        }
      }
    } finally {
      scanTotalDurationNs.add(System.nanoTime() - startTime);
      scanOperationCount.increment();
    }
  }

  private void executeReadModifyWrite() throws Exception {
    long startTime = System.nanoTime();
    try {
      String key = getRandomKey();
      int fieldIndex = ThreadLocalRandom.current().nextInt(fieldCount);
      String fieldName = "field" + fieldIndex;
      String value = YcsbUtils.generateRandomString(fieldLength);

      client
          .readWriteTransaction()
          .run(
              transaction -> {
                Struct row = transaction.readRow(tableName, Key.of(key), fieldNames);
                if (row == null) {
                  throw new IllegalStateException("Row not found for key: " + key);
                }
                consumeStructReader(row);
                Mutation mutation =
                    Mutation.newInsertOrUpdateBuilder(tableName)
                        .set("id")
                        .to(key)
                        .set(fieldName)
                        .to(value)
                        .build();
                transaction.buffer(mutation);
                return null;
              });
    } finally {
      rmwTotalDurationNs.add(System.nanoTime() - startTime);
      rmwOperationCount.increment();
    }
  }

  private static void consumeStructReader(StructReader reader) {
    if (reader != null) {
      int sum = 0;
      for (int i = 0; i < reader.getColumnCount(); i++) {
        if (!reader.isNull(i)) {
          sum += reader.getString(i).hashCode();
        }
      }
      blackhole.add(sum);
    }
  }

  private static void consumeResultSet(ResultSet resultSet) {
    while (resultSet.next()) {
      consumeStructReader(resultSet);
    }
  }

  @Override
  protected void printSummary() {
    long reads = readOperationCount.sum();
    long updates = updateOperationCount.sum();
    long inserts = insertOperationCount.sum();
    long scans = scanOperationCount.sum();
    long rmws = rmwOperationCount.sum();

    if (reads > 0) {
      double avgReadMs = (readTotalDurationNs.sum() / 1_000_000.0) / reads;
      System.out.printf(
          Locale.US, "  [READ]   Count: %,d ops, Avg Latency: %.2f ms%n", reads, avgReadMs);
    }
    if (updates > 0) {
      double avgUpdateMs = (updateTotalDurationNs.sum() / 1_000_000.0) / updates;
      System.out.printf(
          Locale.US, "  [UPDATE] Count: %,d ops, Avg Latency: %.2f ms%n", updates, avgUpdateMs);
    }
    if (inserts > 0) {
      double avgInsertMs = (insertTotalDurationNs.sum() / 1_000_000.0) / inserts;
      System.out.printf(
          Locale.US, "  [INSERT] Count: %,d ops, Avg Latency: %.2f ms%n", inserts, avgInsertMs);
    }
    if (scans > 0) {
      double avgScanMs = (scanTotalDurationNs.sum() / 1_000_000.0) / scans;
      System.out.printf(
          Locale.US, "  [SCAN]   Count: %,d ops, Avg Latency: %.2f ms%n", scans, avgScanMs);
    }
    if (rmws > 0) {
      double avgRmwMs = (rmwTotalDurationNs.sum() / 1_000_000.0) / rmws;
      System.out.printf(
          Locale.US, "  [RMW]    Count: %,d ops, Avg Latency: %.2f ms%n", rmws, avgRmwMs);
    }
  }

  @Nonnull
  @Override
  protected String getBenchmarkName() {
    return "YCSB Benchmark (" + workload.name() + ")";
  }

  @Nonnull
  @Override
  protected String getBenchmarkType() {
    return "ycsb";
  }

  public YcsbWorkload getWorkload() {
    return workload;
  }

  public KeyDistribution getDistribution() {
    return distribution;
  }

  public long getRecordCount() {
    return recordCount;
  }

  public int getZeroPadding() {
    return zeroPadding;
  }
}
