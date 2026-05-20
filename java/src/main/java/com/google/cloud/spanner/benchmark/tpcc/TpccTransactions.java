package com.google.cloud.spanner.benchmark.tpcc;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadOnlyTransaction;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class TpccTransactions {

    public static void executeNewOrder(DatabaseClient client, int scaleFactor, int totalItems) {
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

        TransactionRunner runner = client.readWriteTransaction();
        runner.run(new TransactionRunner.TransactionCallable<Void>() {
            @Override
            public Void run(TransactionContext tx) {
                // Read District Next Order ID
                Statement getDistrict = Statement.newBuilder(
                        "SELECT next_order_id, tax FROM district WHERE warehouse_id = @w AND district_id = @d")
                        .bind("w").to(warehouseId)
                        .bind("d").to(districtId)
                        .build();
                
                long nextOrderId = 1000;
                try (ResultSet rs = tx.executeQuery(getDistrict)) {
                    if (rs.next()) {
                        nextOrderId = rs.getLong(0);
                    }
                }

                // Read Customer discount and last name (explicitly decoding values to simulate client deserialization overhead)
                double customerDiscount = 0.0;
                String customerLastName = "";
                try (ResultSet rs = tx.executeQuery(Statement.newBuilder(
                        "SELECT discount, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                        .bind("w").to(warehouseId)
                        .bind("d").to(districtId)
                        .bind("c").to(customerId)
                        .build())) {
                    if (rs.next()) {
                        customerDiscount = rs.getDouble(0);
                        customerLastName = rs.getString(1);
                    }
                }

                // Execute all write DML statements in a single unified Batch DML request
                Timestamp now = Timestamp.now();
                List<Statement> statements = new ArrayList<>(numItems * 2 + 3);

                statements.add(Statement.newBuilder(
                        "UPDATE district SET next_order_id = @next WHERE warehouse_id = @w AND district_id = @d")
                        .bind("next").to(nextOrderId + 1)
                        .bind("w").to(warehouseId)
                        .bind("d").to(districtId)
                        .build());

                statements.add(Statement.newBuilder(
                        "INSERT INTO orders (warehouse_id, district_id, order_id, customer_id, entry_date, item_count, all_local) " +
                        "VALUES (@w, @d, @o, @c, @dt, @cnt, 1)")
                        .bind("w").to(warehouseId)
                        .bind("d").to(districtId)
                        .bind("o").to(nextOrderId)
                        .bind("c").to(customerId)
                        .bind("dt").to(now)
                        .bind("cnt").to((long) numItems)
                        .build());

                statements.add(Statement.newBuilder(
                        "INSERT INTO new_orders (warehouse_id, district_id, order_id, created_timestamp) " +
                        "VALUES (@w, @d, @o, @dt)")
                        .bind("w").to(warehouseId)
                        .bind("d").to(districtId)
                        .bind("o").to(nextOrderId)
                        .bind("dt").to(now)
                        .build());

                for (int i = 0; i < numItems; i++) {
                    statements.add(Statement.newBuilder(
                            "INSERT INTO order_line (warehouse_id, district_id, order_id, order_line_id, item_id, quantity, amount, dist_info) " +
                            "VALUES (@w, @d, @o, @ol, @i, @qty, @amt, 'distinfo')")
                            .bind("w").to(warehouseId)
                            .bind("d").to(districtId)
                            .bind("o").to(nextOrderId)
                            .bind("ol").to((long) (i + 1))
                            .bind("i").to(itemIds.get(i))
                            .bind("qty").to(quantities.get(i))
                            .bind("amt").to(25.0)
                            .build());

                    statements.add(Statement.newBuilder(
                            "UPDATE stock SET quantity = quantity - @qty, order_count = order_count + 1 " +
                            "WHERE warehouse_id = @w AND item_id = @i")
                            .bind("qty").to(quantities.get(i))
                            .bind("w").to(warehouseId)
                            .bind("i").to(itemIds.get(i))
                            .build());
                }
                tx.batchUpdate(statements);
                return null;
            }
        });
    }

    public static void executePayment(DatabaseClient client, int scaleFactor) {
        long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
        long districtId = ThreadLocalRandom.current().nextLong(1, 11);
        long customerId = ThreadLocalRandom.current().nextLong(1, 3001);
        double amount = ThreadLocalRandom.current().nextDouble(1.0, 5000.0);

        TransactionRunner runner = client.readWriteTransaction();
        runner.run(new TransactionRunner.TransactionCallable<Void>() {
            @Override
            public Void run(TransactionContext tx) {
                List<Statement> statements = java.util.Arrays.asList(
                        Statement.newBuilder(
                                "UPDATE warehouse SET ytd = ytd + @amt WHERE warehouse_id = @w")
                                .bind("amt").to(amount)
                                .bind("w").to(warehouseId)
                                .build(),

                        Statement.newBuilder(
                                "UPDATE district SET ytd = ytd + @amt WHERE warehouse_id = @w AND district_id = @d")
                                .bind("amt").to(amount)
                                .bind("w").to(warehouseId)
                                .bind("d").to(districtId)
                                .build(),

                        Statement.newBuilder(
                                "UPDATE customer SET balance = balance - @amt, ytd_payment = ytd_payment + @amt, payment_count = payment_count + 1 " +
                                "WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                                .bind("amt").to(amount)
                                .bind("w").to(warehouseId)
                                .bind("d").to(districtId)
                                .bind("c").to(customerId)
                                .build(),

                        Statement.newBuilder(
                                "INSERT INTO history (warehouse_id, district_id, history_id, customer_id, date, amount, data) " +
                                "VALUES (@w, @d, @h, @c, @dt, @amt, 'history')")
                                .bind("w").to(warehouseId)
                                .bind("d").to(districtId)
                                .bind("h").to(UUID.randomUUID().toString())
                                .bind("c").to(customerId)
                                .bind("dt").to(Timestamp.now())
                                .bind("amt").to(amount)
                                .build()
                );
                tx.batchUpdate(statements);
                return null;
            }
        });
    }

    public static void executeOrderStatus(DatabaseClient client, int scaleFactor) {
        long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
        long districtId = ThreadLocalRandom.current().nextLong(1, 11);
        long customerId = ThreadLocalRandom.current().nextLong(1, 3001);

        // Explicit Multi-Use ReadOnlyTransaction to guarantee snapshot consistency across queries
        try (ReadOnlyTransaction tx = client.readOnlyTransaction()) {
            double customerBalance = 0.0;
            String customerFirstName = "";
            String customerLastName = "";
            try (ResultSet rs = tx.executeQuery(Statement.newBuilder(
                    "SELECT balance, first_name, last_name FROM customer WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c")
                    .bind("w").to(warehouseId)
                    .bind("d").to(districtId)
                    .bind("c").to(customerId)
                    .build())) {
                if (rs.next()) {
                    customerBalance = rs.getDouble(0);
                    customerFirstName = rs.getString(1);
                    customerLastName = rs.getString(2);
                }
            }

            long orderId = -1;
            try (ResultSet rs = tx.executeQuery(Statement.newBuilder(
                    "SELECT order_id, entry_date, carrier_id FROM orders " +
                    "WHERE warehouse_id = @w AND district_id = @d AND customer_id = @c " +
                    "ORDER BY order_id DESC LIMIT 1")
                    .bind("w").to(warehouseId)
                    .bind("d").to(districtId)
                    .bind("c").to(customerId)
                    .build())) {
                if (rs.next()) {
                    orderId = rs.getLong(0);
                    if (!rs.isNull(1)) rs.getTimestamp(1);
                    if (!rs.isNull(2)) rs.getLong(2);
                }
            }

            if (orderId != -1) {
                try (ResultSet rs = tx.executeQuery(Statement.newBuilder(
                        "SELECT order_line_id, item_id, quantity, amount, delivery_date FROM order_line " +
                        "WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                        .bind("w").to(warehouseId)
                        .bind("d").to(districtId)
                        .bind("o").to(orderId)
                        .build())) {
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

    public static void executeDelivery(DatabaseClient client, int scaleFactor) {
        long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
        long carrierId = ThreadLocalRandom.current().nextLong(1, 11);

        TransactionRunner runner = client.readWriteTransaction();
        runner.run(new TransactionRunner.TransactionCallable<Void>() {
            @Override
            public Void run(TransactionContext tx) {
                List<Statement> batchStatements = new ArrayList<>();
                for (long districtId = 1; districtId <= 10; districtId++) {
                    long orderId = -1;
                    try (ResultSet rs = tx.executeQuery(Statement.newBuilder(
                            "SELECT order_id FROM new_orders " +
                            "WHERE warehouse_id = @w AND district_id = @d " +
                            "ORDER BY created_timestamp ASC LIMIT 1")
                            .bind("w").to(warehouseId)
                            .bind("d").to(districtId)
                            .build())) {
                        if (rs.next()) {
                            orderId = rs.getLong(0);
                        }
                    }

                    if (orderId != -1) {
                        batchStatements.add(Statement.newBuilder(
                                "DELETE FROM new_orders WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                                .bind("w").to(warehouseId)
                                .bind("d").to(districtId)
                                .bind("o").to(orderId)
                                .build());

                        batchStatements.add(Statement.newBuilder(
                                "UPDATE orders SET carrier_id = @c WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                                .bind("c").to(carrierId)
                                .bind("w").to(warehouseId)
                                .bind("d").to(districtId)
                                .bind("o").to(orderId)
                                .build());

                        batchStatements.add(Statement.newBuilder(
                                "UPDATE order_line SET delivery_date = @dt WHERE warehouse_id = @w AND district_id = @d AND order_id = @o")
                                .bind("dt").to(Timestamp.now())
                                .bind("w").to(warehouseId)
                                .bind("d").to(districtId)
                                .bind("o").to(orderId)
                                .build());
                    }
                }
                if (!batchStatements.isEmpty()) {
                    tx.batchUpdate(batchStatements);
                }
                return null;
            }
        });
    }

    public static void executeStockLevel(DatabaseClient client, int scaleFactor) {
        long warehouseId = ThreadLocalRandom.current().nextLong(1, scaleFactor + 1);
        long districtId = ThreadLocalRandom.current().nextLong(1, 11);
        long threshold = ThreadLocalRandom.current().nextLong(15, 21);

        try (ReadOnlyTransaction tx = client.readOnlyTransaction()) {
            long nextOrderId = -1;
            try (ResultSet rs = tx.executeQuery(Statement.newBuilder(
                    "SELECT next_order_id FROM district WHERE warehouse_id = @w AND district_id = @d")
                    .bind("w").to(warehouseId)
                    .bind("d").to(districtId)
                    .build())) {
                if (rs.next()) {
                    nextOrderId = rs.getLong(0);
                }
            }

            if (nextOrderId != -1) {
                try (ResultSet rs = tx.executeQuery(Statement.newBuilder(
                        "SELECT COUNT(DISTINCT s.item_id) FROM order_line ol " +
                        "JOIN stock s ON s.warehouse_id = ol.warehouse_id AND s.item_id = ol.item_id " +
                        "WHERE ol.warehouse_id = @w AND ol.district_id = @d " +
                        "AND ol.order_id >= @minOrderId AND ol.order_id < @nextOrderId " +
                        "AND s.quantity < @threshold")
                        .bind("w").to(warehouseId)
                        .bind("d").to(districtId)
                        .bind("minOrderId").to(Math.max(1, nextOrderId - 20))
                        .bind("nextOrderId").to(nextOrderId)
                        .bind("threshold").to(threshold)
                        .build())) {
                    if (rs.next()) {
                        rs.getLong(0);
                    }
                }
            }
        }
    }
}
