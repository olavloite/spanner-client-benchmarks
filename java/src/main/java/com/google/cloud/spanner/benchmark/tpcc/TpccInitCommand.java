package com.google.cloud.spanner.benchmark.tpcc;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.benchmark.BenchmarkApp;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "tpcc-init", description = "Initializes TPC-C schema and pre-populates data.")
public class TpccInitCommand implements Runnable {

    @ParentCommand
    private BenchmarkApp parent;

    @Option(names = {"--warehouses"}, description = "Scale factor (number of warehouses)", defaultValue = "1")
    private int warehouses;

    @Option(names = {"--items"}, description = "Number of items in catalog", defaultValue = "100000")
    private int items;

    @Override
    public void run() {
        try {
            SpannerOptions.Builder spannerOptionsBuilder = SpannerOptions.newBuilder().setProjectId(parent.getProjectId());
            if (parent.getHost() != null) {
                spannerOptionsBuilder.setHost(parent.getHost());
                spannerOptionsBuilder.setChannelConfigurator(builder -> builder.usePlaintext());
                spannerOptionsBuilder.setCredentials(NoCredentials.getInstance());
            }
            SpannerOptions spannerOptions = spannerOptionsBuilder.build();
            try (Spanner spanner = spannerOptions.getService()) {
                DatabaseClient client = spanner.getDatabaseClient(DatabaseId.of(parent.getProjectId(), parent.getInstanceId(), parent.getDatabaseId()));
                
                TpccSchemaPopulator.createTables(spanner.getDatabaseAdminClient(), parent.getInstanceId(), parent.getDatabaseId());
                TpccDataGenerator.populate(client, warehouses, items);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
