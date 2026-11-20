package com.oak.dms.outbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V146 (OBX-04: the OPTIONAL partial index that lets the cleanup's batch selection be an ordered index scan) on throwaway
 * databases - the same harness as V145's test. Prepared and compiled; NOT yet run (it needs a disposable PostgreSQL).
 */
class V146OutboxPublishedCleanupIndexMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    @Test
    void onADatabaseWithExistingOutboxRows_theIndexIsAddedAndNothingElseChanges() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "145");
            String tenant = uuid();
            List<String> before;
            String relfilenodeBefore;
            try (Connection c = connect(url)) {
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                        + "', 'V146 tenant', 'v146-" + tenant + ".invalid', 'standard', 'active')");
                insertEvent(c, tenant, "pending", "null");
                insertEvent(c, tenant, "published", "now() - interval '40 days'");
                insertEvent(c, tenant, "published", "now() - interval '1 day'");
                insertEvent(c, tenant, "failed", "null");
                before = snapshot(c);
                relfilenodeBefore = string(c, "select relfilenode::text from pg_class where relname = 'outbox_events'");
            }

            migrate(url, "146");

            try (Connection c = connect(url)) {
                String index = string(c, "select indexdef from pg_indexes where indexname = 'ix_outbox_events_published_cleanup'");
                assertThat(index).contains("(published_at, outbox_event_id)").contains("WHERE").contains("published");
                assertThat(snapshot(c)).as("no row changed").isEqualTo(before);
                assertThat(string(c, "select relfilenode::text from pg_class where relname = 'outbox_events'"))
                        .as("the table was not rewritten").isEqualTo(relfilenodeBefore);
                for (String kept : List.of("ix_outbox_events_status_created_at", "ix_outbox_events_aggregate",
                        "ix_outbox_events_tenant_id", "ix_outbox_events_pending_due")) {
                    assertThat(string(c, "select indexname from pg_indexes where indexname = '" + kept + "'"))
                            .as("existing index kept: " + kept).isEqualTo(kept);
                }
                assertThat(count(c, "select count(*) from outbox_events")).isEqualTo(4);
            }
            migrate(url, "146");                                           // idempotent
        });
    }

    @Test
    void aFailureHalfWayRollsTheMigrationBack_andFlywayOrderingEndsAtV146() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "145");
            try (Connection c = connect(url)) {
                exec(c, "create index ix_outbox_events_published_cleanup on outbox_events (aggregate_id)");   // the name V146 wants, taken
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrate(url, "146"))
                    .hasMessageContaining("ix_outbox_events_published_cleanup");
            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from flyway_schema_history where version = '146'")).as("V146 is not recorded").isZero();
                exec(c, "drop index ix_outbox_events_published_cleanup");
            }

            migrate(url, "146");

            try (Connection c = connect(url)) {
                List<Integer> versions = new ArrayList<>();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                        "select version from flyway_schema_history where success and version is not null order by installed_rank")) {
                    while (rs.next()) {
                        versions.add(Integer.parseInt(rs.getString(1)));
                    }
                }
                assertThat(versions).isSorted().doesNotHaveDuplicates();
                assertThat(versions.get(versions.size() - 1)).isEqualTo(146);
                assertThat(versions).contains(145);
                assertThat(count(c, "select count(*) from flyway_schema_history where not success")).isZero();
            }
        });
    }

    @Test
    void onALargerTable_theIndexBuildTimeAndWhatItDoesToConcurrentWritersAreMeasured() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "145");
            String tenant = uuid();
            try (Connection c = connect(url)) {
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                        + "', 'V146 big tenant', 'v146big-" + tenant + ".invalid', 'standard', 'active')");
                exec(c, "insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,"
                        + " created_at, published_at) select gen_random_uuid(), '" + tenant + "', 'v146', gen_random_uuid(), 'big.' || g,"
                        + " jsonb_build_object('n', 1), 'published', now() - interval '60 days', now() - interval '59 days' + make_interval(secs => g)"
                        + " from generate_series(1, 500000) g");
                exec(c, "analyze outbox_events");
            }
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicLong slowestInsertMs = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicLong inserts = new java.util.concurrent.atomic.AtomicLong();
            Thread writer = new Thread(() -> {
                try (Connection c = connect(url)) {
                    while (!stop.get()) {
                        long started = System.nanoTime();
                        exec(c, "insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload)"
                                + " values (gen_random_uuid(), '" + tenant + "', 'v146', gen_random_uuid(), 'live.writer', jsonb_build_object('n', 1))");
                        slowestInsertMs.accumulateAndGet((System.nanoTime() - started) / 1_000_000, Math::max);
                        inserts.incrementAndGet();
                        Thread.sleep(5);
                    }
                } catch (Exception ignored) {
                    // the harness ends the run
                }
            });
            writer.start();
            Thread.sleep(300);
            long started = System.nanoTime();
            migrate(url, "146");
            long migrationMs = (System.nanoTime() - started) / 1_000_000;
            Thread.sleep(300);
            stop.set(true);
            writer.join(10_000);

            try (Connection c = connect(url)) {
                System.out.println("OBX04-OBSERVE V146 on " + count(c, "select count(*) from outbox_events") + " rows (table "
                        + string(c, "select pg_size_pretty(pg_table_size('outbox_events'))") + ", new index "
                        + string(c, "select pg_size_pretty(pg_relation_size('ix_outbox_events_published_cleanup'))") + "): migration took "
                        + migrationMs + " ms; a writer inserting every ~5 ms did " + inserts.get() + " inserts, the slowest took "
                        + slowestInsertMs.get() + " ms");
                assertThat(string(c, "select indexname from pg_indexes where indexname = 'ix_outbox_events_published_cleanup'")).isNotNull();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void insertEvent(Connection c, String tenant, String status, String publishedAtSql) throws SQLException {
        exec(c, "insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,"
                + " created_at, published_at) values ('" + uuid() + "', '" + tenant + "', 'v146', '" + uuid() + "', 'x.event',"
                + " jsonb_build_object('k', '" + uuid() + "'), '" + status + "', now() - interval '60 days', " + publishedAtSql + ")");
    }

    private static List<String> snapshot(Connection c) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select outbox_event_id || '|' || tenant_id || '|' || event_type || '|' || payload::text || '|' || status || '|' || attempts"
                        + " || '|' || created_at || '|' || coalesce(published_at::text, '~') || '|' || next_attempt_at"
                        + " || '|' || coalesce(first_failed_at::text, '~') from outbox_events order by outbox_event_id")) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static String string(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, DB_USERNAME, DB_PASSWORD)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private static void withScratchDatabase(ScratchTest test) throws Exception {
        String name = "dms_v146_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String base = DB_URL.substring(0, DB_URL.lastIndexOf('/') + 1);
        try (Connection admin = connect(base + "postgres"); Statement s = admin.createStatement()) {
            s.execute("create database " + name);
        }
        try {
            test.run(base + name);
        } finally {
            try (Connection admin = connect(base + "postgres"); Statement s = admin.createStatement()) {
                s.execute("drop database if exists " + name + " with (force)");
            }
        }
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, DB_USERNAME, DB_PASSWORD);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @FunctionalInterface
    private interface ScratchTest {
        void run(String url) throws Exception;
    }
}
