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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * OBX-03 against PostgreSQL: the retry time is persisted on the database clock, the batch query honours it (a row waiting for
 * its retry is not selected and does not hold a place ahead of rows that are ready), the order among due rows is deterministic,
 * and OBX-01's locking still makes relays take turns. The broker is a controlled stand-in ({@link OutboxConfirms}).
 * <p>
 * Needs PostgreSQL, like the other integration tests (a throwaway database). Every test works on rows of its own (event types
 * with a unique prefix) after parking whatever else is pending.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
@TestPropertySource(properties = "dms.outbox.relay-interval-ms=3600000")
class OutboxRetrySchedulingIT {

    private static final long WAIT_SECONDS = 30;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private WorkflowFixtures fx;

    private JdbcTemplate jdbc;
    private UUID tenant;
    private String prefix;
    private final ConcurrentLinkedQueue<String> sent = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        jdbc = fx.jdbc();
        tenant = fx.tenant();
        prefix = "obx03." + UUID.randomUUID().toString().substring(0, 8) + ".";
        jdbc.update("update outbox_events set status = 'failed' where status = 'pending'");
        reset(rabbitTemplate);
        OutboxConfirms.stubInvoke(rabbitTemplate);
        sent.clear();
        broker(OutboxConfirms::ack);
    }

    private void broker(org.mockito.stubbing.Answer<Object> answer) {
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            if (key.startsWith(prefix)) {
                sent.add(key);
            }
            return answer.answer(invocation);
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    /** Inserts one event; the two expressions are SQL on the database clock, e.g. {@code now() - interval '2 hours'}. */
    private String insert(String suffix, String status, String createdAtSql, String dueSql) {
        String type = prefix + suffix;
        jdbc.update("insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status,"
                        + " created_at, next_attempt_at) values (?, ?, 'obx03', ?, ?, '{\"n\":1}'::jsonb, ?, " + createdAtSql + ", " + dueSql + ")",
                UUID.randomUUID(), tenant, UUID.randomUUID(), type, status);
        return type;
    }

    private String statusOf(String type) {
        return jdbc.queryForObject("select status from outbox_events where event_type = ?", String.class, type);
    }

    private int attemptsOf(String type) {
        return jdbc.queryForObject("select attempts from outbox_events where event_type = ?", Integer.class, type);
    }

    private static ExecutorService pool(int threads, String namePrefix) {
        int[] n = {0};
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, namePrefix + (char) ('A' + n[0]++));
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------ the retry time is written, on the database clock

    @Test
    void aFailedPublishPersistsItsRetryTimeOnTheDatabaseClock_aboutTenSecondsAhead() {
        String type = insert("0001", "pending", "now()", "now()");
        broker(OutboxConfirms::nack);

        relay.relayPendingEvents();

        assertThat(statusOf(type)).isEqualTo("pending");
        assertThat(attemptsOf(type)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_attempt_at between now() + interval '7 seconds' and now() + interval '13 seconds'"
                + " from outbox_events where event_type = ?", Boolean.class, type))
                .as("10 s +-20% after the failure, on the database's clock").isTrue();
    }

    @Test
    void aNewEventSavedThroughTheEntityGetsTheDatabaseDefaultAndIsDueAtOnce() {
        UUID id = new TransactionTemplate(transactionManager).execute(status -> repository.save(OutboxEvent.builder()
                .tenantId(tenant).aggregateType("obx03").aggregateId(UUID.randomUUID()).eventType(prefix + "entity")
                .payload("{\"n\":1}").build()).getOutboxEventId());

        assertThat(jdbc.queryForObject("select next_attempt_at is not null and next_attempt_at <= now() from outbox_events"
                + " where outbox_event_id = ?", Boolean.class, id)).isTrue();
        relay.relayPendingEvents();
        assertThat(statusOf(prefix + "entity")).isEqualTo("published");
    }

    // ------------------------------------------------------------ eligibility

    @Test
    void aRowWaitingForItsRetryIsNotSelectedWhileTheRowsAroundItArePublished() {
        String ready1 = insert("0001", "pending", "now() - interval '3 seconds'", "now() - interval '3 seconds'");
        String waiting = insert("0002", "pending", "now() - interval '2 seconds'", "now() + interval '1 hour'");
        String ready2 = insert("0003", "pending", "now() - interval '1 second'", "now() - interval '1 second'");

        relay.relayPendingEvents();

        assertThat(statusOf(ready1)).isEqualTo("published");
        assertThat(statusOf(ready2)).isEqualTo("published");
        assertThat(statusOf(waiting)).isEqualTo("pending");
        assertThat(attemptsOf(waiting)).as("untouched").isZero();
        assertThat(sent).doesNotContain(waiting);
    }

    @Test
    void manyOldRowsWaitingForTheirRetryCannotStarveTheRowsThatAreReady() {
        for (int i = 0; i < 150; i++) {                                    // more than a whole batch of the OLDEST rows, all waiting
            insert(String.format("w%04d", i), "pending", "now() - interval '5 hours'", "now() + interval '1 hour'");
        }
        List<String> ready = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ready.add(insert(String.format("r%04d", i), "pending", "now()", "now()"));
        }

        relay.relayPendingEvents();

        ready.forEach(t -> assertThat(statusOf(t)).as(t).isEqualTo("published"));
        assertThat(sent).hasSize(10);
    }

    @Test
    void aFailedRowIsNeverSelectedEvenIfItsRetryTimeHasArrived() {
        String failed = insert("0001", "failed", "now() - interval '2 days'", "now() - interval '1 day'");
        String ready = insert("0002", "pending", "now()", "now()");

        relay.relayPendingEvents();

        assertThat(statusOf(failed)).isEqualTo("failed");
        assertThat(statusOf(ready)).isEqualTo("published");
        assertThat(sent).containsExactly(ready);
    }

    @Test
    void amongDueRowsTheOrderIsDueTimeThenCreationThenId() {
        String a = insert("a", "pending", "now() - interval '5 hours'", "now() - interval '1 hour'");
        String b = insert("b", "pending", "now() - interval '9 hours'", "now() - interval '2 hours'");
        String c = insert("c", "pending", "now() - interval '8 hours'", "now() - interval '2 hours'");   // same due time as b, created later
        String d = insert("d", "pending", "now() - interval '20 hours'", "now() - interval '30 minutes'");

        relay.relayPendingEvents();

        assertThat(List.copyOf(sent)).containsExactly(b, c, a, d);
    }

    @Test
    void aRowThatBecomesDueIsPickedUpOnTheNextPoll() {
        String type = insert("0001", "pending", "now()", "now() + interval '1 hour'");
        relay.relayPendingEvents();
        assertThat(statusOf(type)).isEqualTo("pending");

        jdbc.update("update outbox_events set next_attempt_at = now() - interval '1 second' where event_type = ?", type);
        relay.relayPendingEvents();

        assertThat(statusOf(type)).isEqualTo("published");
    }

    // ------------------------------------------------------------ retry window, rollback, backlog

    @Test
    void anEventWhoseFirstFailureIsOlderThanTheRetryWindowFailsOnItsNextFailureAndIsAudited() {
        String type = insert("0001", "pending", "now()", "now()");
        jdbc.update("update outbox_events set first_failed_at = now() - interval '25 hours', attempts = 30 where event_type = ?", type);
        broker(OutboxConfirms::nack);

        relay.relayPendingEvents();

        assertThat(statusOf(type)).isEqualTo("failed");
        assertThat(attemptsOf(type)).isEqualTo(31);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'OUTBOX_EVENT_PUBLISH_FAILED'"
                + " and resource_name = ?", tenant, type)).isEqualTo(1);
    }

    @Test
    void anEventThatWaitedDaysForTheRelayStillGetsTheWholeWindowAfterItsFirstFailure() {
        String type = insert("0001", "pending", "now() - interval '25 hours'", "now()");     // created 25 h ago, never attempted
        broker(OutboxConfirms::nack);

        relay.relayPendingEvents();

        assertThat(statusOf(type)).as("its window starts at the first failure").isEqualTo("pending");
        assertThat(attemptsOf(type)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select first_failed_at between now() - interval '1 minute' and now() + interval '1 minute'"
                + " from outbox_events where event_type = ?", Boolean.class, type)).isTrue();
        assertThat(jdbc.queryForObject("select created_at < now() - interval '24 hours' from outbox_events where event_type = ?",
                Boolean.class, type)).as("the creation time is preserved").isTrue();
    }

    @Test
    void theWindowOriginSurvivesLaterFailuresAndRestarts_itIsStoredInTheDatabase() {
        String type = insert("0001", "pending", "now()", "now()");
        broker(OutboxConfirms::nack);
        relay.relayPendingEvents();
        Timestamp origin = jdbc.queryForObject("select first_failed_at from outbox_events where event_type = ?", Timestamp.class, type);

        for (int i = 0; i < 3; i++) {                                                           // later failures (each a new transaction)
            jdbc.update("update outbox_events set next_attempt_at = now() - interval '1 second' where event_type = ?", type);
            relay.relayPendingEvents();
        }

        assertThat(attemptsOf(type)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select first_failed_at from outbox_events where event_type = ?", Timestamp.class, type))
                .isEqualTo(origin);
    }

    @Test
    void aPendingRowWithAttemptsFromBeforeTheColumnGetsItsOriginAtTheFirstFailureObserved() {
        String type = insert("0001", "pending", "now() - interval '3 days'", "now()");
        jdbc.update("update outbox_events set attempts = 3 where event_type = ?", type);       // first_failed_at stays NULL, like a migrated row
        broker(OutboxConfirms::nack);

        relay.relayPendingEvents();

        assertThat(statusOf(type)).isEqualTo("pending");
        assertThat(attemptsOf(type)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select first_failed_at is not null and first_failed_at >= now() - interval '1 minute'"
                + " from outbox_events where event_type = ?", Boolean.class, type)).isTrue();
    }

    @Test
    void aRollbackLeavesTheRetryStateExactlyAsItWas() {
        String type = insert("0001", "pending", "now()", "now() - interval '1 second'");
        Timestamp dueBefore = jdbc.queryForObject("select next_attempt_at from outbox_events where event_type = ?", Timestamp.class, type);
        broker(OutboxConfirms::nack);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            relay.relayPendingEvents();                                    // the failure and its schedule are written ...
            status.setRollbackOnly();                                      // ... and rolled back
        });

        assertThat(attemptsOf(type)).isZero();
        assertThat(jdbc.queryForObject("select next_attempt_at from outbox_events where event_type = ?", Timestamp.class, type))
                .isEqualTo(dueBefore);
        assertThat(statusOf(type)).isEqualTo("pending");
    }

    @Test
    void aLargeBacklogIsDrainedInBatchesOldestDueFirstWithNoRowSentTwice() {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            types.add(insert(String.format("%04d", i), "pending", "now() - interval '1 day'", "now() - make_interval(secs => " + (450 - i) + ")"));
        }

        for (int poll = 0; poll < 5; poll++) {                             // 100 per poll
            relay.relayPendingEvents();
        }

        assertThat(new HashSet<>(sent)).hasSize(450);
        assertThat(List.copyOf(sent)).containsExactlyElementsOf(types);
        types.forEach(t -> assertThat(statusOf(t)).isEqualTo("published"));
    }

    // ------------------------------------------------------------ OBX-01 still holds with the new predicate

    @Test
    void aRelayHoldingTheDueRowsMakesAnotherRelaySkipThem_andTheRowsNotDueStayUntouched() throws Exception {
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            due.add(insert("d" + i, "pending", "now() - interval '1 hour'", "now() - interval '1 minute'"));
        }
        List<String> notDue = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            notDue.add(insert("n" + i, "pending", "now() - interval '2 hours'", "now() + interval '1 hour'"));
        }
        CountDownLatch aIsPublishing = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        broker(invocation -> {
            if (Thread.currentThread().getName().equals("relay-A")) {
                aIsPublishing.countDown();
                assertThat(releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();     // A stays mid-batch, holding its locks
            }
            return OutboxConfirms.ack(invocation);
        });
        ExecutorService pool = pool(1, "relay-");

        Future<?> a = pool.submit(relay::relayPendingEvents);
        assertThat(aIsPublishing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        int sentBeforeB = sent.size();
        relay.relayPendingEvents();                                        // B finds every due row locked by A: nothing to do
        int sentByB = sent.size() - sentBeforeB;
        releaseA.countDown();
        a.get(WAIT_SECONDS, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(sentByB).as("B skipped the rows A holds and did not touch the rows that are not due").isZero();
        due.forEach(t -> assertThat(statusOf(t)).isEqualTo("published"));
        notDue.forEach(t -> {
            assertThat(statusOf(t)).isEqualTo("pending");
            assertThat(attemptsOf(t)).isZero();
        });
        assertThat(sent.stream().filter(due::contains).count()).as("each due row sent exactly once").isEqualTo(5);
    }

    // ------------------------------------------------------------ the index

    @Test
    void theBatchQueryIsServedByThePartialIndexAsAnOrderedScan_withNoSort() throws Exception {
        for (int i = 0; i < 3000; i++) {                                   // a table that is mostly published history
            insert(String.format("h%05d", i), "published", "now() - interval '3 days'", "now() - interval '3 days'");
        }
        for (int i = 0; i < 300; i++) {
            insert(String.format("p%05d", i), "pending", "now() - interval '1 hour'", "now() - make_interval(secs => " + (300 - i) + ")");
        }
        jdbc.execute("analyze outbox_events");
        String query = "select * from outbox_events where status = 'pending' and next_attempt_at <= now()"
                + " order by next_attempt_at asc, created_at asc, outbox_event_id asc limit 100 for update skip locked";

        List<String> forcedPlan = explain(query, true);
        List<String> defaultPlan = explain(query, false);

        System.out.println("OBX03-OBSERVE default plan:\n" + String.join("\n", defaultPlan));
        System.out.println("OBX03-OBSERVE forced-index plan:\n" + String.join("\n", forcedPlan));
        String forced = String.join("\n", forcedPlan);
        assertThat(forced).as("the partial index can serve the query").contains("ix_outbox_events_pending_due");
        assertThat(forced).as("in the order asked for, so nothing has to be sorted").doesNotContain("Sort");
        assertThat(forced).contains("LockRows");
    }

    @Test
    void onALargerDisposableTable_thePlanIndexUseRowsScannedTimingAndSizesAreMeasured() throws Exception {
        String cols = "(outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload, status, created_at, next_attempt_at, published_at)";
        jdbc.update("insert into outbox_events " + cols + " select gen_random_uuid(), ?, 'obx03', gen_random_uuid(), ? || 'hist' || g,"
                + " '{\"n\":1}'::jsonb, 'published', now() - interval '3 days', now() - interval '3 days', now() - interval '3 days'"
                + " from generate_series(1, 300000) g", tenant, prefix);
        jdbc.update("insert into outbox_events " + cols + " select gen_random_uuid(), ?, 'obx03', gen_random_uuid(), ? || 'due' || g,"
                + " '{\"n\":1}'::jsonb, 'pending', now() - interval '2 hours', now() - make_interval(secs => g), null"
                + " from generate_series(1, 5000) g", tenant, prefix);
        jdbc.update("insert into outbox_events " + cols + " select gen_random_uuid(), ?, 'obx03', gen_random_uuid(), ? || 'wait' || g,"
                + " '{\"n\":1}'::jsonb, 'pending', now() - interval '5 hours', now() + interval '1 hour', null"
                + " from generate_series(1, 2000) g", tenant, prefix);
        jdbc.execute("analyze outbox_events");
        String query = "select * from outbox_events where status = 'pending' and next_attempt_at <= now()"
                + " order by next_attempt_at asc, created_at asc, outbox_event_id asc limit 100 for update skip locked";

        List<String> chosen = explain(query, false);
        List<String> forced = explain(query, true);

        System.out.println("OBX03-OBSERVE large table: table=" + jdbc.queryForObject("select pg_size_pretty(pg_table_size('outbox_events'))", String.class)
                + " rows=" + jdbc.queryForObject("select count(*) from outbox_events", Long.class)
                + " partial_index=" + jdbc.queryForObject("select pg_size_pretty(pg_relation_size('ix_outbox_events_pending_due'))", String.class)
                + " old_index=" + jdbc.queryForObject("select pg_size_pretty(pg_relation_size('ix_outbox_events_status_created_at'))", String.class));
        System.out.println("OBX03-OBSERVE EXPLAIN (ANALYZE, BUFFERS), planner's own choice:\n" + String.join("\n", chosen));
        System.out.println("OBX03-OBSERVE EXPLAIN (ANALYZE, BUFFERS), sequential and bitmap scans disabled:\n" + String.join("\n", forced));
        assertThat(String.join("\n", forced)).contains("ix_outbox_events_pending_due").doesNotContain("Sort");
        jdbc.update("delete from outbox_events where event_type like ?", prefix + "%");
    }

    private List<String> explain(String sql, boolean forceIndex) throws Exception {
        List<String> plan = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                if (forceIndex) {
                    s.execute("set local enable_seqscan = off");
                    s.execute("set local enable_bitmapscan = off");
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
}
