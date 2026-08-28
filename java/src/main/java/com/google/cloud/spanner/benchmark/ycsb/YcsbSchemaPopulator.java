package com.google.cloud.spanner.benchmark.ycsb;

import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

public class YcsbSchemaPopulator {

  public static void createTable(
      DatabaseClient client,
      DatabaseAdminClient adminClient,
      String instanceId,
      String databaseId,
      String tableName,
      int fieldCount)
      throws ExecutionException, InterruptedException {
    // Fast metadata check: avoid triggering slow Spanner DDL workflow if table already exists
    if (tableExists(client, tableName)) {
      System.out.println("Table " + tableName + " already exists. Skipping DDL creation.");
      return;
    }

    String ddl = generateDdl(tableName, fieldCount);
    System.out.println("Applying DDL for YCSB table: " + tableName + "...");
    adminClient
        .updateDatabaseDdl(instanceId, databaseId, Collections.singletonList(ddl), null)
        .get();
    System.out.println("Successfully created/verified table: " + tableName);
  }

  static String generateDdl(String tableName, int fieldCount) {
    StringBuilder builder = new StringBuilder();
    builder.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");
    builder.append("    id STRING(MAX),\n");
    for (int i = 0; i < fieldCount; i++) {
      builder.append("    field").append(i).append(" STRING(MAX),\n");
    }
    builder.append(") PRIMARY KEY(id)");
    return builder.toString();
  }

  private static boolean tableExists(DatabaseClient client, String tableName) {
    try {
      Statement statement =
          Statement.newBuilder(
                  "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '' AND TABLE_NAME = @tableName")
              .bind("tableName")
              .to(tableName)
              .build();
      try (ResultSet resultSet = client.singleUse().executeQuery(statement)) {
        return resultSet.next();
      }
    } catch (Exception e) {
      // In mock tests or restricted permissions, proceed to attempt DDL
      return false;
    }
  }
}
