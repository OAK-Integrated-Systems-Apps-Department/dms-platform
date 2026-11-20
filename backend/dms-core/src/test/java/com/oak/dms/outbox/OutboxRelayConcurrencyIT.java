package com.oak.dms.outbox;

import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * OBX-01 against PostgreSQL: relay instances take turns over the pending outbox rows. The broker is a mock - the question
 * here is which relay publishes which row, not RabbitMQ - and every test coordinates its relays with latches rather than
 * sleeps, with bounded waits. "Relay instances" are separate threads, each on its own transaction and connection, which is
 * what two dms-core instances are to the database.
 * <p>
 * What this proves is that two relays never publish the same pending row at the same time. It does NOT prove exactly-once
 * delivery: {@link #aRollbackAfterThePublishLeavesTheRowsPendingSoTheyAreSentAgain} shows the at-least-once duplicate that
 * remains when a relay dies between the broker accepting a message and its commit.
 * <p>
 * The relay's own poll is pushed an hour out, so the tests run the relay by hand. Each test works on rows of its own
 * (event types with a unique prefix) after parking whatever else is pending - this suite runs against a throwaway
 * database, like the other integration tests.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
@TestPropertySource(properties = "dms.outbox.relay-interval-ms=3600000")
class OutboxRelayConcurrencyIT {

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
    private WorkflowFixtures fx;

    private JdbcTemplate jdbc;
    private UUID tenant;
    private String prefix;
    /** Routing keys of the messages sent for THIS test's rows, in send order. */
    private final ConcurrentLinkedQueue<String> sent = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        jdbc = fx.jdbc();
        tenant = fx.tenant();
        prefix = "obx01." + UUID.randomUUID().toString().substring(0, 8) + ".";
        // Park everything else that is pending (rows of earlier tests and runs) so a batch only ever holds this test's rows.
        jdbc.update("update outbox_events set status = 'failed' where status = 'pending'");
        reset(rabbitTemplate);
        OutboxConfirms.stubInvoke(rabbitTemplate);
        sent.clear();
        doAnswer(invocation -> {
            record(invocation.getArgument(1));
            return OutboxConfirms.ack(invocation);
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private void record(String routingKey) {
        if (routingKey.startsWith(prefix)) {
            sent.add(routingKey);
        }
    }

    /** Seeds {@code count} pending rows, oldest first; returns their event types in age order. */
    private List<String> seed(int count) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String type = prefix + String.format("%04d", i);
            types.add(type);
            jdbc.update("insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type,"
                            + " payload, status, created_at) values (?, ?, 'obx01', ?, ?, '{\"n\":1}'::jsonb, 'pending',"
                            + " now() - make_interval(secs => ?))",
                    UUID.randomUUID(), tenant, UUID.randomUUID(), type, (double) (count - i));
        }
        return types;
    }

    private String statusOf(String type) {
        return jdbc.queryForObject("select status from outbox_events where event_type = ?", String.class, type);
    }

    private int attemptsOf(String type) {
        return jdbc.queryForObject("select attempts from outbox_events where event_type = ?", Integer.class, type);
    }

    private long countWithStatus(String status) {
        return fx.count("select count(*) from outbox_events where event_type like ? and status = ?", prefix + "%", status);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static ExecutorService pool(int threads, String namePrefix) {
        int[] n = {0};
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, namePrefix + (char) ('A' + n[0]++));
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------ the selection itself

    @Test
    void twoTransactionsNeverSelectTheSameRows() throws Exception {
        List<String> types = seed(6);
        TransactionTemplate txA = new TransactionTemplate(transactionManager);
        TransactionTemplate txB = new TransactionTemplate(transactionManager);
        CountDownLatch aHasSelected = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = pool(1, "relay-");

        Future<List<String>> a = pool.submit(() -> txA.execute(s -> {
            List<String> mine = repository.findBatchToPublish(PageRequest.of(0, 3)).stream().map(OutboxEvent::getEventType).toList();
            aHasSelected.countDown();
            try {
                assertThat(releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return mine; // commits holding the locks until now
        }));
        assertThat(aHasSelected.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // B selects while A's transaction is open and its rows are locked
        List<String> b = txB.execute(s -> repository.findBatchToPublish(PageRequest.of(0, 3)).stream().map(OutboxEvent::getEventType).toList());
        releaseA.countDown();
        List<String> aRows = await(a);
        pool.shutdown();

        assertThat(aRows).containsExactlyElementsOf(types.subList(0, 3));          // the oldest three
        assertThat(b).containsExactlyElementsOf(types.subList(3, 6));              // B skipped A's locked rows, took the next three
        assertThat(new HashSet<>(aRows)).doesNotContainAnyElementsOf(b);
    }

    @Test
    void aRollbackReleasesTheLocksAndTheRowsAreSelectedAgain() throws Exception {
        List<String> types = seed(4);
        TransactionTemplate txA = new TransactionTemplate(transactionManager);
        TransactionTemplate txB = new TransactionTemplate(transactionManager);
        CountDownLatch aHasSelected = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = pool(1, "relay-");

        Future<?> a = pool.submit(() -> txA.execute(s -> {
            repository.findBatchToPublish(PageRequest.of(0, 4));
            aHasSelected.countDown();
            try {
                assertThat(releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            s.setRollbackOnly();
            return null;
        }));
        assertThat(aHasSelected.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        List<String> whileLocked = txB.execute(s -> repository.findBatchToPublish(PageRequest.of(0, 100)).stream()
                .map(OutboxEvent::getEventType).filter(t -> t.startsWith(prefix)).toList());
        releaseA.countDown();
        await(a);
        pool.shutdown();
        List<String> afterRollback = txB.execute(s -> repository.findBatchToPublish(PageRequest.of(0, 100)).stream()
                .map(OutboxEvent::getEventType).filter(t -> t.startsWith(prefix)).toList());

        assertThat(whileLocked).isEmpty();                              // A held all four
        assertThat(afterRollback).containsExactlyElementsOf(types);     // released, and still pending: retryable
        types.forEach(t -> assertThat(statusOf(t)).isEqualTo("pending"));
    }

    @Test
    void publishedAndFailedRowsAreNeverSelected_onlyPendingOnes() {
        List<String> types = seed(4);
        jdbc.update("update outbox_events set status = 'published', published_at = now() where event_type = ?", types.get(0));
        jdbc.update("update outbox_events set status = 'failed', attempts = 5 where event_type = ?", types.get(1));

        List<String> picked = new TransactionTemplate(transactionManager).execute(s -> repository.findBatchToPublish(PageRequest.of(0, 100))
                .stream().map(OutboxEvent::getEventType).filter(t -> t.startsWith(prefix)).toList());

        assertThat(picked).containsExactly(types.get(2), types.get(3));
    }

    @Test
    void theLockingSelectRefusesToRunOutsideATransaction() {
        seed(1);

        assertThatThrownBy(() -> repository.findBatchToPublish(PageRequest.of(0, 10))).isInstanceOf(IllegalTransactionStateException.class);
    }

    // ------------------------------------------------------------ the relay

    @Test
    void aSingleRelayPublishesAPendingRowAndMarksItPublished() {
        List<String> types = seed(3);

        relay.relayPendingEvents();

        assertThat(sent).containsExactlyElementsOf(types);
        types.forEach(t -> assertThat(statusOf(t)).isEqualTo("published"));
        assertThat(countWithStatus("pending")).isZero();
    }

    @Test
    void anEmptyBatchCompletesSafely() {
        relay.relayPendingEvents();

        assertThat(sent).isEmpty();
    }

    @Test
    void rowsGoOutOldestFirst() {
        List<String> types = seed(8);

        relay.relayPendingEvents();

        assertThat(List.copyOf(sent)).containsExactlyElementsOf(types);
    }

    @Test
    void theScheduledEntryPointIsATransactionalProxyAndThePublishRunsInsideItsTransaction() {
        seed(1);
        AtomicBoolean inTransaction = new AtomicBoolean();
        doAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            record(invocation.getArgument(1));
            return OutboxConfirms.ack(invocation);
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.relayPendingEvents();

        assertThat(AopUtils.isAopProxy(relay)).isTrue();
        assertThat(inTransaction.get()).as("the row locks live in the relay's own transaction").isTrue();
    }

    @Test
    void twoRelaysPublishDifferentRowsConcurrently_andNoRowTwice() throws Exception {
        List<String> types = seed(250);                       // the relay takes 100 per batch
        CountDownLatch aIsPublishing = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        doAnswer(invocation -> {
            record(invocation.getArgument(1));
            if (Thread.currentThread().getName().equals("relay-A")) {
                aIsPublishing.countDown();
                assertThat(releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();   // A stays mid-batch, holding its locks
            }
            return OutboxConfirms.ack(invocation);
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ExecutorService pool = pool(1, "relay-");

        Future<?> a = pool.submit(relay::relayPendingEvents);
        assertThat(aIsPublishing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        int sentWhileAIsBlocked;

        relay.relayPendingEvents();                           // B, on this thread, while A holds the oldest 100
        sentWhileAIsBlocked = sent.size();
        Set<String> bPublished = new HashSet<>(sent);

        releaseA.countDown();
        await(a);
        pool.shutdown();
        relay.relayPendingEvents();                           // C: the remaining 50

        // B, concurrently with A, took the NEXT hundred (A had published exactly one so far, then blocked)
        assertThat(sentWhileAIsBlocked).isEqualTo(101);
        assertThat(bPublished).containsAll(types.subList(100, 200));
        // overall: every row exactly once
        assertThat(sent).hasSize(250);
        assertThat(new HashSet<>(sent)).containsExactlyInAnyOrderElementsOf(types);
        assertThat(countWithStatus("published")).isEqualTo(250);
        assertThat(countWithStatus("pending")).isZero();
    }

    @Test
    void severalRelaysDrainAScatteredBacklogWithoutPublishingAnyRowTwice() throws Exception {
        List<String> types = seed(430);
        int relays = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = pool(relays, "relay-");
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < relays; i++) {
            futures.add(pool.submit(() -> {
                assertThat(start.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                for (int round = 0; round < 12 && countWithStatus("pending") > 0; round++) {
                    relay.relayPendingEvents();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            await(f);
        }
        pool.shutdown();
        relay.relayPendingEvents();                           // sweep anything left

        List<String> all = List.copyOf(sent);
        assertThat(all).hasSize(430);
        assertThat(new HashSet<>(all)).containsExactlyInAnyOrderElementsOf(types);
        assertThat(countWithStatus("published")).isEqualTo(430);
    }

    // ------------------------------------------------------------ failure paths

    @Test
    void aRollbackAfterThePublishLeavesTheRowsPendingSoTheyAreSentAgain() {
        List<String> types = seed(3);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        // the relay joins the surrounding transaction; rolling that back is what a crash between "broker accepted" and
        // "commit" amounts to for the database
        outer.execute(s -> {
            relay.relayPendingEvents();
            s.setRollbackOnly();
            return null;
        });
        assertThat(sent).containsExactlyElementsOf(types);                         // the broker was given all three ...
        types.forEach(t -> assertThat(statusOf(t)).isEqualTo("pending"));          // ... but the database still says pending

        relay.relayPendingEvents();                                                // the next poll publishes them again

        assertThat(sent).hasSize(6);                                               // AT-LEAST-ONCE: a duplicate per row
        assertThat(sent.stream().collect(Collectors.groupingBy(t -> t, Collectors.counting())).values()).containsOnly(2L);
        types.forEach(t -> assertThat(statusOf(t)).isEqualTo("published"));
    }

    @Test
    void aFailingPublisherSchedulesRetries_andFailsTheRowOnlyWhenItsRetryWindowIsOver_withoutAffectingOthers() {
        List<String> types = seed(3);
        String bad = types.get(1);
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            record(key);
            if (key.equals(bad)) {
                throw new IllegalStateException("broker refused");
            }
            return OutboxConfirms.ack(invocation);
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.relayPendingEvents();                           // 1st attempt: the other two are published
        assertThat(statusOf(types.get(0))).isEqualTo("published");
        assertThat(statusOf(types.get(2))).isEqualTo("published");
        assertThat(statusOf(bad)).isEqualTo("pending");
        assertThat(attemptsOf(bad)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_attempt_at > now() from outbox_events where event_type = ?", Boolean.class, bad))
                .as("its next attempt is scheduled").isTrue();

        relay.relayPendingEvents();                           // the very next poll: the retry is not due, the row is not selected
        assertThat(attemptsOf(bad)).isEqualTo(1);
        assertThat(sent.stream().filter(bad::equals).count()).isEqualTo(1);

        for (int i = 2; i <= 6; i++) {                        // the old limit was five attempts; six is fine now
            jdbc.update("update outbox_events set next_attempt_at = now() - interval '1 second' where event_type = ?", bad);
            relay.relayPendingEvents();
            assertThat(attemptsOf(bad)).isEqualTo(i);
            assertThat(statusOf(bad)).as("still inside the retry window").isEqualTo("pending");
        }

        jdbc.update("update outbox_events set first_failed_at = now() - interval '25 hours', next_attempt_at = now() - interval '1 second'"
                + " where event_type = ?", bad);
        relay.relayPendingEvents();                           // the window is over: this failure is the last
        assertThat(statusOf(bad)).isEqualTo("failed");
        assertThat(attemptsOf(bad)).isEqualTo(7);

        long sendsBefore = sent.stream().filter(bad::equals).count();
        relay.relayPendingEvents();                           // a failed row is never picked up again
        assertThat(sent.stream().filter(bad::equals).count()).isEqualTo(sendsBefore);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'OUTBOX_EVENT_PUBLISH_FAILED'", tenant))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentRelaysThatBothFailDoNotLoseOrDoubleAttemptCounts() throws Exception {
        List<String> types = seed(4);
        CountDownLatch aIsPublishing = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        doAnswer(invocation -> {
            record(invocation.getArgument(1));
            if (Thread.currentThread().getName().equals("relay-A")) {
                aIsPublishing.countDown();
                assertThat(releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }
            throw new IllegalStateException("broker down");
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ExecutorService pool = pool(1, "relay-");

        Future<?> a = pool.submit(relay::relayPendingEvents);
        assertThat(aIsPublishing.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        relay.relayPendingEvents();                           // B runs while A holds all four rows: it finds nothing to take
        releaseA.countDown();
        await(a);
        pool.shutdown();

        // only A ever tried each row, so each was sent once and counted once (before the fix both tried, and one count was lost)
        assertThat(sent).hasSize(4);
        types.forEach(t -> {
            assertThat(attemptsOf(t)).isEqualTo(1);
            assertThat(statusOf(t)).isEqualTo("pending");
        });
        assertThat(Collections.frequency(List.copyOf(sent), types.get(0))).isEqualTo(1);
    }
}
