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
import com.google.cloud.spanner.Partition;
import com.google.cloud.spanner.PartitionOptions;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.jdbc.CloudSpannerJdbcConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
      Connection connection, int scaleFactor, int totalItems, boolean extended) throws Exception {
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

    try {
      connection.setReadOnly(false);
      connection.setAutoCommit(false);
      CloudSpannerJdbcConnection spannerConnection =
          connection.unwrap(CloudSpannerJdbcConnection.class);
      spannerConnection.setTransactionTag("new_order");

      long nextOrderId = 1000;
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT next_order_id, tax FROM district WHERE warehouse_id = ? AND district_id = ?"
                  + " FOR UPDATE")) {
        statement.setLong(1, warehouseId);
        statement.setLong(2, districtId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            nextOrderId = resultSet.getLong(1);
          }
        }
      }

      double customerDiscount = 0.0;
      String customerLastName = "";
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT discount, last_name FROM customer WHERE warehouse_id = ? AND district_id = ?"
                  + " AND customer_id = ?")) {
        statement.setLong(1, warehouseId);
        statement.setLong(2, districtId);
        statement.setLong(3, customerId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            customerDiscount = resultSet.getDouble(1);
            customerLastName = resultSet.getString(2);
          }
        }
      }

      try (Statement batchStatement = connection.createStatement()) {
        batchStatement.execute("START BATCH DML");
        try (PreparedStatement updateDistrictStatement =
                connection.prepareStatement(
                    "UPDATE district SET next_order_id = ? WHERE warehouse_id = ? AND district_id = ?");
            PreparedStatement insertOrdersStatement =
                connection.prepareStatement(
                    "INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(), ?, 1)");
            PreparedStatement insertNewOrdersStatement =
                connection.prepareStatement(
                    "INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP())");
            PreparedStatement insertOrderLineStatement =
                connection.prepareStatement(
                    "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 25.0, 'distinfo')");
            PreparedStatement updateStockStatement =
                connection.prepareStatement(
                    "UPDATE stock SET quantity = quantity - ?, order_count = order_count + 1 WHERE warehouse_id = ? AND item_id = ?")) {
          updateDistrictStatement.setLong(1, nextOrderId + 1);
          updateDistrictStatement.setLong(2, warehouseId);
          updateDistrictStatement.setLong(3, districtId);
          updateDistrictStatement.executeUpdate();

          insertOrdersStatement.setLong(1, warehouseId);
          insertOrdersStatement.setLong(2, districtId);
          insertOrdersStatement.setLong(3, nextOrderId);
          insertOrdersStatement.setLong(4, customerId);
          insertOrdersStatement.setLong(5, numItems);
          insertOrdersStatement.executeUpdate();

          insertNewOrdersStatement.setLong(1, warehouseId);
          insertNewOrdersStatement.setLong(2, districtId);
          insertNewOrdersStatement.setLong(3, nextOrderId);
          insertNewOrdersStatement.executeUpdate();

          for (int i = 0; i < numItems; i++) {
            insertOrderLineStatement.setLong(1, warehouseId);
            insertOrderLineStatement.setLong(2, districtId);
            insertOrderLineStatement.setLong(3, nextOrderId);
            insertOrderLineStatement.setLong(4, i + 1);
            insertOrderLineStatement.setLong(5, itemIds.get(i));
            insertOrderLineStatement.setLong(6, quantities.get(i));
            insertOrderLineStatement.executeUpdate();

            updateStockStatement.setLong(1, quantities.get(i));
            updateStockStatement.setLong(2, warehouseId);
            updateStockStatement.setLong(3, itemIds.get(i));
            updateStockStatement.executeUpdate();
          }
        }
        batchStatement.execute("RUN BATCH");
      }
      connection.commit();
    } catch (Exception e) {
      try (Statement abortStatement = connection.createStatement()) {
        abortStatement.execute("ABORT BATCH");
      } catch (SQLException ignored) {
      }
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      throw e;
    }
  }

  public static void executePayment(Connection connection, int scaleFactor, boolean extended)
      throws Exception {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);
    double amount = ThreadLocalRandom.current().nextDouble(1.0, 5000.0);

    try {
      connection.setReadOnly(false);
      connection.setAutoCommit(false);
      CloudSpannerJdbcConnection spannerConnection =
          connection.unwrap(CloudSpannerJdbcConnection.class);
      spannerConnection.setTransactionTag("payment");

      try (Statement batchStatement = connection.createStatement()) {
        batchStatement.execute("START BATCH DML");
        try (PreparedStatement updateWarehouseStatement =
                connection.prepareStatement(
                    "UPDATE warehouse SET ytd = ytd + ? WHERE warehouse_id = ?");
            PreparedStatement updateDistrictStatement =
                connection.prepareStatement(
                    "UPDATE district SET ytd = ytd + ? WHERE warehouse_id = ? AND district_id = ?");
            PreparedStatement updateCustomerStatement =
                connection.prepareStatement(
                    "UPDATE customer SET balance = balance - ?, ytd_payment = ytd_payment + ?, payment_count = payment_count + 1 "
                        + "WHERE warehouse_id = ? AND district_id = ? AND customer_id = ?");
            PreparedStatement insertHistoryStatement =
                connection.prepareStatement(
                    "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(), ?, 'history')")) {
          updateWarehouseStatement.setDouble(1, amount);
          updateWarehouseStatement.setLong(2, warehouseId);
          updateWarehouseStatement.executeUpdate();

          updateDistrictStatement.setDouble(1, amount);
          updateDistrictStatement.setLong(2, warehouseId);
          updateDistrictStatement.setLong(3, districtId);
          updateDistrictStatement.executeUpdate();

          updateCustomerStatement.setDouble(1, amount);
          updateCustomerStatement.setDouble(2, amount);
          updateCustomerStatement.setLong(3, warehouseId);
          updateCustomerStatement.setLong(4, districtId);
          updateCustomerStatement.setLong(5, customerId);
          updateCustomerStatement.executeUpdate();

          insertHistoryStatement.setLong(1, warehouseId);
          insertHistoryStatement.setLong(2, districtId);
          insertHistoryStatement.setString(3, UUID.randomUUID().toString());
          insertHistoryStatement.setLong(4, customerId);
          insertHistoryStatement.setDouble(5, amount);
          insertHistoryStatement.executeUpdate();
        }
        batchStatement.execute("RUN BATCH");
      }
      connection.commit();
    } catch (Exception e) {
      try (Statement abortStatement = connection.createStatement()) {
        abortStatement.execute("ABORT BATCH");
      } catch (SQLException ignored) {
      }
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      throw e;
    }
  }

  public static void executeOrderStatus(Connection connection, int scaleFactor, boolean extended)
      throws Exception {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);

    try {
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      CloudSpannerJdbcConnection spannerConnection =
          connection.unwrap(CloudSpannerJdbcConnection.class);
      spannerConnection.setStatementTag("order_status");
      spannerConnection.setReadOnlyStaleness(
          extended
              ? TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS)
              : TimestampBound.strong());

      double customerBalance = 0.0;
      String customerFirstName = "";
      String customerLastName = "";
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = ? AND"
                  + " district_id = ? AND customer_id = ?")) {
        statement.setLong(1, warehouseId);
        statement.setLong(2, districtId);
        statement.setLong(3, customerId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            customerBalance = resultSet.getDouble(1);
            customerFirstName = resultSet.getString(2);
            customerLastName = resultSet.getString(3);
          }
        }
      }

      long orderId = -1;
      spannerConnection.setStatementTag("order_status");
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT order_id, entry_date, carrier_id FROM orders WHERE warehouse_id = ? AND"
                  + " district_id = ? AND customer_id = ? ORDER BY order_id DESC LIMIT 1")) {
        statement.setLong(1, warehouseId);
        statement.setLong(2, districtId);
        statement.setLong(3, customerId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            orderId = resultSet.getLong(1);
            if (resultSet.getTimestamp(2) != null) {
              resultSet.getTimestamp(2);
            }
            resultSet.getLong(3);
          }
        }
      }

      if (orderId != -1) {
        spannerConnection.setStatementTag("order_status");
        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT order_line_id, item_id, quantity, amount, delivery_date FROM order_line"
                    + " WHERE warehouse_id = ? AND district_id = ? AND order_id = ?")) {
          statement.setLong(1, warehouseId);
          statement.setLong(2, districtId);
          statement.setLong(3, orderId);
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              resultSet.getLong(1);
              resultSet.getLong(2);
              resultSet.getLong(3);
              resultSet.getDouble(4);
              if (resultSet.getTimestamp(5) != null) {
                resultSet.getTimestamp(5);
              }
            }
          }
        }
      }
      connection.commit();
    } catch (Exception e) {
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      throw e;
    } finally {
      try {
        CloudSpannerJdbcConnection spannerConnection =
            connection.unwrap(CloudSpannerJdbcConnection.class);
        spannerConnection.setReadOnlyStaleness(TimestampBound.strong());
      } catch (SQLException ignored) {
      }
      try {
        connection.setReadOnly(false);
      } catch (SQLException ignored) {
      }
    }
  }

  public static void executeDelivery(Connection connection, int scaleFactor, boolean extended)
      throws Exception {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long carrierId = ThreadLocalRandom.current().nextLong(1, 11);

    try {
      connection.setReadOnly(false);
      connection.setAutoCommit(false);
      CloudSpannerJdbcConnection spannerConnection =
          connection.unwrap(CloudSpannerJdbcConnection.class);
      spannerConnection.setTransactionTag("delivery");

      List<Long> matchedDistrictIds = new ArrayList<>();
      List<Long> matchedOrderIds = new ArrayList<>();
      for (long districtId = 1; districtId <= 10; districtId++) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT order_id FROM new_orders WHERE warehouse_id = ? AND district_id = ?"
                    + " ORDER BY created_timestamp ASC LIMIT 1 FOR UPDATE")) {
          statement.setLong(1, warehouseId);
          statement.setLong(2, districtId);
          try (ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
              matchedDistrictIds.add(districtId);
              matchedOrderIds.add(resultSet.getLong(1));
            }
          }
        }
      }

      if (!matchedOrderIds.isEmpty()) {
        try (Statement batchStatement = connection.createStatement()) {
          batchStatement.execute("START BATCH DML");
          try (PreparedStatement deleteNewOrdersStatement =
                  connection.prepareStatement(
                      "DELETE FROM new_orders WHERE warehouse_id = ? AND district_id = ? AND order_id = ?");
              PreparedStatement updateOrdersStatement =
                  connection.prepareStatement(
                      "UPDATE orders SET carrier_id = ? WHERE warehouse_id = ? AND district_id = ? AND order_id = ?");
              PreparedStatement updateOrderLineStatement =
                  connection.prepareStatement(
                      "UPDATE order_line SET delivery_date = CURRENT_TIMESTAMP() WHERE warehouse_id = ? AND district_id = ? AND order_id = ?")) {
            for (int i = 0; i < matchedOrderIds.size(); i++) {
              long districtId = matchedDistrictIds.get(i);
              long orderId = matchedOrderIds.get(i);

              deleteNewOrdersStatement.setLong(1, warehouseId);
              deleteNewOrdersStatement.setLong(2, districtId);
              deleteNewOrdersStatement.setLong(3, orderId);
              deleteNewOrdersStatement.executeUpdate();

              updateOrdersStatement.setLong(1, carrierId);
              updateOrdersStatement.setLong(2, warehouseId);
              updateOrdersStatement.setLong(3, districtId);
              updateOrdersStatement.setLong(4, orderId);
              updateOrdersStatement.executeUpdate();

              updateOrderLineStatement.setLong(1, warehouseId);
              updateOrderLineStatement.setLong(2, districtId);
              updateOrderLineStatement.setLong(3, orderId);
              updateOrderLineStatement.executeUpdate();
            }
          }
          batchStatement.execute("RUN BATCH");
        }
      }
      connection.commit();
    } catch (Exception e) {
      try (Statement abortStatement = connection.createStatement()) {
        abortStatement.execute("ABORT BATCH");
      } catch (SQLException ignored) {
      }
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      throw e;
    }
  }

  public static void executeStockLevel(Connection connection, int scaleFactor, boolean extended)
      throws Exception {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long threshold = ThreadLocalRandom.current().nextLong(15, 21);

    try {
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      CloudSpannerJdbcConnection spannerConnection =
          connection.unwrap(CloudSpannerJdbcConnection.class);
      spannerConnection.setStatementTag("stock_level");
      spannerConnection.setReadOnlyStaleness(TimestampBound.strong());

      long nextOrderId = -1;
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT next_order_id FROM district WHERE warehouse_id = ? AND district_id = ?")) {
        statement.setLong(1, warehouseId);
        statement.setLong(2, districtId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            nextOrderId = resultSet.getLong(1);
          }
        }
      }

      if (nextOrderId != -1) {
        spannerConnection.setStatementTag("stock_level");
        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(DISTINCT s.item_id) FROM order_line ol JOIN stock s ON s.warehouse_id"
                    + " = ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = ? AND"
                    + " ol.district_id = ? AND ol.order_id >= ? AND ol.order_id < ? AND s.quantity"
                    + " < ?")) {
          statement.setLong(1, warehouseId);
          statement.setLong(2, districtId);
          statement.setLong(3, Math.max(1, nextOrderId - 20));
          statement.setLong(4, nextOrderId);
          statement.setLong(5, threshold);
          try (ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
              resultSet.getLong(1);
            }
          }
        }
      }
      connection.commit();
    } catch (Exception e) {
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      throw e;
    } finally {
      try {
        connection.setReadOnly(false);
      } catch (SQLException ignored) {
      }
    }
  }

  public static void executeNewOrderMutations(
      Connection connection, int scaleFactor, int totalItems) throws Exception {
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

    try {
      connection.setReadOnly(false);
      connection.setAutoCommit(false);
      CloudSpannerJdbcConnection spannerConnection =
          connection.unwrap(CloudSpannerJdbcConnection.class);
      spannerConnection.setTransactionTag("new_order_mutations");

      // Read District Next Order ID via PreparedStatement within this transaction
      long nextOrderId = 1000;
      String districtSql =
          "SELECT next_order_id FROM district WHERE warehouse_id = ? AND district_id = ?";
      try (PreparedStatement districtStatement = connection.prepareStatement(districtSql)) {
        districtStatement.setLong(1, warehouseId);
        districtStatement.setLong(2, districtId);
        try (ResultSet resultSet = districtStatement.executeQuery()) {
          if (resultSet.next()) {
            nextOrderId = resultSet.getLong(1);
          }
        }
      }

      // Read Customer discount and last name via PreparedStatement within this transaction
      double customerDiscount = 0.0;
      String customerLastName = "";
      String customerSql =
          "SELECT discount, last_name FROM customer WHERE warehouse_id = ? AND district_id = ? AND"
              + " customer_id = ?";
      try (PreparedStatement customerStatement = connection.prepareStatement(customerSql)) {
        customerStatement.setLong(1, warehouseId);
        customerStatement.setLong(2, districtId);
        customerStatement.setLong(3, customerId);
        try (ResultSet resultSet = customerStatement.executeQuery()) {
          if (resultSet.next()) {
            customerDiscount = resultSet.getDouble(1);
            customerLastName = resultSet.getString(2);
          }
        }
      }

      // Read Stock quantities for all items within this transaction
      Map<Long, Long> stockQuantities = new HashMap<>();
      String placeholders = String.join(",", Collections.nCopies(numItems, "?"));
      String stockSql =
          "SELECT item_id, quantity FROM stock WHERE warehouse_id = ? AND item_id IN ("
              + placeholders
              + ")";
      try (PreparedStatement stockStatement = connection.prepareStatement(stockSql)) {
        stockStatement.setLong(1, warehouseId);
        for (int i = 0; i < numItems; i++) {
          stockStatement.setLong(i + 2, itemIds.get(i));
        }
        try (ResultSet resultSet = stockStatement.executeQuery()) {
          while (resultSet.next()) {
            stockQuantities.put(resultSet.getLong(1), resultSet.getLong(2));
          }
        }
      }

      Timestamp now = Timestamp.now();
      List<Mutation> mutations = new ArrayList<>();

      mutations.add(
          Mutation.newUpdateBuilder("district")
              .set("warehouse_id")
              .to(warehouseId)
              .set("district_id")
              .to(districtId)
              .set("next_order_id")
              .to(nextOrderId + 1)
              .build());

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

      spannerConnection.bufferedWrite(mutations);
      connection.commit();
    } catch (Exception e) {
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      throw e;
    }
  }

  public static void executePaymentMutationsDirect(Connection connection, int scaleFactor)
      throws Exception {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);
    double amount = ThreadLocalRandom.current().nextDouble(1.0, 5000.0);

    CloudSpannerJdbcConnection spannerConnection =
        connection.unwrap(CloudSpannerJdbcConnection.class);

    try {
      connection.setReadOnly(false);
      connection.setAutoCommit(false);
      spannerConnection.setTransactionTag("payment_mutations_direct");

      try (Statement batchStatement = connection.createStatement()) {
        batchStatement.execute("START BATCH DML");
        try (PreparedStatement updateWarehouseStatement =
                connection.prepareStatement(
                    "UPDATE warehouse SET ytd = ytd + ? WHERE warehouse_id = ?");
            PreparedStatement updateDistrictStatement =
                connection.prepareStatement(
                    "UPDATE district SET ytd = ytd + ? WHERE warehouse_id = ? AND district_id = ?");
            PreparedStatement updateCustomerStatement =
                connection.prepareStatement(
                    "UPDATE customer SET balance = balance - ?, ytd_payment = ytd_payment + ?, payment_count = payment_count + 1 "
                        + "WHERE warehouse_id = ? AND district_id = ? AND customer_id = ?")) {
          updateWarehouseStatement.setDouble(1, amount);
          updateWarehouseStatement.setLong(2, warehouseId);
          updateWarehouseStatement.executeUpdate();

          updateDistrictStatement.setDouble(1, amount);
          updateDistrictStatement.setLong(2, warehouseId);
          updateDistrictStatement.setLong(3, districtId);
          updateDistrictStatement.executeUpdate();

          updateCustomerStatement.setDouble(1, amount);
          updateCustomerStatement.setDouble(2, amount);
          updateCustomerStatement.setLong(3, warehouseId);
          updateCustomerStatement.setLong(4, districtId);
          updateCustomerStatement.setLong(5, customerId);
          updateCustomerStatement.executeUpdate();
        }
        batchStatement.execute("RUN BATCH");
      }
      connection.commit();
    } catch (Exception e) {
      try (Statement abortStatement = connection.createStatement()) {
        abortStatement.execute("ABORT BATCH");
      } catch (SQLException ignored) {
      }
      try {
        connection.rollback();
      } catch (SQLException ignored) {
      }
      throw e;
    }

    // Write history record using mutations directly
    connection.setAutoCommit(true);
    Mutation historyMutation =
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
            .build();
    spannerConnection.write(historyMutation);
  }

  public static void executeOrderStatusReads(Connection connection, int scaleFactor)
      throws Exception {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long customerId = ThreadLocalRandom.current().nextLong(1, 3001);

    CloudSpannerJdbcConnection spannerConnection =
        connection.unwrap(CloudSpannerJdbcConnection.class);
    DatabaseClient databaseClient = spannerConnection.getDatabaseClient();
    TimestampBound bound = TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS);

    try (ReadOnlyTransaction transaction = databaseClient.readOnlyTransaction(bound)) {
      double customerBalance = 0.0;
      String customerFirstName = "";
      String customerLastName = "";
      try (com.google.cloud.spanner.ResultSet resultSet =
          transaction.read(
              "customer",
              KeySet.singleKey(Key.of(warehouseId, districtId, customerId)),
              Arrays.asList("balance", "first_name", "last_name"),
              Options.tag("order_status_reads"))) {
        if (resultSet.next()) {
          customerBalance = resultSet.getDouble(0);
          customerFirstName = resultSet.getString(1);
          customerLastName = resultSet.getString(2);
        }
      }

      long orderId = -1;
      try (com.google.cloud.spanner.ResultSet resultSet =
          transaction.executeQuery(
              com.google.cloud.spanner.Statement.newBuilder(
                      "SELECT order_id, entry_date, carrier_id FROM orders WHERE warehouse_id = @w"
                          + " AND district_id = @d AND customer_id = @c ORDER BY order_id DESC"
                          + " LIMIT 1")
                  .bind("w")
                  .to(warehouseId)
                  .bind("d")
                  .to(districtId)
                  .bind("c")
                  .to(customerId)
                  .build(),
              Options.tag("order_status_reads"))) {
        if (resultSet.next()) {
          orderId = resultSet.getLong(0);
          if (!resultSet.isNull(1)) {
            resultSet.getTimestamp(1);
          }
          if (!resultSet.isNull(2)) {
            resultSet.getLong(2);
          }
        }
      }

      if (orderId != -1) {
        KeyRange range =
            KeyRange.closedOpen(
                Key.of(warehouseId, districtId, orderId),
                Key.of(warehouseId, districtId, orderId + 1));
        KeySet keySet = KeySet.newBuilder().addRange(range).build();

        try (com.google.cloud.spanner.ResultSet resultSet =
            transaction.read(
                "order_line",
                keySet,
                Arrays.asList("order_line_id", "item_id", "quantity", "amount", "delivery_date"),
                Options.tag("order_status_reads"))) {
          while (resultSet.next()) {
            resultSet.getLong(0);
            resultSet.getLong(1);
            resultSet.getLong(2);
            resultSet.getDouble(3);
            if (!resultSet.isNull(4)) {
              resultSet.getTimestamp(4);
            }
          }
        }
      }
    }
  }

  public static void executeStockLevelPartitioned(Connection connection, int scaleFactor)
      throws Exception {
    long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
    long districtId = ThreadLocalRandom.current().nextLong(1, 11);
    long threshold = ThreadLocalRandom.current().nextLong(15, 21);

    CloudSpannerJdbcConnection spannerConnection =
        connection.unwrap(CloudSpannerJdbcConnection.class);
    BatchClient batchClient =
        spannerConnection.getSpanner().getBatchClient(spannerConnection.getDatabaseId());

    try (BatchReadOnlyTransaction transaction =
        batchClient.batchReadOnlyTransaction(
            TimestampBound.ofExactStaleness(15, TimeUnit.SECONDS))) {
      long nextOrderId = -1;
      try (com.google.cloud.spanner.ResultSet resultSet =
          transaction.executeQuery(
              com.google.cloud.spanner.Statement.newBuilder(
                      "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id ="
                          + " @d")
                  .bind("w")
                  .to(warehouseId)
                  .bind("d")
                  .to(districtId)
                  .build(),
              Options.tag("stock_level_partitioned"))) {
        if (resultSet.next()) {
          nextOrderId = resultSet.getLong(0);
        }
      }

      if (nextOrderId != -1) {
        long minOrderId = Math.max(1, nextOrderId - 20);
        com.google.cloud.spanner.Statement partitionStatement =
            com.google.cloud.spanner.Statement.newBuilder(
                    "SELECT DISTINCT s.item_id FROM order_line ol JOIN stock s ON s.warehouse_id ="
                        + " ol.warehouse_id AND s.item_id = ol.item_id WHERE ol.warehouse_id = @w"
                        + " AND ol.district_id = @d AND ol.order_id >= @minOrderId AND ol.order_id"
                        + " < @nextOrderId AND s.quantity < @threshold")
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

        List<Partition> partitions =
            transaction.partitionQuery(
                PartitionOptions.getDefaultInstance(),
                partitionStatement,
                Options.tag("stock_level_partitioned"));

        Set<Long> uniqueItemIds = ConcurrentHashMap.newKeySet();
        partitions.parallelStream()
            .forEach(
                partition -> {
                  try (com.google.cloud.spanner.ResultSet resultSet =
                      transaction.execute(partition)) {
                    while (resultSet.next()) {
                      uniqueItemIds.add(resultSet.getLong(0));
                    }
                  }
                });
        uniqueItemIds.size();
      }
    }
  }
}
