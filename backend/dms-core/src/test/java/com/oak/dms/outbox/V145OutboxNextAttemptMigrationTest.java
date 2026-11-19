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
 * V145 (OBX-03: {@code outbox_events.next_attempt_at} and the partial index for pending rows) on throwaway databases - the same
 * harness as the other migration tests. Proves, on a database that already holds pending, published and failed outbox rows,
 * that nothing existing changes except the new column, that every pending row stays eligible, that the table is not rewritten,
 * and that Flyway's ordering puts V145 last.
 * <p>
 * Named *Test so CI runs it (only needs Postgres with CREATE DATABASE).
 */
class V145OutboxNextAttemptMigrationTest {

    private static final String DB_URL = env("DB_URL", "jdbc:postgresql://localhost:5433/dms_dev");
    private static final String DB_USERNAME = env("DB_USERNAME", "postgres");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "password");

    @Test
    void onAnEmptyDatabase_theColumnIsNotNullDefaultsToNow_andThePartialIndexExists() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "145");
            try (Connection c = connect(url)) {
                assertThat(string(c, "select is_nullable from information_schema.columns where table_name = 'outbox_events'"
                        + " and column_name = 'next_attempt_at'")).isEqualTo("NO");
                assertThat(string(c, "select data_type from information_schema.columns where table_name = 'outbox_events'"
                        + " and column_name = 'next_attempt_at'")).isEqualTo("timestamp with time zone");
                assertThat(string(c, "select is_nullable from information_schema.columns where table_name = 'outbox_events'"
                        + " and column_name = 'first_failed_at'")).as("nullable, no default").isEqualTo("YES");
                assertThat(string(c, "select column_default from information_schema.columns where table_name = 'outbox_events'"
                        + " and column_name = 'first_failed_at'")).isNull();
                assertThat(string(c, "select column_default from information_schema.columns where table_name = 'outbox_events'"
                        + " and column_name = 'next_attempt_at'")).contains("now()");
                String index = string(c, "select indexdef from pg_indexes where indexname = 'ix_outbox_events_pending_due'");
                assertThat(index).contains("(next_attempt_at, created_at, outbox_event_id)").contains("WHERE").contains("pending");
                assertThat(string(c, "select indexname from pg_indexes where indexname = 'ix_outbox_events_status_created_at'"))
                        .as("the existing index is not touched").isEqualTo("ix_outbox_events_status_created_at");
            }
            migrate(url, "145");                                           // idempotent: nothing left to apply
        });
    }

    @Test
    void onADatabaseWithExistingOutboxRows_nothingChangesExceptTheNewColumn_andEveryPendingRowStaysEligible() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "144");
            String tenant = uuid();
            List<String> before;
            String relfilenodeBefore;
            try (Connection c = connect(url)) {
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                        + "', 'V145 tenant', 'v145-" + tenant + ".invalid', 'standard', 'active')");
                insertEvent(c, tenant, "pending", 0, null, "ocr.requested");
                insertEvent(c, tenant, "pending", 2, "broker refused", "user.created");
                insertEvent(c, tenant, "pending", 4, "no route", "import_batch.submitted");
                insertEvent(c, tenant, "published", 1, null, "ocr.requested");
                insertEvent(c, tenant, "published", 0, null, "ocr.requested");
                insertEvent(c, tenant, "failed", 5, "gave up", "user.created");
                insertEvent(c, tenant, "failed", 5, "gave up", "user.created");
                before = snapshot(c);
                relfilenodeBefore = string(c, "select relfilenode::text from pg_class where relname = 'outbox_events'");
            }

            migrate(url, "145");

            try (Connection c = connect(url)) {
                assertThat(snapshot(c)).as("every existing column of every row, payload, status and attempts included, is unchanged")
                        .isEqualTo(before);
                assertThat(count(c, "select count(*) from outbox_events where status = 'pending' and next_attempt_at <= now()"))
                        .as("every pending row is eligible at once").isEqualTo(3);
                assertThat(count(c, "select count(*) from outbox_events where next_attempt_at is null")).isZero();
                assertThat(count(c, "select count(*) from outbox_events where first_failed_at is not null"))
                        .as("nothing is invented for rows that already have attempts").isZero();
                assertThat(count(c, "select count(*) from outbox_events where status = 'published'")).isEqualTo(2);
                assertThat(count(c, "select count(*) from outbox_events where status = 'failed' and attempts = 5")).isEqualTo(2);
                assertThat(string(c, "select relfilenode::text from pg_class where relname = 'outbox_events'"))
                        .as("a stable default is a fast default: the table was not rewritten").isEqualTo(relfilenodeBefore);
                // the relay's batch query finds exactly the pending rows
                assertThat(count(c, "select count(*) from (select 1 from outbox_events where status = 'pending' and next_attempt_at <= now()"
                        + " order by next_attempt_at, created_at, outbox_event_id limit 100 for update skip locked) s")).isEqualTo(3);
            }
        });
    }

    @Test
    void aFailureHalfWayThroughTheMigrationRollsTheWholeMigrationBack() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "144");
            try (Connection c = connect(url)) {
                // an index with the name V145 wants, on something else: the ALTER TABLE succeeds, the CREATE INDEX then fails
                exec(c, "create index ix_outbox_events_pending_due on outbox_events (aggregate_id)");
            }

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrate(url, "145")).hasMessageContaining("ix_outbox_events_pending_due");

            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'outbox_events'"
                        + " and column_name in ('next_attempt_at', 'first_failed_at')")).as("the ALTER TABLE was rolled back too").isZero();
                assertThat(count(c, "select count(*) from flyway_schema_history where version = '145'")).as("V145 is not recorded").isZero();
                exec(c, "drop index ix_outbox_events_pending_due");
            }
            migrate(url, "145");                                           // and it applies cleanly once the obstacle is gone
            try (Connection c = connect(url)) {
                assertThat(count(c, "select count(*) from information_schema.columns where table_name = 'outbox_events'"
                        + " and column_name in ('next_attempt_at', 'first_failed_at')")).isEqualTo(2);
            }
        });
    }

    @Test
    void onALargerTable_theMigrationTimeAndWhatItDoesToConcurrentWritersAreMeasured() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "144");
            String tenant = uuid();
            try (Connection c = connect(url)) {
                exec(c, "insert into tenants (tenant_id, tenant_name, domain, subscription_tier, status) values ('" + tenant
                        + "', 'V145 big tenant', 'v145big-" + tenant + ".invalid', 'standard', 'active')");
                exec(c, "insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,"
                        + " attempts, created_at, published_at) select gen_random_uuid(), '" + tenant + "', 'v145', gen_random_uuid(),"
                        + " 'big.' || g, '{\"n\":1}'::jsonb, case when g % 100 = 0 then 'failed' else 'published' end, 1,"
                        + " now() - interval '10 days', now() - interval '10 days' from generate_series(1, 500000) g");
                exec(c, "insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,"
                        + " created_at) select gen_random_uuid(), '" + tenant + "', 'v145', gen_random_uuid(), 'pend.' || g, '{\"n\":1}'::jsonb,"
                        + " 'pending', now() from generate_series(1, 300) g");
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
                                + " values (gen_random_uuid(), '" + tenant + "', 'v145', gen_random_uuid(), 'live.writer', '{\"n\":1}'::jsonb)");
                        long ms = (System.nanoTime() - started) / 1_000_000;
                        slowestInsertMs.accumulateAndGet(ms, Math::max);
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
            migrate(url, "145");
            long migrationMs = (System.nanoTime() - started) / 1_000_000;
            Thread.sleep(300);
            stop.set(true);
            writer.join(10_000);

            try (Connection c = connect(url)) {
                System.out.println("OBX03-OBSERVE V145 on " + count(c, "select count(*) from outbox_events") + " rows (table "
                        + string(c, "select pg_size_pretty(pg_table_size('outbox_events'))") + ", partial index "
                        + string(c, "select pg_size_pretty(pg_relation_size('ix_outbox_events_pending_due'))") + "): migration took " + migrationMs
                        + " ms; a writer inserting every ~5 ms did " + inserts.get() + " inserts, the slowest took " + slowestInsertMs.get() + " ms");
                assertThat(count(c, "select count(*) from outbox_events where status = 'pending' and next_attempt_at <= now()")).isGreaterThanOrEqualTo(300);
            }
        });
    }

    @Test
    void flywayOrderingPutsV145AfterV144_withNoGapsOrFailures() throws Exception {
        withScratchDatabase(url -> {
            migrate(url, "145");
            try (Connection c = connect(url)) {
                List<Integer> versions = new ArrayList<>();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                        "select version from flyway_schema_history where success and version is not null order by installed_rank")) {
                    while (rs.next()) {
                        versions.add(Integer.parseInt(rs.getString(1)));
                    }
                }
                assertThat(versions).isSorted();
                assertThat(versions.get(versions.size() - 1)).isEqualTo(145);
                assertThat(versions).contains(144);
                assertThat(versions).doesNotHaveDuplicates();
                assertThat(count(c, "select count(*) from flyway_schema_history where not success")).isZero();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void insertEvent(Connection c, String tenant, String status, int attempts, String lastError, String type)
            throws SQLException {
        exec(c, "insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,"
                + " attempts, last_error, created_at, published_at) values ('" + uuid() + "', '" + tenant + "', 'v145', '" + uuid()
                + "', '" + type + "', '{\"k\": [1, 2, {\"v\": \"" + uuid() + "\"}]}'::jsonb, '" + status + "', " + attempts + ", "
                + (lastError == null ? "null" : "'" + lastError + "'") + ", now() - interval '3 hours', "
                + ("published".equals(status) ? "now() - interval '2 hours'" : "null") + ")");
    }

    /** Every column of every row except the new one, in a stable order, as text. */
    private static List<String> snapshot(Connection c) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select outbox_event_id || '|' || tenant_id || '|' || aggregate_type || '|' || aggregate_id || '|' || event_type || '|'"
                        + " || payload::text || '|' || status || '|' || attempts || '|' || coalesce(last_error, '~') || '|' || created_at"
                        + " || '|' || coalesce(published_at::text, '~') from outbox_events order by outbox_event_id")) {
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
        String name = "dms_v145_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
