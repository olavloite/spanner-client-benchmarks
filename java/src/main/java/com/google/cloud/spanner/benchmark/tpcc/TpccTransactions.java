package com.google.cloud.spanner.benchmark.tpcc;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.BatchClient;
import com.google.cloud.spanner.BatchReadOnlyTransaction;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.KeyRange;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Options;
import com.google.cloud.spanner.Options.QueryOption;
import com.google.cloud.spanner.Options.TransactionOption;
import com.google.cloud.spanner.Partition;
import com.google.cloud.spanner.PartitionOptions;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class TpccTransactions {

  public static void executeNewOrder(
      DatabaseClient client, int scaleFactor, int totalItems, boolean extended) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);
    int numItems = ThreadLocalRandom.current().nextInt(5, 16);
    List<Long> itemIds = new ArrayList<>(numItems);
    List<Long> quantities = new ArrayList<>(numItems);
    for (int i = 0; i < numItems; i++) {
      itemIds.add(ThreadLocalRandom.current().nextLong(1, totalItems + 1));
      quantities.add(ThreadLocalRandom.current().nextLong(1, 11));
    }

    TransactionOption[] txOptions = new TransactionOption[] {Options.tag("new_order")};

    TransactionRunner runner = client.readWriteTransaction(txOptions);
    runner.run(
        new TransactionRunner.TransactionCallable<Void>() {
          @Override
          public Void run(TransactionContext tx) {
            QueryOption[] queryOptions = new QueryOption[] {Options.tag("new_order")};

            // Read District Next Order ID
            Statement getDistrict =
                Statement.newBuilder(
                        "SELECT next_order_id, tax FROM district WHERE warehouse_id = @w AND district_id = @d FOR UPDATE")
                    .bind("w")
                    .to(warehouseId)
                    .bind("d")
                    .to(districtId)
                    .build();

            long nextOrderId = 1000;
            try (ResultSet rs = tx.executeQuery(getDistrict, queryOptions)) {
              if (rs.next()) {
                nextOrderId = rs.getLong(0);
              }
            }

            // Read Customer discount and last name
            double customerDiscount = 0.0;
            String customerLastName = "";
            try (ResultSet rs =
                tx.executeQuery(
                    Statement.newBuilder(
                            "SELECT discount, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .bind("c")
                        .to(customerId)
                        .build(),
                    queryOptions)) {
              if (rs.next()) {
                customerDiscount = rs.getDouble(0);
                customerLastName = rs.getString(1);
              }
            }

            // Execute all write DML statements in a single unified Batch DML request
            Timestamp now = Timestamp.now();
            List<Statement> statements = new ArrayList<>(numItems * 2 + 3);

            statements.add(
                Statement.newBuilder(
                        "UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d")
                    .bind("next")
                    .to(nextOrderId + 1)
                    .bind("w")
                    .to(warehouseId)
                    .bind("d")
                    .to(districtId)
                    .build());

            statements.add(
                Statement.newBuilder(
                        "INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) "
                            + "VALUES (@w, @d, @o, @c, @dt, @cnt, 1)")
                    .bind("w")
                    .to(warehouseId)
                    .bind("d")
                    .to(districtId)
                    .bind("o")
                    .to(nextOrderId)
                    .bind("c")
                    .to(customerId)
                    .bind("dt")
                    .to(now)
                    .bind("cnt")
                    .to((long) numItems)
                    .build());

            statements.add(
                Statement.newBuilder(
                        "INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) "
                            + "VALUES (@w, @d, @o, @dt)")
                    .bind("w")
                    .to(warehouseId)
                    .bind("d")
                    .to(districtId)
                    .bind("o")
                    .to(nextOrderId)
                    .bind("dt")
                    .to(now)
                    .build());

            for (int i = 0; i < numItems; i++) {
              statements.add(
                  Statement.newBuilder(
                          "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) "
                              + "VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')")
                      .bind("w")
                      .to(warehouseId)
                      .bind("d")
                      .to(districtId)
                      .bind("o")
                      .to(nextOrderId)
                      .bind("ol")
                      .to((long) (i + 1))
                      .bind("i")
                      .to(itemIds.get(i))
                      .bind("qty")
                      .to(quantities.get(i))
                      .bind("amt")
                      .to(25.0)
                      .build());

              statements.add(
                  Statement.newBuilder(
                          "UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 "
                              + "WHERE warehouse_id = @w AND item_id = @i")
                      .bind("qty")
                      .to(quantities.get(i))
                      .bind("w")
                      .to(warehouseId)
                      .bind("i")
                      .to(itemIds.get(i))
                      .build());
            }

            tx.batchUpdate(statements, Options.tag("new_order"));
            return null;
          }
        });
  }

  public static void executePayment(DatabaseClient client, int scaleFactor, boolean extended) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);
    double amount = ThreadLocalRandom.current().nextDouble(1.0, 5000.0);

    TransactionOption[] txOptions = new TransactionOption[] {Options.tag("payment")};

    TransactionRunner runner = client.readWriteTransaction(txOptions);
    runner.run(
        new TransactionRunner.TransactionCallable<Void>() {
          @Override
          public Void run(TransactionContext tx) {
            List<Statement> statements =
                Arrays.asList(
                    Statement.newBuilder(
                            "UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w")
                        .bind("amt")
                        .to(amount)
                        .bind("w")
                        .to(warehouseId)
                        .build(),
                    Statement.newBuilder(
                            "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d")
                        .bind("amt")
                        .to(amount)
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .build(),
                    Statement.newBuilder(
                            "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 "
                                + "WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                        .bind("amt")
                        .to(amount)
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .bind("c")
                        .to(customerId)
                        .build(),
                    Statement.newBuilder(
                            "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) "
                                + "VALUES (@w, @d, @h, @c, @dt, @amt, 'history')")
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .bind("h")
                        .to(UUID.randomUUID().toString())
                        .bind("c")
                        .to(customerId)
                        .bind("dt")
                        .to(Timestamp.now())
                        .bind("amt")
                        .to(amount)
                        .build());
            tx.batchUpdate(statements, Options.tag("payment"));
            return null;
          }
        });
  }

  public static void executeOrderStatus(DatabaseClient client, int scaleFactor, boolean extended) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);

    TimestampBound bound =
        extended ? TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS) : TimestampBound.strong();

    try (ReadOnlyTransaction tx = client.readOnlyTransaction(bound)) {
      QueryOption[] queryOptions = new QueryOption[] {Options.tag("order_status")};

      double customerBalance = 0.0;
      String customerFirstName = "";
      String customerLastName = "";
      try (ResultSet rs =
          tx.executeQuery(
              Statement.newBuilder(
                      "SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                  .bind("w")
                  .to(warehouseId)
                  .bind("d")
                  .to(districtId)
                  .bind("c")
                  .to(customerId)
                  .build(),
              queryOptions)) {
        if (rs.next()) {
          customerBalance = rs.getDouble(0);
          customerFirstName = rs.getString(1);
          customerLastName = rs.getString(2);
        }
      }

      long orderId = -1;
      try (ResultSet rs =
          tx.executeQuery(
              Statement.newBuilder(
                      "SELECT order_id, entry_date, carrier_id FROM orders "
                          + "WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c "
                          + "ORDER BY order_id DESC LIMIT 1")
                  .bind("w")
                  .to(warehouseId)
                  .bind("d")
                  .to(districtId)
                  .bind("c")
                  .to(customerId)
                  .build(),
              queryOptions)) {
        if (rs.next()) {
          orderId = rs.getLong(0);
          if (!rs.isNull(1)) rs.getTimestamp(1);
          if (!rs.isNull(2)) rs.getLong(2);
        }
      }

      if (orderId != -1) {
        try (ResultSet rs =
            tx.executeQuery(
                Statement.newBuilder(
                        "SELECT order_line_id, item_id, quantity, amount, delivery_date FROM order_line "
                            + "WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                    .bind("w")
                    .to(warehouseId)
                    .bind("d")
                    .to(districtId)
                    .bind("o")
                    .to(orderId)
                    .build(),
                queryOptions)) {
          while (rs.next()) {
            long orderLineId = rs.getLong(0);
            long itemId = rs.getLong(1);
            long quantity = rs.getLong(2);
            double amount = rs.getDouble(3);
            if (!rs.isNull(4)) rs.getTimestamp(4);
          }
        }
      }
    }
  }

  public static void executeDelivery(DatabaseClient client, int scaleFactor, boolean extended) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long carrierId = ThreadLocalRandom.current().nextLong(1, 11);

    TransactionOption[] txOptions = new TransactionOption[] {Options.tag("delivery")};

    TransactionRunner runner = client.readWriteTransaction(txOptions);
    runner.run(
        new TransactionRunner.TransactionCallable<Void>() {
          @Override
          public Void run(TransactionContext tx) {
            QueryOption[] queryOptions = new QueryOption[] {Options.tag("delivery")};

            List<Statement> batchStatements = new ArrayList<>();
            for (long districtId = 1; districtId <= 10; districtId++) {
              long orderId = -1;
              try (ResultSet rs =
                  tx.executeQuery(
                      Statement.newBuilder(
                              "SELECT order_id FROM new_orders "
                                  + "WHERE warehouse_id = @w AND district_id = @d "
                                  + "ORDER BY created_timestamp ASC LIMIT 1 FOR UPDATE")
                          .bind("w")
                          .to(warehouseId)
                          .bind("d")
                          .to(districtId)
                          .build(),
                      queryOptions)) {
                if (rs.next()) {
                  orderId = rs.getLong(0);
                }
              }

              if (orderId != -1) {
                batchStatements.add(
                    Statement.newBuilder(
                            "DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .bind("o")
                        .to(orderId)
                        .build());

                batchStatements.add(
                    Statement.newBuilder(
                            "UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                        .bind("c")
                        .to(carrierId)
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .bind("o")
                        .to(orderId)
                        .build());

                batchStatements.add(
                    Statement.newBuilder(
                            "UPDATE order_line SET delivery_date = @dt WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                        .bind("dt")
                        .to(Timestamp.now())
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .bind("o")
                        .to(orderId)
                        .build());
              }
            }
            if (!batchStatements.isEmpty()) {
              tx.batchUpdate(batchStatements, Options.tag("delivery"));
            }
            return null;
          }
        });
  }

  public static void executeStockLevel(DatabaseClient client, int scaleFactor, boolean extended) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long threshold = ThreadLocalRandom.current().nextLong(15, 21);

    try (ReadOnlyTransaction tx = client.readOnlyTransaction()) {
      QueryOption[] queryOptions = new QueryOption[] {Options.tag("stock_level")};

      long nextOrderId = -1;
      try (ResultSet rs =
          tx.executeQuery(
              Statement.newBuilder(
                      "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d")
                  .bind("w")
                  .to(warehouseId)
                  .bind("d")
                  .to(districtId)
                  .build(),
              queryOptions)) {
        if (rs.next()) {
          nextOrderId = rs.getLong(0);
        }
      }

      if (nextOrderId != -1) {
        try (ResultSet rs =
            tx.executeQuery(
                Statement.newBuilder(
                        "SELECT COUNT(DISTINCT s.item_id) FROM order_line ol "
                            + "JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id "
                            + "WHERE ol.warehouse_id = @w AND ol.district_id = @d "
                            + "AND ol.order_id >= @minOrderId AND ol.order_id < @nextOrderId "
                            + "AND s.quantity < @threshold")
                    .bind("w")
                    .to(warehouseId)
                    .bind("d")
                    .to(districtId)
                    .bind("minOrderId")
                    .to(Math.max(1, nextOrderId - 20))
                    .bind("nextOrderId")
                    .to(nextOrderId)
                    .bind("threshold")
                    .to(threshold)
                    .build(),
                queryOptions)) {
          if (rs.next()) {
            rs.getLong(0);
          }
        }
      }
    }
  }

  public static void executeNewOrderMutations(
      DatabaseClient client, int scaleFactor, int totalItems) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);
    int numItems = ThreadLocalRandom.current().nextInt(5, 16);
    List<Long> itemIds = new ArrayList<>(numItems);
    List<Long> quantities = new ArrayList<>(numItems);
    for (int i = 0; i < numItems; i++) {
      itemIds.add(ThreadLocalRandom.current().nextLong(1, totalItems + 1));
      quantities.add(ThreadLocalRandom.current().nextLong(1, 11));
    }

    TransactionRunner runner = client.readWriteTransaction(Options.tag("new_order_mutations"));
    runner.run(
        new TransactionRunner.TransactionCallable<Void>() {
          @Override
          public Void run(TransactionContext tx) {
            // Read District Next Order ID via read API (supporting options)
            long nextOrderId = 1000;
            try (ResultSet rs =
                tx.read(
                    "district",
                    KeySet.singleKey(Key.of(warehouseId, districtId)),
                    Arrays.asList("next_order_id"),
                    Options.tag("new_order_mutations"))) {
              if (rs.next()) {
                nextOrderId = rs.getLong(0);
              }
            }

            // Read Customer discount and last name via read API (supporting options)
            double customerDiscount = 0.0;
            String customerLastName = "";
            try (ResultSet rs =
                tx.read(
                    "customer",
                    KeySet.singleKey(Key.of(warehouseId, districtId, customerId)),
                    Arrays.asList("discount", "last_name"),
                    Options.tag("new_order_mutations"))) {
              if (rs.next()) {
                customerDiscount = rs.getDouble(0);
                customerLastName = rs.getString(1);
              }
            }

            // Read Stock quantities for all items in a single read query
            KeySet.Builder keySetBuilder = KeySet.newBuilder();
            for (int i = 0; i < numItems; i++) {
              keySetBuilder.addKey(Key.of(warehouseId, itemIds.get(i)));
            }
            KeySet keySet = keySetBuilder.build();

            Map<Long, Long> stockQuantities = new HashMap<>();
            try (ResultSet rs =
                tx.read(
                    "stock",
                    keySet,
                    Arrays.asList("item_id", "quantity"),
                    Options.tag("new_order_mutations"))) {
              while (rs.next()) {
                stockQuantities.put(rs.getLong(0), rs.getLong(1));
              }
            }

            Timestamp now = Timestamp.now();
            List<Mutation> mutations = new ArrayList<>();

            // 1. Update district next_order_id
            mutations.add(
                Mutation.newUpdateBuilder("district")
                    .set("warehouse_id")
                    .to(warehouseId)
                    .set("district_id")
                    .to(districtId)
                    .set("next_order_id")
                    .to(nextOrderId + 1)
                    .build());

            // 2. Insert order
            mutations.add(
                Mutation.newInsertBuilder("orders")
                    .set("warehouse_id")
                    .to(warehouseId)
                    .set("district_id")
                    .to(districtId)
                    .set("order_id")
                    .to(nextOrderId)
                    .set("customer_id")
                    .to(customerId)
                    .set("entry_date")
                    .to(now)
                    .set("item_count")
                    .to((long) numItems)
                    .set("all_local")
                    .to(1)
                    .build());

            // 3. Insert new_order
            mutations.add(
                Mutation.newInsertBuilder("new_orders")
                    .set("warehouse_id")
                    .to(warehouseId)
                    .set("district_id")
                    .to(districtId)
                    .set("order_id")
                    .to(nextOrderId)
                    .set("created_timestamp")
                    .to(now)
                    .build());

            // 4. Order lines and stock updates
            for (int i = 0; i < numItems; i++) {
              long itemId = itemIds.get(i);
              long quantity = stockQuantities.getOrDefault(itemId, 10L);
              long orderedQty = quantities.get(i);
              long newQuantity = quantity - orderedQty;

              mutations.add(
                  Mutation.newInsertBuilder("order_line")
                      .set("warehouse_id")
                      .to(warehouseId)
                      .set("district_id")
                      .to(districtId)
                      .set("order_id")
                      .to(nextOrderId)
                      .set("order_line_id")
                      .to((long) (i + 1))
                      .set("item_id")
                      .to(itemId)
                      .set("quantity")
                      .to(orderedQty)
                      .set("amount")
                      .to(25.0)
                      .set("dist_info")
                      .to("distinfo")
                      .build());

              mutations.add(
                  Mutation.newUpdateBuilder("stock")
                      .set("warehouse_id")
                      .to(warehouseId)
                      .set("item_id")
                      .to(itemId)
                      .set("quantity")
                      .to(newQuantity)
                      .build());
            }

            tx.buffer(mutations);
            return null;
          }
        });
  }

  public static void executePaymentMutationsDirect(DatabaseClient client, int scaleFactor) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);
    double amount = ThreadLocalRandom.current().nextDouble(1.0, 5000.0);

    TransactionRunner runner = client.readWriteTransaction(Options.tag("payment_mutations_direct"));
    runner.run(
        new TransactionRunner.TransactionCallable<Void>() {
          @Override
          public Void run(TransactionContext tx) {
            List<Statement> statements =
                Arrays.asList(
                    Statement.newBuilder(
                            "UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w")
                        .bind("amt")
                        .to(amount)
                        .bind("w")
                        .to(warehouseId)
                        .build(),
                    Statement.newBuilder(
                            "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d")
                        .bind("amt")
                        .to(amount)
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .build(),
                    Statement.newBuilder(
                            "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 "
                                + "WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                        .bind("amt")
                        .to(amount)
                        .bind("w")
                        .to(warehouseId)
                        .bind("d")
                        .to(districtId)
                        .bind("c")
                        .to(customerId)
                        .build());
            tx.batchUpdate(statements, Options.tag("payment_mutations_direct"));
            return null;
          }
        });

    // Write history record using mutations directly outside the transaction session
    List<Mutation> mutations = new ArrayList<>();
    mutations.add(
        Mutation.newInsertBuilder("history")
            .set("warehouse_id")
            .to(warehouseId)
            .set("district_id")
            .to(districtId)
            .set("history_id")
            .to(UUID.randomUUID().toString())
            .set("customer_id")
            .to(customerId)
            .set("date")
            .to(Timestamp.now())
            .set("amount")
            .to(amount)
            .set("data")
            .to("history")
            .build());
    client.writeWithOptions(mutations, Options.tag("payment_mutations_direct"));
  }

  public static void executeOrderStatusReads(DatabaseClient client, int scaleFactor) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);

    TimestampBound bound = TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS);

    try (ReadOnlyTransaction tx = client.readOnlyTransaction(bound)) {
      // 1. Look up the customer's balance, first_name, and last_name using read API (supporting
      // options)
      double customerBalance = 0.0;
      String customerFirstName = "";
      String customerLastName = "";
      try (ResultSet rs =
          tx.read(
              "customer",
              KeySet.singleKey(Key.of(warehouseId, districtId, customerId)),
              Arrays.asList("balance", "first_name", "last_name"),
              Options.tag("order_status_reads"))) {
        if (rs.next()) {
          customerBalance = rs.getDouble(0);
          customerFirstName = rs.getString(1);
          customerLastName = rs.getString(2);
        }
      }

      // 2. Query the latest order ID using query
      long orderId = -1;
      try (ResultSet rs =
          tx.executeQuery(
              Statement.newBuilder(
                      "SELECT order_id, entry_date, carrier_id FROM orders "
                          + "WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c "
                          + "ORDER BY order_id DESC LIMIT 1")
                  .bind("w")
                  .to(warehouseId)
                  .bind("d")
                  .to(districtId)
                  .bind("c")
                  .to(customerId)
                  .build(),
              Options.tag("order_status_reads"))) {
        if (rs.next()) {
          orderId = rs.getLong(0);
          if (!rs.isNull(1)) rs.getTimestamp(1);
          if (!rs.isNull(2)) rs.getLong(2);
        }
      }

      // 3. Look up all matching order_line records using read() prefix key range
      if (orderId != -1) {
        KeyRange range =
            KeyRange.closedOpen(
                Key.of(warehouseId, districtId, orderId),
                Key.of(warehouseId, districtId, orderId + 1));
        KeySet keySet = KeySet.newBuilder().addRange(range).build();

        try (ResultSet rs =
            tx.read(
                "order_line",
                keySet,
                Arrays.asList("order_line_id", "item_id", "quantity", "amount", "delivery_date"),
                Options.tag("order_status_reads"))) {
          while (rs.next()) {
            rs.getLong(0);
            rs.getLong(1);
            rs.getLong(2);
            rs.getDouble(3);
            if (!rs.isNull(4)) rs.getTimestamp(4);
          }
        }
      }
    }
  }

  public static void executeStockLevelPartitioned(BatchClient batchClient, int scaleFactor) {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long threshold = ThreadLocalRandom.current().nextLong(15, 21);

    // 1. Create BatchReadOnlyTransaction with Bounded Staleness
    try (BatchReadOnlyTransaction tx =
        batchClient.batchReadOnlyTransaction(
            TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS))) {

      // First read district next_order_id using query inside BatchReadOnlyTransaction (to get the
      // exact bounds)
      long nextOrderId = -1;
      try (ResultSet rs =
          tx.executeQuery(
              Statement.newBuilder(
                      "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d")
                  .bind("w")
                  .to(warehouseId)
                  .bind("d")
                  .to(districtId)
                  .build(),
              Options.tag("stock_level_partitioned"))) {
        if (rs.next()) {
          nextOrderId = rs.getLong(0);
        }
      }

      if (nextOrderId != -1) {
        long minOrderId = Math.max(1, nextOrderId - 20);
        Statement partitionStmt =
            Statement.newBuilder(
                    "SELECT DISTINCT s.item_id FROM order_line ol "
                        + "JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id "
                        + "WHERE ol.warehouse_id = @w AND ol.district_id = @d "
                        + "AND ol.order_id >= @minOrderId AND ol.order_id < @nextOrderId "
                        + "AND s.quantity < @threshold")
                .bind("w")
                .to(warehouseId)
                .bind("d")
                .to(districtId)
                .bind("minOrderId")
                .to(minOrderId)
                .bind("nextOrderId")
                .to(nextOrderId)
                .bind("threshold")
                .to(threshold)
                .build();

        // 2. Generate query partitions
        List<Partition> partitions =
            tx.partitionQuery(
                PartitionOptions.getDefaultInstance(),
                partitionStmt,
                Options.tag("stock_level_partitioned"));

        // 3. Execute partitions concurrently
        Set<Long> uniqueItemIds = ConcurrentHashMap.newKeySet();
        partitions.parallelStream()
            .forEach(
                partition -> {
                  try (ResultSet rs = tx.execute(partition)) {
                    while (rs.next()) {
                      uniqueItemIds.add(rs.getLong(0));
                    }
                  }
                });
        uniqueItemIds.size();
      }
    }
  }
}
