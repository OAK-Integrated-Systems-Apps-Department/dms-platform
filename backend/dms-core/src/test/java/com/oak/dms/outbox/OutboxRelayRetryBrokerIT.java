package com.oak.dms.outbox;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.messaging.RabbitMqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBX-03 against a REAL RabbitMQ broker: what the retry schedule does when the broker is away and comes back, and when the
 * queue an event needs does not exist yet and appears later (a worker that starts after the event was created).
 * <p>
 * The outbox table is stood in for by an in-memory repository that applies the same rule as the SQL ("pending and due"); the SQL
 * itself is {@code OutboxRetrySchedulingIT}'s job. Time is moved by editing {@code nextAttemptAt}, never by sleeping.
 * <p>
 * Same safety gate as the other broker tests: only a disposable {@code obx02*}/{@code obx03*} virtual host is ever used, named
 * by {@code OBX02_RABBIT_VHOST}; connection settings come from {@code OBX02_RABBIT_HOST}, {@code OBX02_RABBIT_PORT},
 * {@code OBX02_RABBIT_USER} and {@code OBX02_RABBIT_PASSWORD}.
 */
@EnabledIfEnvironmentVariable(named = "OBX02_RABBIT_VHOST", matches = "obx0[23].*")
class OutboxRelayRetryBrokerIT {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
    private final List<OutboxEvent> table = new ArrayList<>();
    private final List<String> queues = new ArrayList<>();
    private CachingConnectionFactory live;
    private RabbitTemplate liveTemplate;
    private RabbitAdmin admin;
    private TopicExchange exchange;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private CachingConnectionFactory factory(String host, int port) {
        return factory(host, port, 10_000);
    }

    private CachingConnectionFactory factory(String host, int port, int channelRpcTimeoutMs) {
        com.rabbitmq.client.ConnectionFactory client = new com.rabbitmq.client.ConnectionFactory();
        client.setHost(host);
        client.setPort(port);
        client.setUsername(env("OBX02_RABBIT_USER", "guest"));
        client.setPassword(env("OBX02_RABBIT_PASSWORD", "guest"));
        client.setVirtualHost(System.getenv("OBX02_RABBIT_VHOST"));
        client.setConnectionTimeout(2000);
        return RabbitMqConfig.publisherConnectionFactory(client, channelRpcTimeoutMs);
    }

    private RabbitTemplate template(CachingConnectionFactory cf) {
        return new RabbitMqConfig().rabbitTemplate(new RabbitMqConfig.OutboxPublisherConnection(cf), new Jackson2JsonMessageConverter());
    }

    @BeforeEach
    void connect() {
        live = factory(env("OBX02_RABBIT_HOST", "127.0.0.1"), Integer.parseInt(env("OBX02_RABBIT_PORT", "5672")));
        liveTemplate = template(live);
        admin = new RabbitAdmin(live);
        exchange = new TopicExchange(RabbitMqConfig.DMS_EVENTS_EXCHANGE, true, false);
        // the in-memory outbox: pending rows whose retry time has come (or was never set), in due order
        when(repository.findBatchToPublish(any(Pageable.class))).thenAnswer(invocation -> {
            OffsetDateTime now = OffsetDateTime.now();
            return table.stream()
                    .filter(e -> e.getStatus() == OutboxEvent.Status.pending)
                    .filter(e -> e.getNextAttemptAt() == null || !e.getNextAttemptAt().isAfter(now))
                    .toList();
        });
        when(repository.databaseClockMillis()).thenAnswer(invocation -> System.currentTimeMillis());
    }

    @AfterEach
    void cleanUp() {
        queues.forEach(admin::deleteQueue);
        admin.deleteExchange(RabbitMqConfig.DMS_EVENTS_EXCHANGE);
        live.destroy();
    }

    private OutboxEvent event(String type) {
        OutboxEvent e = OutboxEvent.builder().outboxEventId(UUID.randomUUID()).tenantId(UUID.randomUUID()).aggregateType("test")
                .aggregateId(UUID.randomUUID()).eventType(type).payload("{\"k\":1}").build();
        table.add(e);
        return e;
    }

    private String bind(String key) {
        Queue q = QueueBuilder.durable("obx03.q." + UUID.randomUUID()).build();
        admin.declareQueue(q);
        admin.declareBinding(BindingBuilder.bind(q).to(exchange).with(key));
        queues.add(q.getName());
        return q.getName();
    }

    private void poll(RabbitTemplate with) {
        new OutboxRelay(repository, with, audit, 5000, OutboxRetryPolicy.defaults()).relayPendingEvents();
    }

    private static void timePasses(OutboxEvent e) {
        e.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
    }

    @Test
    void aBrokerThatIsAwayAndComesBack_theEventWaitsForItsRetryAndThenGoesOut_withoutBurningItsWindow() {
        admin.declareExchange(exchange);
        String key = "obx03.outage." + UUID.randomUUID();
        String queue = bind(key);
        OutboxEvent e = event(key);
        CachingConnectionFactory dead = factory("127.0.0.1", 1);                // nothing listens on port 1: the broker is "away"
        try {
            poll(template(dead));
        } finally {
            dead.destroy();
        }

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getNextAttemptAt()).as("scheduled ahead, not retried at the next poll").isAfter(OffsetDateTime.now());

        poll(liveTemplate);                                                      // the broker is back, but the retry is not due
        assertThat(e.getAttempts()).as("not tried before its time").isEqualTo(1);
        assertThat(liveTemplate.receive(queue, 500)).isNull();

        timePasses(e);
        poll(liveTemplate);

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(e.getAttempts()).as("one failed attempt, then success").isEqualTo(1);
        assertThat(liveTemplate.receive(queue, 5000)).isNotNull();
    }

    @Test
    void aQueueThatDoesNotExistYetIsRetriedWithDelay_andTheEventIsDeliveredOnceTheWorkerHasDeclaredIt() {
        admin.declareExchange(exchange);
        String key = "obx03.worker." + UUID.randomUUID();
        OutboxEvent e = event(key);                                              // the worker's queue is not there

        poll(liveTemplate);

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).contains("NO_ROUTE");
        assertThat(e.getNextAttemptAt()).isAfter(OffsetDateTime.now());

        String queue = bind(key);                                                // the worker starts and declares its queue
        timePasses(e);
        poll(liveTemplate);

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        Message received = liveTemplate.receive(queue, 5000);
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("{\"k\":1}");
    }

    @Test
    void aMissingExchangeIsRetriedWithDelay_andTheEventIsDeliveredOnceTheExchangeExists() {
        String key = "obx03.exchange." + UUID.randomUUID();                       // the events exchange is not declared yet
        OutboxEvent e = event(key);

        poll(liveTemplate);

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getNextAttemptAt()).isAfter(OffsetDateTime.now());

        admin.declareExchange(exchange);
        String queue = bind(key);
        timePasses(e);
        poll(new RabbitMqConfig().rabbitTemplate(new RabbitMqConfig.OutboxPublisherConnection(factory(env("OBX02_RABBIT_HOST", "127.0.0.1"),
                Integer.parseInt(env("OBX02_RABBIT_PORT", "5672")))), new Jackson2JsonMessageConverter()));   // a fresh channel: the old one was closed by the 404

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(liveTemplate.receive(queue, 5000)).isNotNull();
    }

    @Test
    void aBrokerThatNeverConfirms_theTimeoutIsScheduledForLater_notFiveRapidAttempts_andRecoveryDelivers() throws Exception {
        String dir = System.getenv("OBX02_STALL_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null && !dir.isBlank(), "needs the external pause driver");
        java.nio.file.Path signals = java.nio.file.Path.of(dir);
        admin.declareExchange(exchange);
        String key = "obx03.stall." + UUID.randomUUID();
        String queue = bind(key);
        CachingConnectionFactory quick = factory(env("OBX02_RABBIT_HOST", "127.0.0.1"), Integer.parseInt(env("OBX02_RABBIT_PORT", "5672")), 3000);
        RabbitTemplate quickTemplate = template(quick);
        try {
            OutboxEvent warm = event(key);
            new OutboxRelay(repository, quickTemplate, audit, 1000, OutboxRetryPolicy.defaults()).relayPendingEvents();     // channel + confirm mode
            assertThat(warm.getStatus()).isEqualTo(OutboxEvent.Status.published);
            java.nio.file.Files.writeString(signals.resolve("READY_C"), "1");
            waitFor(signals.resolve("SUSPENDED_C"), 120);

            OutboxEvent e = event(key);
            OutboxRelay relay = new OutboxRelay(repository, quickTemplate, audit, 1000, OutboxRetryPolicy.defaults());
            relay.relayPendingEvents();                                          // the broker never confirms: a timeout
            assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
            assertThat(e.getAttempts()).isEqualTo(1);
            assertThat(e.getLastError()).contains("no broker confirmation");
            assertThat(e.getNextAttemptAt()).isAfter(OffsetDateTime.now().plusSeconds(6));

            for (int i = 0; i < 5; i++) {                                        // the next five polls, two seconds apart in real life
                relay.relayPendingEvents();
            }
            assertThat(e.getAttempts()).as("the long outage does not cause five rapid attempts: the retry is not due").isEqualTo(1);

            timePasses(e);
            relay.relayPendingEvents();                                          // its time has come: a second timeout / failure
            assertThat(e.getAttempts()).isEqualTo(2);
            assertThat(e.getNextAttemptAt()).as("the backoff has grown (20 s +-20%)").isAfter(OffsetDateTime.now().plusSeconds(14));
            assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        } finally {
            java.nio.file.Files.writeString(signals.resolve("DONE_C"), "1");
        }
        waitFor(signals.resolve("RESUMED_C"), 120);

        OutboxEvent stalled = table.get(table.size() - 1);
        timePasses(stalled);
        new OutboxRelay(repository, quickTemplate, audit, 5000, OutboxRetryPolicy.defaults()).relayPendingEvents();

        assertThat(stalled.getStatus()).as("delivered once the broker answers again").isEqualTo(OutboxEvent.Status.published);
        assertThat(stalled.getAttempts()).isEqualTo(2);
        int received = 0;
        while (liveTemplate.receive(queue, 1000) != null) {
            received++;
        }
        System.out.println("OBX03-OBSERVE after the stall the queue holds " + received + " messages (1 warm-up + the event, delivered at least once)");
        assertThat(received).isGreaterThanOrEqualTo(2);
        quick.destroy();
    }

    private static void waitFor(java.nio.file.Path file, int seconds) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        while (!java.nio.file.Files.exists(file)) {
            if (System.nanoTime() > until) {
                throw new AssertionError("driver signal never arrived: " + file);
            }
            Thread.sleep(100);
        }
    }

    @Test
    void atLeastOnce_anEventSentAgainAfterARollbackArrivesTwice() {
        admin.declareExchange(exchange);
        String key = "obx03.dup." + UUID.randomUUID();
        String queue = bind(key);
        OutboxEvent e = event(key);

        poll(liveTemplate);
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        e.setStatus(OutboxEvent.Status.pending);                                 // what a rollback after the confirm leaves behind
        e.setPublishedAt(null);
        poll(liveTemplate);

        int received = 0;
        while (liveTemplate.receive(queue, 1000) != null) {
            received++;
        }
        assertThat(received).as("a duplicate: delivery is at-least-once").isEqualTo(2);
    }
}
