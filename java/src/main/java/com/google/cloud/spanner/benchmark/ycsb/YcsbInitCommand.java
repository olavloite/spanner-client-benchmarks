package com.google.cloud.spanner.benchmark.ycsb;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.benchmark.BenchmarkApp;
import com.google.cloud.spanner.benchmark.SpannerClientHelper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "ycsb-init", description = "Initializes YCSB schema and pre-populates data.")
public class YcsbInitCommand implements Runnable {

  @ParentCommand private BenchmarkApp parent;

  @Option(
      names = {"-t", "--table"},
      description = "Table name. Defaults to usertable.",
      defaultValue = "usertable")
  private String tableName = "usertable";

  @Option(
      names = {"--record-count"},
      description = "Total number of records to pre-populate. Defaults to 100,000.",
      defaultValue = "100000")
  private long recordCount = 100_000L;

  @Option(
      names = {"--field-count"},
      description = "Number of fields per record. Defaults to 10.",
      defaultValue = "10")
  private int fieldCount = 10;

  @Option(
      names = {"--field-length"},
      description = "Length of each field value in bytes. Defaults to 100.",
      defaultValue = "100")
  private int fieldLength = 100;

  @Option(
      names = {"--zero-padding"},
      description =
          "Zero padding length for primary key generation (e.g. 12 -> user000000000001). Defaults to 12.",
      defaultValue = "12")
  private int zeroPadding = 12;

  @Option(
      names = {"--threads"},
      description = "Number of parallel worker threads for data loading. Defaults to 16.",
      defaultValue = "16")
  private int threads = 16;

  @Option(
      names = {"--batch-size"},
      description = "Number of rows per mutation batch. Defaults to 500.",
      defaultValue = "500")
  private int batchSize = 500;

  @Option(
      names = {"--skip-schema"},
      description = "Skip DDL schema creation.",
      defaultValue = "false")
  private boolean skipSchema = false;

  @Option(
      names = {"--skip-data"},
      description = "Skip data population.",
      defaultValue = "false")
  private boolean skipData = false;

  @Override
  public void run() {
    try {
      SpannerOptions spannerOptions =
          SpannerClientHelper.createSpannerOptions(parent.getProjectId(), parent.getHost());
      try (Spanner spanner = spannerOptions.getService()) {
        DatabaseClient client =
            spanner.getDatabaseClient(
                DatabaseId.of(
                    parent.getProjectId(), parent.getInstanceId(), parent.getDatabaseId()));

        if (!skipSchema) {
          YcsbSchemaPopulator.createTable(
              client,
              spanner.getDatabaseAdminClient(),
              parent.getInstanceId(),
              parent.getDatabaseId(),
              tableName,
              fieldCount);
        }

        if (!skipData) {
          YcsbDataGenerator.populate(
              client,
              tableName,
              recordCount,
              fieldCount,
              fieldLength,
              zeroPadding,
              threads,
              batchSize);
        }
      }
    } catch (Exception e) {
      System.err.println("YCSB initialization failed: " + e.getMessage());
      throw new RuntimeException("YCSB initialization failed", e);
    }
  }
}
