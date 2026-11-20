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

import java.util.ArrayList;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * OBX-02 against PostgreSQL: what ends up in {@code outbox_events} for each answer the broker can give, and that OBX-01's
 * protection still holds when the relay waits for confirms while holding its row locks. The broker is a controlled stand-in
 * (a mocked {@link RabbitTemplate} answering through the {@link CorrelationData}); real broker behaviour is
 * {@code OutboxRelayBrokerIT}. Relays are separate threads on separate transactions and connections, coordinated with
 * latches and bounded waits, never sleeps.
 * <p>
 * Needs PostgreSQL, like the other integration tests (a throwaway database; see the test README / DB_URL). The relay under test waits up to 20 s for
 * confirms (so the tests that hold confirms back are not racing the deadline); the timeout test builds a relay with a short
 * deadline of its own.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
@TestPropertySource(properties = {"dms.outbox.relay-interval-ms=3600000", "dms.outbox.confirm-timeout-ms=20000"})
class OutboxRelayConfirmationIT {

    private static final long WAIT_SECONDS = 30;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private com.oak.dms.audit.service.AuditService auditService;
    @Autowired
    private org.springframework.amqp.rabbit.connection.CachingConnectionFactory listenerConnectionFactory;
    @Autowired
    private com.oak.dms.messaging.RabbitMqConfig.OutboxPublisherConnection publisherConnection;
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
        prefix = "obx02." + UUID.randomUUID().toString().substring(0, 8) + ".";
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

    private List<String> seed(int count) {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String type = prefix + String.format("%04d", i);
            types.add(type);
            jdbc.update("insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type,"
                            + " payload, status, created_at) values (?, ?, 'obx02', ?, ?, '{\"n\":1}'::jsonb, 'pending',"
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

    private String lastErrorOf(String type) {
        return jdbc.queryForObject("select last_error from outbox_events where event_type = ?", String.class, type);
    }

    private static ExecutorService pool(int threads, String namePrefix) {
        int[] n = {0};
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, namePrefix + (char) ('A' + n[0]++));
            t.setDaemon(true);
            return t;
        });
    }

    @Test
    void onlyTheOutboxPublisherConnectionHasTheBoundedChannelRpcTimeout_theListenersConnectionKeepsTheClientDefault() {
        assertThat(publisherConnection.connectionFactory().getRabbitConnectionFactory().getChannelRpcTimeout()).isEqualTo(10_000);
        assertThat(listenerConnectionFactory.getRabbitConnectionFactory().getChannelRpcTimeout())
                .as("OCR and bulk-import consumers are not affected").isEqualTo(600_000);
        assertThat(listenerConnectionFactory.getRabbitConnectionFactory())
                .isNotSameAs(publisherConnection.connectionFactory().getRabbitConnectionFactory());
        assertThat(publisherConnection.connectionFactory().isPublisherConfirms()).isTrue();
        assertThat(publisherConnection.connectionFactory().isPublisherReturns()).isTrue();
    }

    @Test
    void aConfirmedPublishBecomesPublishedInTheDatabase() {
        String type = seed(1).get(0);

        relay.relayPendingEvents();

        assertThat(statusOf(type)).isEqualTo("published");
        assertThat(attemptsOf(type)).isZero();
        assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where event_type = ?", Boolean.class, type)).isTrue();
    }

    @Test
    void aNackedPublishStaysPendingWithOneAttemptAndTheReason() {
        String type = seed(1).get(0);
        broker(OutboxConfirms::nack);

        relay.relayPendingEvents();

        assertThat(statusOf(type)).isEqualTo("pending");
        assertThat(attemptsOf(type)).isEqualTo(1);
        assertThat(lastErrorOf(type)).contains("negatively acknowledged");
    }

    @Test
    void aReturnedMessageStaysPendingEvenThoughTheBrokerConfirmedIt() {
        String type = seed(1).get(0);
        broker(OutboxConfirms::returnedThenAck);

        relay.relayPendingEvents();

        assertThat(statusOf(type)).isEqualTo("pending");
        assertThat(attemptsOf(type)).isEqualTo(1);
        assertThat(lastErrorOf(type)).contains("unroutable");
    }

    @Test
    void aPublishThatTimesOutIsRetriedLaterAndIsNotFailedForGoodBeforeTheRetryWindowEnds() {
        List<String> types = seed(3);
        broker(OutboxConfirms::silence);
        OutboxRelay shortDeadline = new OutboxRelay(repository, rabbitTemplate, auditService, 300);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);   // the relay's own @Transactional needs the proxy; here the test supplies the transaction

        tx.executeWithoutResult(s -> shortDeadline.relayPendingEvents());
        for (String type : types) {
            assertThat(attemptsOf(type)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select next_attempt_at > now() from outbox_events where event_type = ?", Boolean.class, type))
                    .as("the retry is scheduled").isTrue();
        }
        tx.executeWithoutResult(s -> shortDeadline.relayPendingEvents());       // not due yet: nothing is selected
        for (String type : types) {
            assertThat(attemptsOf(type)).as("not retried before its time").isEqualTo(1);
        }

        for (int attempt = 2; attempt <= 6; attempt++) {                        // the old limit was five attempts
            jdbc.update("update outbox_events set next_attempt_at = now() - interval '1 second' where event_type like ?", prefix + "%");
            tx.executeWithoutResult(s -> shortDeadline.relayPendingEvents());
        }
        for (String type : types) {
            assertThat(statusOf(type)).as("six timed-out attempts, still inside the window").isEqualTo("pending");
            assertThat(attemptsOf(type)).isEqualTo(6);
        }

        jdbc.update("update outbox_events set first_failed_at = now() - interval '25 hours', next_attempt_at = now() - interval '1 second'"
                + " where event_type like ?", prefix + "%");
        tx.executeWithoutResult(s -> shortDeadline.relayPendingEvents());
        for (String type : types) {
            assertThat(statusOf(type)).as("older than the retry window: failed for good").isEqualTo("failed");
            assertThat(attemptsOf(type)).isEqualTo(7);
        }
    }

    @Test
    void aMixedBatchPersistsEachRowsOwnOutcome() {
        List<String> types = seed(4);
        broker(i -> {
            String key = OutboxConfirms.routingKey(i);
            if (key.endsWith("0001")) {
                return OutboxConfirms.nack(i);
            }
            if (key.endsWith("0002")) {
                return OutboxConfirms.returnedThenAck(i);
            }
            return OutboxConfirms.ack(i);
        });

        relay.relayPendingEvents();

        assertThat(statusOf(types.get(0))).isEqualTo("published");
        assertThat(statusOf(types.get(1))).isEqualTo("pending");
        assertThat(statusOf(types.get(2))).isEqualTo("pending");
        assertThat(statusOf(types.get(3))).isEqualTo("published");
        assertThat(attemptsOf(types.get(1))).isEqualTo(1);
        assertThat(attemptsOf(types.get(2))).isEqualTo(1);
    }

    @Test
    void aRollbackAfterAConfirmedPublishLeavesTheRowPendingSoItIsSentAgain() {
        String type = seed(1).get(0);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> {
            relay.relayPendingEvents();                       // confirmed and marked published inside the outer transaction
            status.setRollbackOnly();                         // ... which then rolls back
        });

        assertThat(statusOf(type)).as("nothing was committed").isEqualTo("pending");
        relay.relayPendingEvents();
        assertThat(statusOf(type)).isEqualTo("published");
        assertThat(sent.stream().filter(type::equals).count()).as("at-least-once: sent twice").isEqualTo(2);
    }

    @Test
    void aRelayWaitingForConfirmsStillHoldsItsLocks_soAnotherRelayTakesDifferentRowsAndNoRowIsPublishedTwice() throws Exception {
        List<String> types = seed(250);
        CountDownLatch aSentAll = new CountDownLatch(100);
        List<CorrelationData> aPending = new java.util.concurrent.CopyOnWriteArrayList<>();
        broker(i -> {
            if (Thread.currentThread().getName().equals("relay-A")) {
                aPending.add(OutboxConfirms.correlation(i));           // A's confirms are withheld
                aSentAll.countDown();
                return null;
            }
            return OutboxConfirms.ack(i);
        });
        ExecutorService pool = pool(1, "relay-");

        Future<?> a = pool.submit(() -> {
            relay.relayPendingEvents();                          // sends 100, then waits for confirms that have not come
        });
        assertThat(aSentAll.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        relay.relayPendingEvents();                              // B, on this thread, while A waits holding the oldest 100
        Set<String> sentByB = sent.stream().collect(Collectors.toCollection(HashSet::new));
        // release A's confirms now: it settles its own rows
        aPending.forEach(c -> c.getFuture().complete(new CorrelationData.Confirm(true, null)));
        a.get(WAIT_SECONDS, TimeUnit.SECONDS);
        pool.shutdownNow();

        List<String> allSent = new ArrayList<>(sent);
        assertThat(new HashSet<>(allSent)).as("no row was published by two relays").hasSameSizeAs(allSent);
        assertThat(sentByB).hasSize(200);                        // A's 100 plus B's 100 distinct rows
        assertThat(types.stream().filter(t -> "published".equals(statusOf(t))).count()).isEqualTo(200);
        assertThat(types.stream().filter(t -> "pending".equals(statusOf(t))).count()).isEqualTo(50);
    }

    @Test
    void concurrentRelaysThatAreBothNackedDoNotLoseOrDoubleAttemptCounts() throws Exception {
        List<String> types = seed(150);
        CountDownLatch aWaiting = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        broker(i -> {
            if (Thread.currentThread().getName().equals("relay-A")) {
                aWaiting.countDown();
                try {
                    assertThat(releaseA.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return OutboxConfirms.nack(i);
        });
        ExecutorService pool = pool(1, "relay-");

        Future<?> a = pool.submit(relay::relayPendingEvents);
        assertThat(aWaiting.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        relay.relayPendingEvents();                              // B takes the other 50 rows and gets nacks
        releaseA.countDown();
        a.get(WAIT_SECONDS, TimeUnit.SECONDS);
        pool.shutdownNow();

        for (String type : types) {
            assertThat(statusOf(type)).isEqualTo("pending");
            assertThat(attemptsOf(type)).as("exactly one attempt each: %s", type).isEqualTo(1);
        }
    }
}
