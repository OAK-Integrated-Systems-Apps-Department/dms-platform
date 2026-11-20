package com.oak.dms.reporting.datasource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CRG-NFR-003 on real PostgreSQL: one repeatable-read snapshot, read-only, with a timeout that does not outlive the transaction. */
class ReadOnlySnapshotTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    private static String scratch;
    private static String base;
    private static SingleConnectionDataSource dataSource;
    private static ReadOnlySnapshot snapshot;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void createScratchDatabase() throws Exception {
        scratch = "dms_crg_snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        base = DB_URL.substring(0, DB_URL.lastIndexOf('/') + 1);
        try (Connection admin = DriverManager.getConnection(base + "postgres", DB_USERNAME, DB_PASSWORD);
             Statement s = admin.createStatement()) {
            s.execute("create database " + scratch);
        }
        try (Connection c = DriverManager.getConnection(base + scratch, DB_USERNAME, DB_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("create table docs (id int primary key)");
            s.execute("insert into docs values (1), (2), (3)");
        }
        dataSource = new SingleConnectionDataSource(base + scratch, DB_USERNAME, DB_PASSWORD, true);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        snapshot = new ReadOnlySnapshot(new DataSourceTransactionManager(dataSource), jdbc);
    }

    @AfterAll
    static void dropScratchDatabase() throws Exception {
        dataSource.destroy();
        try (Connection admin = DriverManager.getConnection(base + "postgres", DB_USERNAME, DB_PASSWORD);
             Statement s = admin.createStatement()) {
            s.execute("drop database if exists " + scratch + " with (force)");
        }
    }

    private static String show(NamedParameterJdbcTemplate t, String setting) {
        return t.queryForObject("show " + setting, Map.of(), String.class);
    }

    @Test
    void runsAtRepeatableRead_readOnly_withTheStatementTimeoutApplied() {
        snapshot.run(Duration.ofSeconds(1), t -> {
            assertThat(show(t, "transaction_isolation")).isEqualTo("repeatable read");
            assertThat(show(t, "transaction_read_only")).isEqualTo("on");
            assertThat(show(t, "statement_timeout")).isEqualTo("1s");
            return null;
        });
    }

    @Test
    void theTimeoutEndsWithTheTransaction_soItNeverLeaksToTheNextUserOfAPooledConnection() {
        snapshot.run(Duration.ofSeconds(1), t -> null);

        assertThat(show(jdbc, "statement_timeout")).isEqualTo("0");
    }

    @Test
    void aSlowStatementIsCancelled_andRecognisedAsATimeout() {
        assertThatThrownBy(() -> snapshot.run(Duration.ofMillis(300), t -> t.queryForObject("select pg_sleep(2)::text", Map.of(), String.class)))
                .satisfies(e -> assertThat(ReadOnlySnapshot.isTimeout(e)).isTrue());
    }

    @Test
    void anOrdinaryFailureIsNotMistakenForATimeout() {
        assertThatThrownBy(() -> snapshot.run(Duration.ofSeconds(5), t -> t.queryForObject("select * from no_such_table", Map.of(), String.class)))
                .satisfies(e -> assertThat(ReadOnlySnapshot.isTimeout(e)).isFalse());
    }

    @Test
    void aReportCannotWrite() {
        assertThatThrownBy(() -> snapshot.run(Duration.ofSeconds(5), t -> t.update("insert into docs values (99)", Map.of())))
                .hasMessageContaining("read-only transaction");
    }

    @Test
    void everyStatementInOneRunSeesTheSameState_evenIfAnotherTransactionCommitsInBetween() throws Exception {
        long[] counts = new long[2];

        snapshot.run(Duration.ofSeconds(5), t -> {
            counts[0] = t.queryForObject("select count(*) from docs", Map.of(), Long.class);
            try (Connection other = DriverManager.getConnection(base + scratch, DB_USERNAME, DB_PASSWORD);
                 Statement s = other.createStatement()) {
                s.execute("insert into docs values (" + (100 + (int) (Math.random() * 100000)) + ")");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            counts[1] = t.queryForObject("select count(*) from docs", Map.of(), Long.class);
            return null;
        });

        assertThat(counts[1]).as("the row committed mid-run is not visible to it").isEqualTo(counts[0]);
        assertThat(jdbc.queryForObject("select count(*) from docs", Map.of(), Long.class)).isGreaterThan(counts[0]);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
