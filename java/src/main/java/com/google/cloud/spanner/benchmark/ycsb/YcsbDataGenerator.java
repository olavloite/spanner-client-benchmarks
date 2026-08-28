package com.google.cloud.spanner.benchmark.ycsb;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Mutation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class YcsbDataGenerator {

  public static void populate(
      DatabaseClient client,
      String tableName,
      long recordCount,
      int fieldCount,
      int fieldLength,
      int zeroPadding,
      int threads,
      int batchSize)
      throws InterruptedException, ExecutionException {
    System.out.println(
        "Populating YCSB table "
            + tableName
            + " with "
            + recordCount
            + " records using "
            + threads
            + " threads and batch size "
            + batchSize
            + "...");

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    AtomicLong progress = new AtomicLong(0);
    long recordsPerThread = (recordCount + threads - 1) / threads;
    List<Future<?>> futures = new ArrayList<>(threads);

    long startTime = System.currentTimeMillis();

    for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
      final long startId = threadIndex * recordsPerThread;
      final long endId = Math.min(recordCount - 1, (threadIndex + 1) * recordsPerThread - 1);

      if (startId >= recordCount) {
        break;
      }

      futures.add(
          executor.submit(
              () -> {
                List<Mutation> batch = new ArrayList<>(batchSize);
                for (long id = startId; id <= endId; id++) {
                  String key = ZipfianGenerator.buildKeyName(id, zeroPadding);
                  Mutation.WriteBuilder builder =
                      Mutation.newInsertOrUpdateBuilder(tableName).set("id").to(key);
                  for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                    builder
                        .set("field" + fieldIndex)
                        .to(YcsbUtils.generateRandomString(fieldLength));
                  }
                  batch.add(builder.build());

                  if (batch.size() >= batchSize) {
                    client.writeAtLeastOnce(batch);
                    batch.clear();
                    long current = progress.addAndGet(batchSize);
                    logProgress(current, recordCount, startTime);
                  }
                }
                if (!batch.isEmpty()) {
                  client.writeAtLeastOnce(batch);
                  long current = progress.addAndGet(batch.size());
                  logProgress(current, recordCount, startTime);
                }
                return null;
              }));
    }

    // Await all worker tasks and re-throw any caught exceptions to avoid silent failures
    for (Future<?> future : futures) {
      future.get();
    }

    executor.shutdown();
    if (!executor.awaitTermination(24, TimeUnit.HOURS)) {
      System.err.println("YCSB data population did not finish in time.");
      executor.shutdownNow();
    } else {
      long durationSeconds = Math.max(1, (System.currentTimeMillis() - startTime) / 1000);
      System.out.println(
          "Successfully populated "
              + recordCount
              + " records in "
              + durationSeconds
              + "s ("
              + (recordCount / durationSeconds)
              + " records/sec).");
    }
  }

  private static final AtomicLong lastLogTime = new AtomicLong(0);

  private static void logProgress(long current, long total, long startTime) {
    long now = System.currentTimeMillis();
    long last = lastLogTime.get();
    if (now - last > 5000 || current >= total) {
      if (lastLogTime.compareAndSet(last, now)) {
        double percentage = (double) current * 100.0 / total;
        long elapsedSeconds = Math.max(1, (now - startTime) / 1000);
        long recordsPerSecond = current / elapsedSeconds;
        System.out.printf(
            "Progress: %d / %d records (%.1f%%) - %d records/s%n",
            current, total, percentage, recordsPerSecond);
      }
    }
  }
}
