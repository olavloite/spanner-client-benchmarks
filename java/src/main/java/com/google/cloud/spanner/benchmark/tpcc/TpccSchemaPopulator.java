package com.google.cloud.spanner.benchmark.tpcc;

import com.google.cloud.spanner.DatabaseAdminClient;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class TpccSchemaPopulator {

  public static void createTables(
      DatabaseAdminClient adminClient, String instanceId, String databaseId)
      throws ExecutionException, InterruptedException {
    List<String> statements =
        Arrays.asList(
            "CREATE TABLE IF NOT EXISTS warehouse (\n"
                + "    warehouse_id INT64 NOT NULL,\n"
                + "    name STRING(10),\n"
                + "    street_1 STRING(20),\n"
                + "    street_2 STRING(20),\n"
                + "    city STRING(20),\n"
                + "    state STRING(2),\n"
                + "    zip STRING(9),\n"
                + "    tax FLOAT64,\n"
                + "    ytd FLOAT64\n"
                + ") PRIMARY KEY(warehouse_id)",
            "CREATE TABLE IF NOT EXISTS district (\n"
                + "    warehouse_id INT64 NOT NULL,\n"
                + "    district_id INT64 NOT NULL,\n"
                + "    name STRING(10),\n"
                + "    street_1 STRING(20),\n"
                + "    street_2 STRING(20),\n"
                + "    city STRING(20),\n"
                + "    state STRING(2),\n"
                + "    zip STRING(9),\n"
                + "    tax FLOAT64,\n"
                + "    ytd FLOAT64,\n"
                + "    next_order_id INT64\n"
                + ") PRIMARY KEY(warehouse_id, district_id),\n"
                + "  INTERLEAVE IN PARENT warehouse ON DELETE CASCADE",
            "CREATE TABLE IF NOT EXISTS customer (\n"
                + "    warehouse_id INT64 NOT NULL,\n"
                + "    district_id INT64 NOT NULL,\n"
                + "    customer_id INT64 NOT NULL,\n"
                + "    first_name STRING(16),\n"
                + "    middle_name STRING(2),\n"
                + "    last_name STRING(16),\n"
                + "    street_1 STRING(20),\n"
                + "    street_2 STRING(20),\n"
                + "    city STRING(20),\n"
                + "    state STRING(2),\n"
                + "    zip STRING(9),\n"
                + "    phone STRING(16),\n"
                + "    since TIMESTAMP,\n"
                + "    credit STRING(2),\n"
                + "    credit_limit FLOAT64,\n"
                + "    discount FLOAT64,\n"
                + "    balance FLOAT64,\n"
                + "    ytd_payment FLOAT64,\n"
                + "    payment_count INT64,\n"
                + "    delivery_count INT64,\n"
                + "    data STRING(500)\n"
                + ") PRIMARY KEY(warehouse_id, district_id, customer_id),\n"
                + "  INTERLEAVE IN PARENT district ON DELETE CASCADE",
            "CREATE INDEX IF NOT EXISTS idx_customer_last_name ON customer(warehouse_id, district_id, last_name)",
            "CREATE TABLE IF NOT EXISTS history (\n"
                + "    warehouse_id INT64 NOT NULL,\n"
                + "    district_id INT64 NOT NULL,\n"
                + "    history_id STRING(36) NOT NULL,\n"
                + "    customer_id INT64 NOT NULL,\n"
                + "    date TIMESTAMP,\n"
                + "    amount FLOAT64,\n"
                + "    data STRING(24)\n"
                + ") PRIMARY KEY(warehouse_id, district_id, history_id)",
            "CREATE TABLE IF NOT EXISTS item (\n"
                + "    item_id INT64 NOT NULL,\n"
                + "    im_id INT64,\n"
                + "    name STRING(24),\n"
                + "    price FLOAT64,\n"
                + "    data STRING(50)\n"
                + ") PRIMARY KEY(item_id)",
            "CREATE TABLE IF NOT EXISTS stock (\n"
                + "    warehouse_id INT64 NOT NULL,\n"
                + "    item_id INT64 NOT NULL,\n"
                + "    quantity INT64,\n"
                + "    ytd FLOAT64,\n"
                + "    order_count INT64,\n"
                + "    remote_count INT64,\n"
                + "    data STRING(50)\n"
                + ") PRIMARY KEY(warehouse_id, item_id)",
            "CREATE TABLE IF NOT EXISTS orders (\n"
                + "    warehouse_id INT64 NOT NULL,\n"
                + "    district_id INT64 NOT NULL,\n"
                + "    order_id INT64 NOT NULL,\n"
                + "    customer_id INT64 NOT NULL,\n"
                + "    entry_date TIMESTAMP,\n"
                + "    carrier_id INT64,\n"
                + "    item_count INT64,\n"
                + "    all_local INT64\n"
                + ") PRIMARY KEY(warehouse_id, district_id, order_id)",
            "CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(warehouse_id, district_id, customer_id, order_id)",
            "CREATE TABLE IF NOT EXISTS order_line (\n"
                + "    warehouse_id INT64 NOT NULL,\n"
                + "    district_id INT64 NOT NULL,\n"
                + "    order_id INT64 NOT NULL,\n"
                + "    order_line_id INT64 NOT NULL,\n"
                + "    item_id INT64 NOT NULL,\n"
                + "    delivery_date TIMESTAMP,\n"
                + "    quantity INT64,\n"
                + "    amount FLOAT64,\n"
                + "    dist_info STRING(24)\n"
                + ") PRIMARY KEY(warehouse_id, district_id, order_id, order_line_id),\n"
                + "  INTERLEAVE IN PARENT orders ON DELETE CASCADE",
            "CREATE TABLE IF NOT EXISTS new_orders (\n"
                + "    warehouse_id INT64 NOT NULL,\n"
                + "    district_id INT64 NOT NULL,\n"
                + "    order_id INT64 NOT NULL,\n"
                + "    created_timestamp TIMESTAMP NOT NULL\n"
                + ") PRIMARY KEY(warehouse_id, district_id, order_id)",
            "CREATE INDEX IF NOT EXISTS idx_new_orders_timestamp ON new_orders(warehouse_id, district_id, created_timestamp)");

    adminClient.updateDatabaseDdl(instanceId, databaseId, statements, null).get();
    System.out.println("Successfully validated TPC-C schema definitions.");
  }
}
