package com.google.cloud.spanner.benchmark;

import com.google.cloud.spanner.connection.SpannerPool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConnectionSupplier implements AutoCloseable {

  @FunctionalInterface
  public interface ConnectionInitializer {
    void initialize(Connection connection) throws SQLException;
  }

  private final String url;
  private final Properties properties;
  private final ConnectionInitializer initializer;
  private final List<Connection> connections = new CopyOnWriteArrayList<>();
  private final ThreadLocal<Connection> threadConnection;

  public ConnectionSupplier(String url, Properties properties) {
    this(url, properties, null);
  }

  public ConnectionSupplier(String url, Properties properties, ConnectionInitializer initializer) {
    this.url = url;
    this.properties = properties != null ? properties : new Properties();
    this.initializer = initializer;
    this.threadConnection =
        ThreadLocal.withInitial(
            () -> {
              try {
                Connection connection = DriverManager.getConnection(this.url, this.properties);
                if (this.initializer != null) {
                  this.initializer.initialize(connection);
                }
                connections.add(connection);
                return connection;
              } catch (SQLException e) {
                throw new RuntimeException("Failed to obtain JDBC connection", e);
              }
            });
  }

  public Connection getConnection() {
    return threadConnection.get();
  }

  public List<Connection> getActiveConnections() {
    return connections;
  }

  @Override
  public void close() {
    for (Connection connection : connections) {
      try {
        if (!connection.isClosed()) {
          connection.close();
        }
      } catch (SQLException ignored) {
      }
    }
    connections.clear();
    try {
      SpannerPool.closeSpannerPool();
    } catch (Exception ignored) {
    }
  }
}
