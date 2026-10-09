package com.google.cloud.spanner.benchmark.tpcc;

import com.google.api.gax.rpc.ServerStream;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.MutationGroup;
import com.google.cloud.spanner.SpannerException;
import com.google.common.util.concurrent.Futures;
import com.google.rpc.Code;
import com.google.spanner.v1.BatchWriteResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class TpccDataGenerator {

  private static final int BATCH_SIZE = 500;
  private static final int MAX_GROUPS_PER_RPC = 40;
  private static final int NUM_DISTRICTS = 10;
  private static final int NUM_CUSTOMERS_PER_DISTRICT = 3000;

  public static void populate(DatabaseClient client, int numWarehouses, int numItems)
      throws InterruptedException {
    System.out.println(
        "Beginning parallel TPC-C data ingestion for "
            + numWarehouses
            + " warehouses and "
            + numItems
            + " items...");
    ExecutorService executor =
        Executors.newFixedThreadPool(Math.min(16, Runtime.getRuntime().availableProcessors() * 2));
    List<Future<?>> futures = new ArrayList<>();

    // 1. Load Items
    futures.add(executor.submit(() -> loadItems(client, numItems)));

    // 2. Load Warehouses
    for (int w = 1; w <= numWarehouses; w++) {
      final int warehouseId = w;
      futures.add(executor.submit(() -> loadWarehouseData(client, warehouseId, numItems)));
    }

    executor.shutdown();
    executor.awaitTermination(3, TimeUnit.HOURS);

    for (Future<?> future : futures) {
      Futures.getUnchecked(future);
    }

    System.out.println("Finished TPC-C data ingestion successfully.");
  }

  private static void flushMutations(
      DatabaseClient client,
      List<Mutation> buffer,
      List<MutationGroup> mutationGroups,
      boolean forceFlush) {
    if (!buffer.isEmpty()) {
      mutationGroups.add(MutationGroup.of(buffer));
      buffer.clear();
    }
    if (!mutationGroups.isEmpty() && (forceFlush || mutationGroups.size() >= MAX_GROUPS_PER_RPC)) {
      writeWithRetry(client, mutationGroups);
      mutationGroups.clear();
    }
  }

  private static void writeWithRetry(DatabaseClient client, List<MutationGroup> mutationGroups) {
    List<MutationGroup> pendingGroups = new ArrayList<>(mutationGroups);
    while (!pendingGroups.isEmpty()) {
      List<MutationGroup> retryGroups = new ArrayList<>();
      try {
        ServerStream<BatchWriteResponse> stream = client.batchWriteAtLeastOnce(pendingGroups);
        collectAbortedMutationGroups(stream, pendingGroups, retryGroups);
      } catch (SpannerException e) {
        if (e.getErrorCode() != ErrorCode.ABORTED
            && e.getErrorCode() != ErrorCode.RESOURCE_EXHAUSTED
            && e.getErrorCode() != ErrorCode.DEADLINE_EXCEEDED
            && e.getErrorCode() != ErrorCode.UNAVAILABLE
            && e.getErrorCode() != ErrorCode.INTERNAL) {
          throw e;
        }
        retryGroups.clear();
        retryGroups.addAll(pendingGroups);
      }

      if (retryGroups.isEmpty()) {
        break;
      }

      try {
        Thread.sleep(ThreadLocalRandom.current().nextInt(100, 1000));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(ie);
      }
      pendingGroups = retryGroups;
    }
  }

  private static void collectAbortedMutationGroups(
      ServerStream<BatchWriteResponse> stream,
      List<MutationGroup> pendingGroups,
      List<MutationGroup> retryGroups) {
    for (BatchWriteResponse response : stream) {
      if (response.getStatus().getCode() != Code.OK_VALUE) {
        int code = response.getStatus().getCode();
        if (code == Code.ABORTED_VALUE
            || code == Code.RESOURCE_EXHAUSTED_VALUE
            || code == Code.DEADLINE_EXCEEDED_VALUE
            || code == Code.UNAVAILABLE_VALUE
            || code == Code.INTERNAL_VALUE) {
          for (int index : response.getIndexesList()) {
            retryGroups.add(pendingGroups.get(index));
          }
        } else {
          throw new RuntimeException(
              "BatchWrite failed with non-retriable status: "
                  + response.getStatus().getCode()
                  + " - "
                  + response.getStatus().getMessage());
        }
      }
    }
  }

  private static void loadItems(DatabaseClient client, int numItems) {
    System.out.println("Generating item catalog...");
    List<Mutation> buffer = new ArrayList<>(BATCH_SIZE);
    List<MutationGroup> mutationGroups = new ArrayList<>(MAX_GROUPS_PER_RPC);
    for (int i = 1; i <= numItems; i++) {
      buffer.add(
          Mutation.newInsertOrUpdateBuilder("item")
              .set("item_id")
              .to((long) i)
              .set("im_id")
              .to((long) (i % 10000))
              .set("name")
              .to("Item_" + i)
              .set("price")
              .to((double) ((i % 100) + 1.0))
              .set("data")
              .to("Data_" + i)
              .build());

      if (buffer.size() >= BATCH_SIZE) {
        flushMutations(client, buffer, mutationGroups, false);
      }
    }
    flushMutations(client, buffer, mutationGroups, true);
    System.out.println("Items generated successfully.");
  }

  private static void loadWarehouseData(DatabaseClient client, int warehouseId, int numItems) {
    System.out.println("Starting generation for warehouse " + warehouseId);
    List<Mutation> buffer = new ArrayList<>(BATCH_SIZE);
    List<MutationGroup> mutationGroups = new ArrayList<>(MAX_GROUPS_PER_RPC);

    // Warehouse
    buffer.add(
        Mutation.newInsertOrUpdateBuilder("warehouse")
            .set("warehouse_id")
            .to((long) warehouseId)
            .set("name")
            .to("WH_" + warehouseId)
            .set("street_1")
            .to("Street 1")
            .set("street_2")
            .to("Street 2")
            .set("city")
            .to("City")
            .set("state")
            .to("ST")
            .set("zip")
            .to("123456789")
            .set("tax")
            .to(0.08)
            .set("ytd")
            .to(300000.0)
            .build());
    flushMutations(client, buffer, mutationGroups, true);

    // Stock
    for (int i = 1; i <= numItems; i++) {
      buffer.add(
          Mutation.newInsertOrUpdateBuilder("stock")
              .set("warehouse_id")
              .to((long) warehouseId)
              .set("item_id")
              .to((long) i)
              .set("quantity")
              .to((long) ((i % 50) + 50))
              .set("ytd")
              .to(0.0)
              .set("order_count")
              .to(0L)
              .set("remote_count")
              .to(0L)
              .set("data")
              .to("StockData_" + i)
              .build());
      if (buffer.size() >= BATCH_SIZE) {
        flushMutations(client, buffer, mutationGroups, false);
      }
    }
    flushMutations(client, buffer, mutationGroups, true);

    // Districts, Customers, Orders
    Timestamp now = Timestamp.now();
    for (int d = 1; d <= NUM_DISTRICTS; d++) {
      buffer.add(
          Mutation.newInsertOrUpdateBuilder("district")
              .set("warehouse_id")
              .to((long) warehouseId)
              .set("district_id")
              .to((long) d)
              .set("name")
              .to("Dist_" + d)
              .set("street_1")
              .to("Street 1")
              .set("street_2")
              .to("Street 2")
              .set("city")
              .to("City")
              .set("state")
              .to("ST")
              .set("zip")
              .to("123456789")
              .set("tax")
              .to(0.08)
              .set("ytd")
              .to(30000.0)
              .set("next_order_id")
              .to(3001L)
              .build());
      flushMutations(client, buffer, mutationGroups, true);

      for (int c = 1; c <= NUM_CUSTOMERS_PER_DISTRICT; c++) {
        buffer.add(
            Mutation.newInsertOrUpdateBuilder("customer")
                .set("warehouse_id")
                .to((long) warehouseId)
                .set("district_id")
                .to((long) d)
                .set("customer_id")
                .to((long) c)
                .set("first_name")
                .to("John")
                .set("middle_name")
                .to("OE")
                .set("last_name")
                .to("Customer_" + c)
                .set("street_1")
                .to("Street 1")
                .set("street_2")
                .to("Street 2")
                .set("city")
                .to("City")
                .set("state")
                .to("ST")
                .set("zip")
                .to("123456789")
                .set("phone")
                .to("123-456-7890")
                .set("since")
                .to(now)
                .set("credit")
                .to(c % 10 == 0 ? "BC" : "GC")
                .set("credit_limit")
                .to(50000.0)
                .set("discount")
                .to(0.05)
                .set("balance")
                .to(-10.0)
                .set("ytd_payment")
                .to(10.0)
                .set("payment_count")
                .to(1L)
                .set("delivery_count")
                .to(0L)
                .set("data")
                .to("CustomerData_" + c)
                .build());

        buffer.add(
            Mutation.newInsertOrUpdateBuilder("history")
                .set("warehouse_id")
                .to((long) warehouseId)
                .set("district_id")
                .to((long) d)
                .set("history_id")
                .to(UUID.randomUUID().toString())
                .set("customer_id")
                .to((long) c)
                .set("date")
                .to(now)
                .set("amount")
                .to(10.0)
                .set("data")
                .to("HistoryData")
                .build());

        // Order and OrderLine
        long orderId = c;
        buffer.add(
            Mutation.newInsertOrUpdateBuilder("orders")
                .set("warehouse_id")
                .to((long) warehouseId)
                .set("district_id")
                .to((long) d)
                .set("order_id")
                .to(orderId)
                .set("customer_id")
                .to((long) c)
                .set("entry_date")
                .to(now)
                .set("carrier_id")
                .to((orderId < 2101) ? (long) ((orderId % 10) + 1) : null)
                .set("item_count")
                .to(5L)
                .set("all_local")
                .to(1L)
                .build());

        if (orderId >= 2101) {
          buffer.add(
              Mutation.newInsertOrUpdateBuilder("new_orders")
                  .set("warehouse_id")
                  .to((long) warehouseId)
                  .set("district_id")
                  .to((long) d)
                  .set("order_id")
                  .to(orderId)
                  .set("created_timestamp")
                  .to(now)
                  .build());
        }

        for (int ol = 1; ol <= 5; ol++) {
          buffer.add(
              Mutation.newInsertOrUpdateBuilder("order_line")
                  .set("warehouse_id")
                  .to((long) warehouseId)
                  .set("district_id")
                  .to((long) d)
                  .set("order_id")
                  .to(orderId)
                  .set("order_line_id")
                  .to((long) ol)
                  .set("item_id")
                  .to((long) ((c + ol) % 1000 + 1))
                  .set("delivery_date")
                  .to(orderId < 2101 ? now : null)
                  .set("quantity")
                  .to(5L)
                  .set("amount")
                  .to(orderId < 2101 ? 0.0 : 25.0)
                  .set("dist_info")
                  .to("DistInfo_" + ol)
                  .build());
        }

        if (buffer.size() >= BATCH_SIZE) {
          flushMutations(client, buffer, mutationGroups, false);
        }
      }
      flushMutations(client, buffer, mutationGroups, true);
    }
    System.out.println("Finished generation for warehouse " + warehouseId);
  }
}
