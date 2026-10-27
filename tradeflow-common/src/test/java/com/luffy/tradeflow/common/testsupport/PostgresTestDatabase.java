package com.luffy.tradeflow.common.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A real PostgreSQL 16 server for compatibility tests:
 * Testcontainers ({@code postgres:16-alpine}) when Docker is available, otherwise
 * zonky embedded-postgres, which runs genuine PostgreSQL binaries from a Maven
 * artifact without Docker. Either way the SQL runs on PostgreSQL, not H2.
 */
public final class PostgresTestDatabase {

    private static PostgresTestDatabase instance;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String engine;

    private PostgresTestDatabase(String host, int port, String username, String password, String engine) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.engine = engine;
    }

    public static synchronized PostgresTestDatabase get() {
        if (instance == null) {
            instance = dockerAvailable() ? startContainer() : startEmbedded();
            System.out.println("[PostgresTestDatabase] using " + instance.engine);
        }
        return instance;
    }

    /** Creates (if needed) a database and returns its JDBC URL. */
    public String createDatabase(String name) {
        try (Connection c = DriverManager.getConnection(url("postgres"), username, password);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + name + "'")) {
                if (!rs.next()) {
                    st.execute("CREATE DATABASE " + name);
                }
            }
            return url(name);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test database " + name, e);
        }
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String engine() {
        return engine;
    }

    private String url(String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    private static PostgresTestDatabase startContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine");
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        return new PostgresTestDatabase(container.getHost(), container.getMappedPort(5432), container.getUsername(),
                container.getPassword(), "Testcontainers postgres:16-alpine");
    }

    private static PostgresTestDatabase startEmbedded() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (Exception ignored) {
                    // best effort on JVM exit
                }
            }));
            return new PostgresTestDatabase("localhost", pg.getPort(), "postgres", "", "embedded PostgreSQL (zonky)");
        } catch (Exception e) {
            throw new IllegalStateException("Neither Docker nor embedded PostgreSQL is available", e);
        }
    }
}
