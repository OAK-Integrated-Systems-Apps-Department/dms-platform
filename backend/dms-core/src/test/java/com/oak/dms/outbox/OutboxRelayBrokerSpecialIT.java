package com.oak.dms.outbox;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.messaging.RabbitMqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBX-02 against a real broker, the cases the approved {@code OutboxRelayBrokerIT} does not cover: that a return is recorded
 * on the publish's correlation BEFORE its confirm, what happens to an unroutable {@code user.created} event (five attempts,
 * then failed), and - with the broker process paused from outside - a confirmation that really never arrives: the batch
 * deadline, late confirmations after the broker resumes, and what the template keeps while confirms are outstanding.
 * <p>
 * Same safety gate as {@code OutboxRelayBrokerIT}: only a disposable {@code obx02*} virtual host is ever used. The pause test
 * additionally needs {@code OBX02_STALL_DIR}: it writes {@code READY} there, waits for the driver to pause the broker and write
 * {@code SUSPENDED}, runs, writes {@code DONE}, and waits for {@code RESUMED}.
 */
@EnabledIfEnvironmentVariable(named = "OBX02_RABBIT_VHOST", matches = "obx0[23].*")
class OutboxRelayBrokerSpecialIT {

    private static final int CHANNEL_RPC_TIMEOUT_MS = 4000;
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
    private CachingConnectionFactory connectionFactory;
    private RabbitTemplate template;
    private RabbitAdmin admin;
    private final List<String> queues = new ArrayList<>();
    private TopicExchange exchange;

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    @BeforeEach
    void connect() {
        com.rabbitmq.client.ConnectionFactory client = new com.rabbitmq.client.ConnectionFactory();
        client.setHost(env("OBX02_RABBIT_HOST", "127.0.0.1"));
        client.setPort(Integer.parseInt(env("OBX02_RABBIT_PORT", "5672")));
        client.setUsername(env("OBX02_RABBIT_USER", "guest"));
        client.setPassword(env("OBX02_RABBIT_PASSWORD", "guest"));
        client.setVirtualHost(System.getenv("OBX02_RABBIT_VHOST"));
        // the production publisher factory, with the channel-open bound shortened so this test is quick (production: 10 s)
        connectionFactory = RabbitMqConfig.publisherConnectionFactory(client, CHANNEL_RPC_TIMEOUT_MS);
        template = new RabbitMqConfig().rabbitTemplate(new RabbitMqConfig.OutboxPublisherConnection(connectionFactory), new Jackson2JsonMessageConverter());
        admin = new RabbitAdmin(connectionFactory);
        exchange = new TopicExchange(RabbitMqConfig.DMS_EVENTS_EXCHANGE, true, false);
        admin.declareExchange(exchange);
    }

    @AfterEach
    void cleanUp() {
        queues.forEach(admin::deleteQueue);
        admin.deleteExchange(RabbitMqConfig.DMS_EVENTS_EXCHANGE);
        connectionFactory.destroy();
    }

    private String boundQueue(String key) {
        Queue q = QueueBuilder.durable("obx02.q." + UUID.randomUUID()).build();
        admin.declareQueue(q);
        admin.declareBinding(BindingBuilder.bind(q).to(exchange).with(key));
        queues.add(q.getName());
        return q.getName();
    }

    private OutboxEvent event(String type) {
        return OutboxEvent.builder().outboxEventId(UUID.randomUUID()).tenantId(UUID.randomUUID()).aggregateType("test")
                .aggregateId(UUID.randomUUID()).eventType(type).payload("{\"k\":1}").build();
    }

    private void relay(long deadlineMs, List<OutboxEvent> events) {
        OutboxConfirms.stubClock(repository);
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(events);
        new OutboxRelay(repository, template, audit, deadlineMs).relayPendingEvents();
    }

    private static Message message() {
        MessageProperties p = new MessageProperties();
        p.setContentType("application/json");
        return new Message("{\"k\":1}".getBytes(StandardCharsets.UTF_8), p);
    }

    @Test
    void aReturnIsRecordedOnThePublishesCorrelationBeforeItsConfirmIsDelivered() throws Exception {
        List<CorrelationData> correlations = new ArrayList<>();
        AtomicInteger returnedAtConfirm = new AtomicInteger();
        AtomicInteger notYetReturnedAtConfirm = new AtomicInteger();
        for (int i = 0; i < 300; i++) {
            CorrelationData cd = new CorrelationData("order." + UUID.randomUUID());
            correlations.add(cd);
            template.send(RabbitMqConfig.DMS_EVENTS_EXCHANGE, "obx02.nobody." + i, message(), cd);
            // runs on the thread that completes the confirm, at that moment (if the confirm is not already there)
            if (!cd.getFuture().isDone()) {
                cd.getFuture().thenAccept(c -> {
                    if (cd.getReturned() != null) {
                        returnedAtConfirm.incrementAndGet();
                    } else {
                        notYetReturnedAtConfirm.incrementAndGet();
                    }
                });
            }
        }
        for (CorrelationData cd : correlations) {
            CorrelationData.Confirm confirm = cd.getFuture().get(10, TimeUnit.SECONDS);
            assertThat(confirm.isAck()).as("the broker confirms even an unroutable publish").isTrue();
            assertThat(cd.getReturned()).as("and it was returned on this very correlation").isNotNull();
            assertThat(cd.getReturned().getRoutingKey()).startsWith("obx02.nobody.");
        }
        System.out.println("OBX02-OBSERVE return-before-confirm: checkedAtConfirm=" + (returnedAtConfirm.get() + notYetReturnedAtConfirm.get())
                + " returnedAlready=" + returnedAtConfirm.get() + " notYetReturned=" + notYetReturnedAtConfirm.get());
        assertThat(notYetReturnedAtConfirm.get()).as("no confirm was delivered before its return was recorded").isZero();
    }

    @Test
    void anUnroutableUserCreatedEventIsReturnedEveryTime_scheduledForRetry_andFailsOnlyWhenTheRetryWindowIsOver() {
        // no queue is bound for user.created (nor in this disposable vhost, nor anywhere in the repository)
        OutboxEvent e = event("user.created");

        for (int attempt = 1; attempt <= 6; attempt++) {            // (the repository here is a mock: it hands the event back each time)
            relay(5000, List.of(e));
            assertThat(e.getLastError()).contains("NO_ROUTE").contains("user.created");
            assertThat(e.getAttempts()).isEqualTo(attempt);
            assertThat(e.getStatus()).as("still inside the retry window").isEqualTo(OutboxEvent.Status.pending);
            assertThat(e.getNextAttemptAt()).as("scheduled, not retried at once").isNotNull();
        }

        e.setFirstFailedAt(java.time.OffsetDateTime.now().minusHours(25));
        relay(5000, List.of(e));

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.failed);
    }

    @Test
    void aBrokerThatNeverConfirms_theBatchDeadline_lateConfirmations_andOutstandingCorrelations() throws Exception {
        String dir = System.getenv("OBX02_STALL_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null && !dir.isBlank(), "needs the external pause driver");
        Path signals = Path.of(dir);
        String key = "obx02.stall." + UUID.randomUUID();
        String queue = boundQueue(key);

        OutboxEvent warm = event(key);
        relay(5000, List.of(warm));                                          // channel and confirm mode are established
        assertThat(warm.getStatus()).isEqualTo(OutboxEvent.Status.published);
        Files.writeString(signals.resolve("READY"), "1");
        waitFor(signals.resolve("SUSPENDED"), 120);

        List<OutboxEvent> stalled = new ArrayList<>();
        long deadline = 2000;
        for (int round = 1; round <= 3; round++) {
            List<OutboxEvent> batch = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                batch.add(event(key));
            }
            long started = System.nanoTime();
            relay(deadline, batch);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.println("OBX02-OBSERVE stall round " + round + ": 20 unconfirmed sends took " + elapsedMs + " ms (deadline "
                    + deadline + " ms); unconfirmed correlations held by the template: " + template.getUnconfirmedCount());
            assertThat(elapsedMs).as("bounded: one deadline for the batch (not 20 x %d ms) plus at most one channel-open timeout", deadline)
                    .isLessThan(deadline + CHANNEL_RPC_TIMEOUT_MS + 3000);
            for (OutboxEvent e : batch) {
                assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
                assertThat(e.getAttempts()).isEqualTo(1);
                assertThat(e.getLastError()).matches("(?s).*(no broker confirmation|publishing failed).*");
            }
            stalled.addAll(batch);
        }
        int outstandingDuringStall = template.getUnconfirmedCount();
        Files.writeString(signals.resolve("DONE"), "1");
        waitFor(signals.resolve("RESUMED"), 120);

        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (template.getUnconfirmedCount() > 0 && System.nanoTime() < waitUntil) {
            Thread.sleep(100);
        }
        System.out.println("OBX02-OBSERVE after resume: outstanding during stall=" + outstandingDuringStall
                + ", after resume=" + template.getUnconfirmedCount());
        for (OutboxEvent e : stalled) {
            assertThat(e.getStatus()).as("a late confirmation for an abandoned attempt changes nothing").isEqualTo(OutboxEvent.Status.pending);
            assertThat(e.getAttempts()).isEqualTo(1);
        }

        relay(5000, stalled);                                                // the retry, now that the broker answers

        for (OutboxEvent e : stalled) {
            assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
            assertThat(e.getAttempts()).as("the retry succeeded; the earlier timeout was counted once").isEqualTo(1);
        }
        int received = 1;                                                    // the warm-up message
        while (template.receive(queue, 1000) != null) {
            received++;
        }
        System.out.println("OBX02-OBSERVE queue holds " + received + " messages: 1 warm-up + the first stalled batch (20, written to the socket"
                + " before the broker was paused, delivered on resume) + 60 retries (the 40 events of the later rounds never reached the broker)");
        assertThat(received).as("at-least-once: the first batch was delivered on resume AND sent again by the retry").isGreaterThanOrEqualTo(1 + 20 + 60);
    }

    @Test
    void aBrokerThatIsAlreadyFrozenWhenTheRelayFirstConnects_howLongTheBatchTakesAndWhatItDoesToTheRows() throws Exception {
        String dir = System.getenv("OBX02_STALL_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null && !dir.isBlank(), "needs the external pause driver");
        Path signals = Path.of(dir);
        // a fresh connection factory: no connection and no channel exist yet, so the relay has to connect (TCP + AMQP handshake)
        // and open a channel against a broker that accepts TCP but never answers
        connectionFactory.destroy();
        com.rabbitmq.client.ConnectionFactory client = new com.rabbitmq.client.ConnectionFactory();
        client.setHost(env("OBX02_RABBIT_HOST", "127.0.0.1"));
        client.setPort(Integer.parseInt(env("OBX02_RABBIT_PORT", "5672")));
        client.setUsername(env("OBX02_RABBIT_USER", "guest"));
        client.setPassword(env("OBX02_RABBIT_PASSWORD", "guest"));
        client.setVirtualHost(System.getenv("OBX02_RABBIT_VHOST"));
        CachingConnectionFactory cold = RabbitMqConfig.publisherConnectionFactory(client, CHANNEL_RPC_TIMEOUT_MS);
        RabbitTemplate coldTemplate = new RabbitMqConfig().rabbitTemplate(new RabbitMqConfig.OutboxPublisherConnection(cold),
                new Jackson2JsonMessageConverter());
        Files.writeString(signals.resolve("READY_B"), "1");
        waitFor(signals.resolve("SUSPENDED_B"), 120);
        List<OutboxEvent> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(event("obx02.cold." + UUID.randomUUID()));
        }
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(batch);
        long started = System.nanoTime();
        try {
            new OutboxRelay(repository, coldTemplate, audit, 2000).relayPendingEvents();
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.println("OBX02-OBSERVE cold connect against a frozen broker: batch of 20 took " + elapsedMs
                    + " ms (confirm deadline 2000 ms, channel-open bound " + CHANNEL_RPC_TIMEOUT_MS + " ms, client handshake bound 10000 ms, TCP connect bound 60000 ms)");
            Files.writeString(signals.resolve("DONE_B"), "1");
            waitFor(signals.resolve("RESUMED_B"), 120);
            cold.destroy();
        }
        for (OutboxEvent e : batch) {
            assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
            assertThat(e.getAttempts()).isEqualTo(1);
            assertThat(e.getLastError()).isNotBlank();
        }
    }

    private static void waitFor(Path file, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!Files.exists(file)) {
            if (System.nanoTime() > until) {
                throw new AssertionError("driver signal never arrived: " + file);
            }
            Thread.sleep(100);
        }
    }
}
