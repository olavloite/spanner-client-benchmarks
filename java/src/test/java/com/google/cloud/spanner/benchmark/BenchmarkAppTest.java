package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.MockSpannerServiceImpl;
import com.google.cloud.spanner.MockSpannerServiceImpl.StatementResult;
import com.google.cloud.spanner.Statement;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.google.spanner.v1.ResultSetMetadata;
import com.google.spanner.v1.StructType;
import com.google.spanner.v1.StructType.Field;
import com.google.spanner.v1.Type;
import com.google.spanner.v1.TypeCode;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class BenchmarkAppTest {

    private static MockSpannerServiceImpl mockSpanner;
    private static Server server;
    private static int port;

    @BeforeClass
    public static void startServer() throws Exception {
        mockSpanner = new MockSpannerServiceImpl();
        
        // Build a valid ResultSet with metadata to avoid "Missing type metadata" error
        com.google.spanner.v1.ResultSet resultSet =
                com.google.spanner.v1.ResultSet.newBuilder()
                        .setMetadata(
                                ResultSetMetadata.newBuilder()
                                        .setRowType(
                                                StructType.newBuilder()
                                                        .addFields(
                                                                Field.newBuilder()
                                                                        .setType(Type.newBuilder().setCode(TypeCode.INT64).build())
                                                                        .setName("id")
                                                                        .build())
                                                        .build())
                                        .build())
                        .addRows(
                                ListValue.newBuilder()
                                        .addValues(Value.newBuilder().setStringValue("1").build())
                                        .build())
                        .build();

        // Use putPartialStatementResult to match only on the SQL string.
        mockSpanner.putPartialStatementResult(
                StatementResult.query(
                        Statement.of("SELECT * FROM my_table WHERE id = @id"),
                        resultSet
                )
        );
        mockSpanner.putPartialStatementResult(
                StatementResult.query(
                        Statement.of("SELECT id FROM my_table WHERE id = @id"),
                        resultSet
                )
        );

        server = ServerBuilder.forPort(0)
                .addService(mockSpanner)
                .build()
                .start();
        port = server.getPort();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    @After
    public void clearRequests() {
        mockSpanner.clearRequests();
    }

    @Test
    public void testPointSelectBenchmarkRuns() throws Exception {
        Thread thread = new Thread(() -> {
            try {
                new picocli.CommandLine(new BenchmarkApp()).execute(new String[]{"-p", "my-project", "-i", "my-instance", "-d", "my-database", "--host", "http://localhost:" + port, "point-select", "-t", "my_table", "--tps", "1000", "--threads", "10"});
            } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
            }
        });

        thread.start();
        
        // Wait for at least one specific request
        long startTime = System.currentTimeMillis();
        boolean received = false;
        while (System.currentTimeMillis() - startTime < 5000) {
            boolean hasRequest = mockSpanner.getRequestsOfType(com.google.spanner.v1.ExecuteSqlRequest.class)
                    .stream()
                    .anyMatch(r -> r.getSql().contains("SELECT * FROM my_table"));
            if (hasRequest) {
                received = true;
                break;
            }
            Thread.sleep(1);
        }
        
        assertTrue("Should have received some requests", received);
        
        // Interrupt the thread to stop the infinite loop
        thread.interrupt();
        thread.join(5000); // Wait for it to finish
        
        assertTrue("Thread should have finished", !thread.isAlive());
    }

    @Test
    public void testSelectAndUpdateBenchmarkRuns() throws Exception {
        Thread thread = new Thread(() -> {
            try {
                new picocli.CommandLine(new BenchmarkApp()).execute(new String[]{"-p", "my-project", "-i", "my-instance", "-d", "my-database", "--host", "http://localhost:" + port, "select-update", "-t", "my_table", "--tps", "1000", "--threads", "10"});
            } catch (Exception e) {
                System.out.println("App terminated: " + e.getMessage());
            }
        });

        thread.start();
        
        // Wait for at least one specific request (DML)
        long startTime = System.currentTimeMillis();
        boolean received = false;
        while (System.currentTimeMillis() - startTime < 5000) {
            boolean hasDml = mockSpanner.getRequestsOfType(com.google.spanner.v1.ExecuteSqlRequest.class)
                    .stream()
                    .anyMatch(r -> r.getSql().contains("UPDATE my_table") || r.getSql().contains("INSERT INTO my_table"));
            if (hasDml) {
                received = true;
                break;
            }
            Thread.sleep(1);
        }
        
        assertTrue("Should have received some requests", received);
        
        // Interrupt the thread to stop the infinite loop
        thread.interrupt();
        thread.join(5000); // Wait for it to finish
        
        assertTrue("Thread should have finished", !thread.isAlive());
    }
}
