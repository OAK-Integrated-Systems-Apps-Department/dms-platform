package com.oak.dms.outbox;

import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * OBX-04 against PostgreSQL: which rows the cleanup deletes and which it never touches, how it behaves next to other cleanups and
 * next to the relay, and how the batch query plans. Prepared and compiled; NOT yet run (it needs a disposable PostgreSQL, which
 * needs separate approval).
 * <p>
 * Every test works on rows of its own (an event type with a unique prefix). Ages are written as SQL on the database clock so the
 * 30-day (720-hour) retention in this class's configuration is exercised exactly. The broker is a controlled stand-in.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
@TestPropertySource(properties = {
        "dms.outbox.relay-interval-ms=3600000",
        "dms.outbox.cleanup.enabled=true",
        "dms.outbox.cleanup.published-retention-hours=720",
        "dms.outbox.cleanup.batch-size=100",
        "dms.outbox.cleanup.max-batches-per-run=1",
        "dms.outbox.cleanup.interval-ms=3600000000"})
class OutboxCleanupIT {

    private static final long RETENTION_SECONDS = 720L * 3600;
    private static final long WAIT_SECONDS = 30;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxCleanup cleanup;
    @Autowired
    private OutboxCleanupStore store;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private WorkflowFixtures fx;

    private JdbcTemplate jdbc;
    private UUID tenant;
    private String prefix;

    @BeforeEach
    void setUp() {
        jdbc = fx.jdbc();
        tenant = fx.tenant();
        prefix = "obx04." + UUID.randomUUID().toString().substring(0, 8) + ".";
        jdbc.update("update outbox_events set status = 'failed' where status = 'pending'");       // park what other tests left pending
        // The cleanup is global: any OLD published row in this (disposable) database is eligible, including one an earlier test of this
        // class left behind. Park those too, so each test sees only the rows it seeded.
        jdbc.update("update outbox_events set status = 'failed' where status = 'published' and published_at < now() - interval '720 hours'");
        reset(rabbitTemplate);
        OutboxConfirms.stubInvoke(rabbitTemplate);
        doAnswer(OutboxConfirms::ack).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    // ------------------------------------------------------------------ helpers

    private static final String COLUMNS = "(outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,"
            + " attempts, created_at, published_at, next_attempt_at, first_failed_at)";

    /** Inserts a row; every time argument is SQL on the database clock, or null. */
    private String insert(String suffix, String status, int attempts, String createdAt, String publishedAt, String nextAttemptAt,
                          String firstFailedAt) {
        String type = prefix + suffix;
        jdbc.update("insert into outbox_events " + COLUMNS + " values (?, ?, 'obx04', ?, ?, jsonb_build_object('n', 1), ?, ?, "
                        + createdAt + ", " + publishedAt + ", " + nextAttemptAt + ", " + firstFailedAt + ")",
                UUID.randomUUID(), tenant, UUID.randomUUID(), type, status, attempts);
        return type;
    }

    private String published(String suffix, String ageSql) {
        return insert(suffix, "published", 0, "now() - interval '" + ageSql + "' - interval '1 hour'", "now() - interval '" + ageSql + "'", "now()", "null");
    }

    private String pending(String suffix, String createdAgeSql) {
        return insert(suffix, "pending", 0, "now() - interval '" + createdAgeSql + "'", "null", "now()", "null");
    }

    private String failed(String suffix, String ageSql) {
        return insert(suffix, "failed", 150, "now() - interval '" + ageSql + "'", "null", "now() - interval '" + ageSql + "'",
                "now() - interval '" + ageSql + "'");
    }

    private boolean exists(String type) {
        return jdbc.queryForObject("select count(*) from outbox_events where event_type = ?", Long.class, type) > 0;
    }

    private long remaining() {
        return jdbc.queryForObject("select count(*) from outbox_events where event_type like ?", Long.class, prefix + "%");
    }

    private String statusOf(String type) {
        return jdbc.queryForObject("select status from outbox_events where event_type = ?", String.class, type);
    }

    private List<String> seedPublished(int count, String ageSql) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            types.add(published(String.format("p%04d", i), ageSql));
        }
        return types;
    }

    private static ExecutorService pool(int threads, String namePrefix) {
        int[] n = {0};
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, namePrefix + (char) ('A' + n[0]++));
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    void ofAMixOfRows_onlyPublishedRowsOlderThanTheRetentionAreDeleted() {
        List<String> old = seedPublished(5, "40 days");
        List<String> recent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            recent.add(published("r" + i, "10 days"));
        }
        String pendingOld = pending("pend.old", "90 days");
        String retryScheduled = insert("pend.retry", "pending", 7, "now() - interval '90 days'", "null", "now() + interval '1 hour'",
                "now() - interval '40 days'");
        String failedOld = failed("failed.old", "90 days");
        String publishedWithoutDate = insert("published.nodate", "published", 0, "now() - interval '90 days'", "null", "now()", "null");

        int deleted = cleanup.runOnce();

        assertThat(deleted).isEqualTo(5);
        old.forEach(t -> assertThat(exists(t)).as("old published " + t).isFalse());
        recent.forEach(t -> assertThat(exists(t)).as("recent published " + t).isTrue());
        assertThat(exists(pendingOld)).as("a pending event is never deleted for being old").isTrue();
        assertThat(exists(retryScheduled)).as("a retry-scheduled event is never deleted").isTrue();
        assertThat(statusOf(retryScheduled)).isEqualTo("pending");
        assertThat(exists(failedOld)).as("a terminally failed event is never deleted").isTrue();
        assertThat(exists(publishedWithoutDate)).as("no published_at, no deletion").isTrue();
    }

    @Test
    void theRetentionWindowIsRespectedToTheMinute() {
        String justInside = published("inside", "29 days 23 hours 59 minutes");
        String justOutside = published("outside", "30 days 1 minute");

        cleanup.runOnce();

        assertThat(exists(justInside)).isTrue();
        assertThat(exists(justOutside)).isFalse();
    }

    @Test
    void aRowExactlyAtTheCutoffIsKept_oneMillisecondOlderIsDeleted() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String[] types = new String[2];

        Integer deleted = tx.execute(status -> {
            // inside one transaction now() is the same for these inserts and for the delete
            types[0] = insert("boundary.equal", "published", 0, "now() - interval '721 hours'", "now() - interval '720 hours'", "now()", "null");
            types[1] = insert("boundary.older", "published", 0, "now() - interval '721 hours'",
                    "now() - (interval '720 hours' + interval '1 millisecond')", "now()", "null");
            return store.deletePublishedBatch(RETENTION_SECONDS, 100);
        });

        assertThat(deleted).isEqualTo(1);
        assertThat(exists(types[0])).as("published_at == cutoff: strict comparison, kept").isTrue();
        assertThat(exists(types[1])).isFalse();
    }

    @Test
    void theCleanupNeverDeletesWhileDisabled() {
        List<String> old = seedPublished(3, "40 days");
        OutboxCleanup off = new OutboxCleanup(store, transactionManager, new OutboxCleanupPolicy(false, 720, 100, 3_600_000L, 1));

        assertThat(off.runOnce()).isZero();

        old.forEach(t -> assertThat(exists(t)).isTrue());
    }

    // ------------------------------------------------------------------ batches

    @Test
    void aBatchIsNeverLargerThanTheBatchSize_andRepeatedRunsDrainTheRest() {
        seedPublished(250, "40 days");

        assertThat(cleanup.runOnce()).isEqualTo(100);
        assertThat(remaining()).isEqualTo(150);
        assertThat(cleanup.runOnce()).isEqualTo(100);
        assertThat(cleanup.runOnce()).isEqualTo(50);
        assertThat(cleanup.runOnce()).as("nothing eligible is left").isZero();
        assertThat(remaining()).isZero();
    }

    @Test
    void aRunNeverExceedsItsBatchQuota() {
        seedPublished(450, "40 days");
        OutboxCleanup three = new OutboxCleanup(store, transactionManager, new OutboxCleanupPolicy(true, 720, 100, 3_600_000L, 3));

        assertThat(three.runOnce()).isEqualTo(300);
        assertThat(remaining()).isEqualTo(150);
    }

    @Test
    void theOldestPublishedRowsGoFirst() {
        List<String> oldest = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            oldest.add(published(String.format("a%04d", i), (90 + i) + " days"));        // 90 to 189 days old
        }
        List<String> newer = seedPublished(50, "40 days");

        cleanup.runOnce();                                                              // one batch of 100

        oldest.forEach(t -> assertThat(exists(t)).isFalse());
        newer.forEach(t -> assertThat(exists(t)).isTrue());
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void twoCleanupsAtOnceTakeDifferentRows_noRowIsDeletedTwiceAndNoneIsWaitedFor() throws Exception {
        seedPublished(300, "40 days");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch aHasItsRows = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = pool(1, "cleanup-");

        Future<Integer> a = pool.submit(() -> tx.execute(status -> {
            int n = store.deletePublishedBatch(RETENTION_SECONDS, 100);
            aHasItsRows.countDown();
            try {
                assertThat(releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();    // A keeps its row locks until it commits
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return n;
        }));
        assertThat(aHasItsRows.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Integer b = tx.execute(status -> store.deletePublishedBatch(RETENTION_SECONDS, 100));   // B, while A holds 100 rows

        releaseA.countDown();
        Integer aDeleted = a.get(WAIT_SECONDS, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(b).as("B was not blocked and took a full batch of other rows").isEqualTo(100);
        assertThat(aDeleted).isEqualTo(100);
        assertThat(remaining()).as("300 - 100 - 100").isEqualTo(100);
    }

    @Test
    void aRowSomeoneElseHoldsIsSkipped_notWaitedFor() throws Exception {
        List<String> eligible = seedPublished(105, "40 days");
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                s.execute("select 1 from outbox_events where event_type in ('" + String.join("','", eligible.subList(0, 5))
                        + "') for update");                                              // another transaction locks 5 of the oldest
                int deleted = cleanup.runOnce();

                assertThat(deleted).as("the other 100 were taken; the locked 5 were skipped").isEqualTo(100);
                eligible.subList(0, 5).forEach(t -> assertThat(exists(t)).as("locked row " + t).isTrue());
            } finally {
                holder.rollback();
            }
        }
        assertThat(cleanup.runOnce()).as("once released they are eligible again").isEqualTo(5);
    }

    @Test
    void theRelayAndTheCleanupRunAtTheSameTime_withoutTouchingEachOthersRows() throws Exception {
        List<String> old = seedPublished(3, "40 days");
        String due1 = insert("due1", "pending", 0, "now() - interval '5 minutes'", "null", "now() - interval '1 minute'", "null");
        String due2 = insert("due2", "pending", 0, "now() - interval '4 minutes'", "null", "now() - interval '1 minute'", "null");
        CountDownLatch relayHoldsItsRows = new CountDownLatch(1);
        CountDownLatch releaseRelay = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("relay-A")) {
                relayHoldsItsRows.countDown();
                assertThat(releaseRelay.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();   // the relay sits mid-batch holding its locks
            }
            return OutboxConfirms.ack(invocation);
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ExecutorService pool = pool(1, "relay-");

        Future<?> relayRun = pool.submit(relay::relayPendingEvents);
        assertThat(relayHoldsItsRows.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        int deleted = cleanup.runOnce();                                                  // the cleanup, meanwhile

        releaseRelay.countDown();
        relayRun.get(WAIT_SECONDS, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(deleted).as("the cleanup was not blocked by the relay's locks").isEqualTo(3);
        old.forEach(t -> assertThat(exists(t)).isFalse());
        assertThat(statusOf(due1)).as("the relay completed its own rows").isEqualTo("published");
        assertThat(statusOf(due2)).isEqualTo("published");
        assertThat(cleanup.runOnce()).as("what the relay just published is far inside the retention").isZero();
    }

    // ------------------------------------------------------------------ rollback

    @Test
    void aRolledBackCleanupDeletesNothing() {
        List<String> old = seedPublished(10, "40 days");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Integer deleted = tx.execute(status -> {
            int n = store.deletePublishedBatch(RETENTION_SECONDS, 100);
            status.setRollbackOnly();
            return n;
        });

        assertThat(deleted).as("the batch did delete, inside the transaction").isEqualTo(10);
        old.forEach(t -> assertThat(exists(t)).as("but the rollback brought it back: " + t).isTrue());
        assertThat(cleanup.runOnce()).as("and it is still eligible").isEqualTo(10);
    }

    @Test
    void theStoreRefusesToRunOutsideATransaction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.deletePublishedBatch(RETENTION_SECONDS, 100))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    // ------------------------------------------------------------------ OBX-03 and the OCR worker

    @Test
    void retryScheduledAndFailedEventsSurviveRepeatedCleanups_andTheRelayStillDeliversTheDueOnes() {
        String waiting = insert("retry.waiting", "pending", 12, "now() - interval '100 days'", "null", "now() + interval '10 minutes'", "now() - interval '40 days'");
        String due = insert("retry.due", "pending", 12, "now() - interval '100 days'", "null", "now() - interval '1 minute'", "now() - interval '40 days'");
        String failed = failed("failed", "100 days");

        for (int i = 0; i < 3; i++) {
            cleanup.runOnce();
        }
        assertThat(exists(waiting) && exists(due) && exists(failed)).isTrue();

        relay.relayPendingEvents();                                                      // OBX-03: only the due one is selected

        assertThat(statusOf(due)).isEqualTo("published");
        assertThat(statusOf(waiting)).isEqualTo("pending");
        assertThat(statusOf(failed)).isEqualTo("failed");
        assertThat(cleanup.runOnce()).as("a just-published event is inside the retention").isZero();
        assertThat(exists(due)).isTrue();
    }

    @Test
    void eventsInsertedTheWayTheOcrWorkerInsertsThem_workAndAreCleanedLikeAnyOther() {
        String type = prefix + "worker";
        // the OCR worker's columns only: no next_attempt_at, no first_failed_at, no published_at, no last_error
        jdbc.update("insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, created_at, payload,"
                + " status, attempts) values (?, ?, 'document_version', ?, ?, now(), jsonb_build_object('n', 1), 'pending', 0)",
                UUID.randomUUID(), tenant, UUID.randomUUID(), type);

        assertThat(jdbc.queryForObject("select next_attempt_at <= now() and first_failed_at is null and published_at is null"
                + " from outbox_events where event_type = ?", Boolean.class, type)).as("the database defaults apply").isTrue();
        assertThat(cleanup.runOnce()).as("a pending OCR event is never deleted").isZero();
        relay.relayPendingEvents();
        assertThat(statusOf(type)).isEqualTo("published");
        assertThat(cleanup.runOnce()).isZero();

        jdbc.update("update outbox_events set published_at = now() - interval '40 days' where event_type = ?", type);
        assertThat(cleanup.runOnce()).as("once published long ago it goes, whoever created it").isEqualTo(1);
        assertThat(exists(type)).isFalse();
    }

    // ------------------------------------------------------------------ plans

    @Test
    void theBatchSelectionPlan_withAndWithoutTheOptionalPublishedIndex_isMeasured() throws Exception {
        String cols = COLUMNS;
        jdbc.update("insert into outbox_events " + cols + " select gen_random_uuid(), ?, 'obx04', gen_random_uuid(), ? || 'old' || g,"
                + " jsonb_build_object('n', 1), 'published', 0, now() - interval '50 days', now() - interval '40 days' - make_interval(secs => g),"
                + " now(), null from generate_series(1, 20000) g", tenant, prefix);
        jdbc.update("insert into outbox_events " + cols + " select gen_random_uuid(), ?, 'obx04', gen_random_uuid(), ? || 'new' || g,"
                + " jsonb_build_object('n', 1), 'published', 0, now() - interval '5 days', now() - interval '4 days' - make_interval(secs => g),"
                + " now(), null from generate_series(1, 20000) g", tenant, prefix);
        jdbc.update("insert into outbox_events " + cols + " select gen_random_uuid(), ?, 'obx04', gen_random_uuid(), ? || 'pend' || g,"
                + " jsonb_build_object('n', 1), 'pending', 0, now() - interval '50 days', null, now() + interval '1 hour', null"
                + " from generate_series(1, 500) g", tenant, prefix);
        jdbc.execute("analyze outbox_events");
        String select = "select outbox_event_id from outbox_events where status = 'published'"
                + " and published_at < now() - make_interval(secs => 2592000) order by published_at asc, outbox_event_id asc limit 100"
                + " for update skip locked";

        List<String> withIndex = explain(select, false, false);
        List<String> withoutIndex = explain(select, true, false);
        List<String> forcedOldIndex = explain(select, true, true);

        System.out.println("OBX04-OBSERVE rows=" + jdbc.queryForObject("select count(*) from outbox_events", Long.class)
                + " published_index=" + jdbc.queryForObject("select coalesce(pg_size_pretty(pg_relation_size(to_regclass('ix_outbox_events_published_cleanup'))), 'absent')", String.class)
                + " status_created_index=" + jdbc.queryForObject("select pg_size_pretty(pg_relation_size('ix_outbox_events_status_created_at'))", String.class));
        System.out.println("OBX04-OBSERVE plan WITH the optional published index (planner's choice):\n" + String.join("\n", withIndex));
        System.out.println("OBX04-OBSERVE plan WITHOUT it (index dropped inside a rolled-back transaction):\n" + String.join("\n", withoutIndex));
        System.out.println("OBX04-OBSERVE plan WITHOUT it, sequential scans disabled:\n" + String.join("\n", forcedOldIndex));
        jdbc.update("delete from outbox_events where event_type like ?", prefix + "%");
        assertThat(String.join("\n", withIndex)).as("with the optional index the batch is an ordered index scan").doesNotContain("Sort");
    }

    /** EXPLAIN (ANALYZE, BUFFERS) in a transaction that is always rolled back; optionally with the published index dropped first. */
    private List<String> explain(String sql, boolean dropPublishedIndex, boolean noSeqScan) throws Exception {
        List<String> plan = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                if (dropPublishedIndex) {
                    s.execute("drop index if exists ix_outbox_events_published_cleanup");
                }
                if (noSeqScan) {
                    s.execute("set local enable_seqscan = off");
                }
                try (ResultSet rs = s.executeQuery("explain (analyze, buffers) " + sql)) {
                    while (rs.next()) {
                        plan.add(rs.getString(1));
                    }
                }
            } finally {
                c.rollback();
            }
        }
        return plan;
    }

    @Test
    void severalCleanupInstancesRunningInLoopsNeverDeleteMoreThanExists() throws Exception {
        seedPublished(500, "40 days");
        int instances = 4;
        ExecutorService pool = pool(instances, "cleanup-");
        AtomicInteger total = new AtomicInteger();
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            runs.add(pool.submit(() -> {
                OutboxCleanup instance = new OutboxCleanup(store, transactionManager, new OutboxCleanupPolicy(true, 720, 50, 3_600_000L, 10));
                total.addAndGet(instance.runOnce());
            }));
        }
        for (Future<?> run : runs) {
            run.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // a run stops at a partial batch (rows another instance holds are skipped), so a few rows may be left for the next run;
        // what matters is that no row was deleted twice or lost: deleted + remaining == 500
        assertThat(total.get() + remaining()).as("every row is either deleted exactly once or still there").isEqualTo(500);
        assertThat(total.get()).isGreaterThan(0);
    }
}
