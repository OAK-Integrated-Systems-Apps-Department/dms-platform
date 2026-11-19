package com.oak.dms.outbox;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.messaging.RabbitMqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Binding;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBX-02 against a REAL RabbitMQ broker: the relay, wired with the production {@link RabbitMqConfig#rabbitTemplate}
 * (so it checks that config too), publishing to a real topic exchange.
 * <p>
 * Everything the mock-based tests assume about the broker is checked here: a confirm for a routed message, a return for an
 * unroutable one (and that it arrives on the same publish's correlation, ahead of the confirm), a nack, a missing exchange,
 * many publishes in flight, the message as consumers see it, and a refused connection followed by recovery.
 * <p>
 * <b>Disposable broker only.</b> The tests are skipped unless {@code OBX02_RABBIT_VHOST} names a virtual host starting with
 * {@code obx02} (a refusal, not a convention: a real vhost is never touched). In that vhost the tests declare the events
 * exchange and their own queues and delete them afterwards. Connection settings come from {@code OBX02_RABBIT_HOST} (default
 * 127.0.0.1), {@code OBX02_RABBIT_PORT} (default 5672), {@code OBX02_RABBIT_USER} and {@code OBX02_RABBIT_PASSWORD}.
 * <p>
 * Not covered, because it cannot be done safely or deterministically from a test: a confirmation that never arrives and a
 * broker that stops mid-batch (the relay's timeout path is exercised with controlled outcomes in
 * {@code OutboxRelayConfirmationTest}).
 */
@EnabledIfEnvironmentVariable(named = "OBX02_RABBIT_VHOST", matches = "obx0[23].*")
class OutboxRelayBrokerIT {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);

    private CachingConnectionFactory connectionFactory;
    private RabbitTemplate template;
    private RabbitAdmin admin;
    private final List<String> queues = new ArrayList<>();
    private TopicExchange exchange;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private CachingConnectionFactory factory(String host, int port) {
        com.rabbitmq.client.ConnectionFactory client = new com.rabbitmq.client.ConnectionFactory();
        client.setHost(host);
        client.setPort(port);
        client.setUsername(env("OBX02_RABBIT_USER", "guest"));
        client.setPassword(env("OBX02_RABBIT_PASSWORD", "guest"));
        client.setVirtualHost(System.getenv("OBX02_RABBIT_VHOST"));
        client.setConnectionTimeout(2000);
        // the production factory for the outbox publisher: confirms and returns on, channel RPCs bounded
        return RabbitMqConfig.publisherConnectionFactory(client, 10_000);
    }

    @BeforeEach
    void connect() {
        String vhost = System.getenv("OBX02_RABBIT_VHOST");
        assumeTrue(vhost != null && vhost.matches("obx0[23].*"), "only a disposable obx02*/obx03* virtual host is ever used");
        connectionFactory = factory(env("OBX02_RABBIT_HOST", "127.0.0.1"), Integer.parseInt(env("OBX02_RABBIT_PORT", "5672")));
        template = new RabbitMqConfig().rabbitTemplate(new RabbitMqConfig.OutboxPublisherConnection(connectionFactory), new Jackson2JsonMessageConverter());
        admin = new RabbitAdmin(connectionFactory);
        exchange = new TopicExchange(RabbitMqConfig.DMS_EVENTS_EXCHANGE, true, false);
    }

    @AfterEach
    void cleanUp() {
        if (admin != null) {
            queues.forEach(admin::deleteQueue);
            admin.deleteExchange(RabbitMqConfig.DMS_EVENTS_EXCHANGE);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private String boundQueue(String routingKey, QueueBuilder builder) {
        Queue queue = builder.build();
        admin.declareQueue(queue);
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(routingKey);
        admin.declareBinding(binding);
        queues.add(queue.getName());
        return queue.getName();
    }

    private String boundQueue(String routingKey) {
        return boundQueue(routingKey, QueueBuilder.durable("obx02.q." + UUID.randomUUID()));
    }

    private OutboxEvent event(String type) {
        return OutboxEvent.builder().outboxEventId(UUID.randomUUID()).tenantId(UUID.randomUUID()).aggregateType("test")
                .aggregateId(UUID.randomUUID()).eventType(type).payload("{\"k\":1}").build();
    }

    private void relay(RabbitTemplate with, long deadlineMs, OutboxEvent... events) {
        OutboxConfirms.stubClock(repository);
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of(events));
        new OutboxRelay(repository, with, audit, deadlineMs).relayPendingEvents();
    }

    @Test
    void aRoutedMessageIsConfirmedPublishedAndArrivesAsConsumersExpectIt() {
        admin.declareExchange(exchange);
        String key = "obx02.routed." + UUID.randomUUID();
        String queue = boundQueue(key);
        OutboxEvent e = event(key);

        relay(template, 5000, e);

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(e.getAttempts()).isZero();
        Message received = template.receive(queue, 5000);
        assertThat(received).as("the message reached the bound queue").isNotNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"k\":1}");
        assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(received.getMessageProperties().getReceivedRoutingKey()).isEqualTo(key);
        assertThat(received.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
        assertThat(received.getMessageProperties().getHeaders()).doesNotContainKey("__TypeId__");
    }

    @Test
    void anUnroutableMessageIsReturnedAndStaysPendingEvenThoughTheBrokerConfirmsIt() {
        admin.declareExchange(exchange);                              // the exchange exists; nothing is bound for this key
        OutboxEvent e = event("obx02.unbound." + UUID.randomUUID());

        relay(template, 5000, e);

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).contains("returned").contains("NO_ROUTE");
    }

    @Test
    void aMissingExchangeIsAFailureNotASuccess() {
        // the events exchange is deliberately not declared in this virtual host
        OutboxEvent e = event("obx02.noexchange." + UUID.randomUUID());

        relay(template, 3000, e);

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).isNotBlank();
    }

    @Test
    void aPublisherNackIsAFailure_reproducedWithAFullRejectPublishQueue() {
        admin.declareExchange(exchange);
        String key = "obx02.full." + UUID.randomUUID();
        boundQueue(key, QueueBuilder.durable("obx02.q." + UUID.randomUUID()).maxLength(1).overflow(QueueBuilder.Overflow.rejectPublish));
        OutboxEvent first = event(key);
        OutboxEvent second = event(key);

        relay(template, 5000, first, second);

        assertThat(first.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(second.getStatus()).as("the queue was full: the broker nacked the second publish").isEqualTo(OutboxEvent.Status.pending);
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).contains("negatively acknowledged");
    }

    @Test
    void manyPublishesInFlightAreEachConfirmedAndEachMessageArrivesOnce() {
        admin.declareExchange(exchange);
        String key = "obx02.many." + UUID.randomUUID();
        String queue = boundQueue(key);
        OutboxEvent[] events = new OutboxEvent[100];
        for (int i = 0; i < events.length; i++) {
            events[i] = event(key);
        }

        relay(template, 10_000, events);

        for (OutboxEvent e : events) {
            assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        }
        int received = 0;
        while (template.receive(queue, 1000) != null) {
            received++;
        }
        assertThat(received).isEqualTo(100);
    }

    @Test
    void aMixedBatchOfRoutedAndUnroutableMessagesSettlesEachCorrectly() {
        admin.declareExchange(exchange);
        String routed = "obx02.mixed.routed." + UUID.randomUUID();
        boundQueue(routed);
        OutboxEvent ok1 = event(routed);
        OutboxEvent lost = event("obx02.mixed.unbound." + UUID.randomUUID());
        OutboxEvent ok2 = event(routed);

        relay(template, 5000, ok1, lost, ok2);

        assertThat(ok1.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(lost.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(ok2.getStatus()).isEqualTo(OutboxEvent.Status.published);
        Set<OutboxEvent.Status> statuses = new HashSet<>(List.of(ok1.getStatus(), ok2.getStatus()));
        assertThat(statuses).containsExactly(OutboxEvent.Status.published);
    }

    @Test
    void aRefusedConnectionIsAFailure_andTheSameRowIsPublishedOnceTheBrokerIsReachable() {
        admin.declareExchange(exchange);
        String key = "obx02.recover." + UUID.randomUUID();
        String queue = boundQueue(key);
        OutboxEvent e = event(key);
        CachingConnectionFactory dead = factory("127.0.0.1", 1);      // nothing listens on port 1
        try {
            RabbitTemplate deadTemplate = new RabbitMqConfig().rabbitTemplate(new RabbitMqConfig.OutboxPublisherConnection(dead), new Jackson2JsonMessageConverter());

            relay(deadTemplate, 2000, e);

            assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
            assertThat(e.getAttempts()).isEqualTo(1);
        } finally {
            dead.destroy();
        }

        relay(template, 5000, e);                                     // the broker is reachable again

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(template.receive(queue, 5000)).isNotNull();
    }
}
