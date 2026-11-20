package com.oak.dms.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.CachingConnectionFactoryConfigurer;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionFactoryBeanConfigurer;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * dms-core mostly PUBLISHES onto {@link #DMS_EVENTS_EXCHANGE} -- each worker
 * (backend/workers/*) owns and declares its own queue(s) bound to it, so
 * dms-core doesn't need to know about every downstream worker.
 * <p>
 * One deliberate exception (Phase 1 B.1+, see {@code com.oak.dms.ocrworker.OcrEventsConsumer}):
 * dms-core also CONSUMES {@code document.version.ocr.completed}/{@code .failed}
 * here, because applying OCR results onto {@code document_metadata_values}
 * ({@link com.oak.dms.document.DocumentMetadataService#applyOcrFields}) is a
 * write path only dms-core owns -- ocr-worker itself only ever writes
 * {@code document_ocr_field_results} directly (never {@code document_metadata_values}).
 * The dead-letter exchange/queue below mirror ocr-worker's own DLX
 * ownership convention (backend/workers/ocr-worker/src/ocr_worker/consumer.py)
 * -- both services independently declare the SAME shared DLX exchange
 * (idempotent re-declaration with matching properties is normal AMQP
 * practice, same as both already do for the main exchange), each with its
 * own distinct queue/routing key so they can never collide.
 */
@Configuration
public class RabbitMqConfig {

    public static final String DMS_EVENTS_EXCHANGE = "oak.dms.events";
    public static final String DMS_EVENTS_DLX = "oak.dms.events.dlx";

    public static final String QUEUE_OCR_EVENTS = "dms-core.q.ocr-events";
    public static final String QUEUE_OCR_EVENTS_DEAD_LETTER = "dms-core.q.ocr-events.dead-letter";

    public static final String ROUTING_KEY_OCR_COMPLETED = "document.version.ocr.completed";
    public static final String ROUTING_KEY_OCR_FAILED = "document.version.ocr.failed";
    public static final String ROUTING_KEY_OCR_EVENTS_DEAD_LETTER = "dms-core.ocr-events.dead-letter";

    @Bean
    public TopicExchange dmsEventsExchange() {
        return new TopicExchange(DMS_EVENTS_EXCHANGE, /* durable */ true, /* autoDelete */ false);
    }

    @Bean
    public TopicExchange dmsEventsDlx() {
        return new TopicExchange(DMS_EVENTS_DLX, /* durable */ true, /* autoDelete */ false);
    }

    @Bean
    public Queue ocrEventsDeadLetterQueue() {
        return QueueBuilder.durable(QUEUE_OCR_EVENTS_DEAD_LETTER).build();
    }

    @Bean
    public Binding ocrEventsDeadLetterBinding(Queue ocrEventsDeadLetterQueue, TopicExchange dmsEventsDlx) {
        return BindingBuilder.bind(ocrEventsDeadLetterQueue).to(dmsEventsDlx).with(ROUTING_KEY_OCR_EVENTS_DEAD_LETTER);
    }

    // spring.rabbitmq.listener.simple.retry (application.yml, already
    // configured, unused until now) handles LOCAL retries in-process; once
    // exhausted, Spring's default RejectAndDontRequeueRecoverer nacks with
    // requeue=false, which -- because this queue declares
    // x-dead-letter-exchange/x-dead-letter-routing-key below -- routes the
    // message here instead of losing it. No custom retry/recoverer bean needed.
    @Bean
    public Queue ocrEventsQueue() {
        return QueueBuilder.durable(QUEUE_OCR_EVENTS)
                .withArgument("x-dead-letter-exchange", DMS_EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_OCR_EVENTS_DEAD_LETTER)
                .build();
    }

    @Bean
    public Binding ocrEventsCompletedBinding(Queue ocrEventsQueue, TopicExchange dmsEventsExchange) {
        return BindingBuilder.bind(ocrEventsQueue).to(dmsEventsExchange).with(ROUTING_KEY_OCR_COMPLETED);
    }

    @Bean
    public Binding ocrEventsFailedBinding(Queue ocrEventsQueue, TopicExchange dmsEventsExchange) {
        return BindingBuilder.bind(ocrEventsQueue).to(dmsEventsExchange).with(ROUTING_KEY_OCR_FAILED);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * OBX-02: the connection the outbox relay publishes on - its OWN, separate from the one Spring Boot configures for
     * the listeners and the admin, so the one setting below affects the relay only.
     * <p>
     * Why it exists: a channel that still has unconfirmed publishes is not reused, so after a stalled batch the relay's
     * next batch has to open a new channel, and that is a broker round trip ({@code channel.open}) which the relay's
     * confirmation deadline does not cover. The Rabbit client waits for it for TEN MINUTES by default; against a paused
     * broker the relay was seen blocked for 130+ s (holding its transaction's row locks and a database connection).
     * {@code dms.outbox.channel-rpc-timeout-ms} (default 10 s) bounds exactly that wait, for this connection only. Setting
     * it on the shared client factory (the obvious {@code spring.rabbitmq.channel-rpc-timeout}) would also change the
     * OCR and bulk-import consumers' queue declarations, bindings and consumer startup, which is not wanted here.
     * <p>
     * It is built from the same Boot properties as the main connection (host, port, credentials, virtual host,
     * publisher-confirm-type, ...). Confirms and returns are forced on regardless, because the relay depends on them.
     * Not supported (refused at startup rather than silently ignored): {@code spring.rabbitmq.ssl.*}, whose bundle
     * support Boot keeps internal.
     */
    @Bean
    public OutboxPublisherConnection outboxPublisherConnection(
            RabbitConnectionFactoryBeanConfigurer beanConfigurer,
            CachingConnectionFactoryConfigurer connectionFactoryConfigurer,
            RabbitProperties properties,
            @Value("${dms.outbox.channel-rpc-timeout-ms:" + DEFAULT_PUBLISHER_CHANNEL_RPC_TIMEOUT_MS + "}") int channelRpcTimeoutMs)
            throws Exception {
        if (properties.getSsl().determineEnabled() || properties.getSsl().getBundle() != null) {
            throw new IllegalStateException("The outbox publisher connection does not support spring.rabbitmq.ssl.*;"
                    + " extend RabbitMqConfig.outboxPublisherConnection before enabling TLS to the broker");
        }
        RabbitConnectionFactoryBean bean = new RabbitConnectionFactoryBean();
        beanConfigurer.configure(bean);
        bean.afterPropertiesSet();
        CachingConnectionFactory factory = publisherConnectionFactory(bean.getObject(), channelRpcTimeoutMs);
        connectionFactoryConfigurer.configure(factory);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        factory.setConnectionNameStrategy(connectionFactory -> "dms-core-outbox-publisher");
        return new OutboxPublisherConnection(factory);
    }

    /** Default bound for opening a channel on the outbox publisher connection. */
    public static final int DEFAULT_PUBLISHER_CHANNEL_RPC_TIMEOUT_MS = 10_000;

    /**
     * The outbox publisher's factory around a Rabbit client factory: channel-open (and every other channel RPC) bounded
     * by {@code channelRpcTimeoutMs}; confirms and returns on. Kept separate so tests build the same thing.
     */
    public static CachingConnectionFactory publisherConnectionFactory(com.rabbitmq.client.ConnectionFactory clientFactory,
                                                                      int channelRpcTimeoutMs) {
        if (channelRpcTimeoutMs <= 0) {
            throw new IllegalArgumentException("dms.outbox.channel-rpc-timeout-ms must be positive");
        }
        clientFactory.setChannelRpcTimeout(channelRpcTimeoutMs);
        CachingConnectionFactory factory = new CachingConnectionFactory(clientFactory);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    /**
     * Holder for the outbox publisher's connection factory. It is deliberately NOT a {@code ConnectionFactory} bean:
     * a second one would make Spring Boot drop its own connection factory (used by the listeners).
     */
    public static final class OutboxPublisherConnection implements DisposableBean {
        private final CachingConnectionFactory connectionFactory;

        public OutboxPublisherConnection(CachingConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
        }

        public CachingConnectionFactory connectionFactory() {
            return connectionFactory;
        }

        @Override
        public void destroy() {
            connectionFactory.destroy();
        }
    }

    @Bean
    public RabbitTemplate rabbitTemplate(OutboxPublisherConnection publisherConnection,
                                          Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(publisherConnection.connectionFactory());
        template.setMessageConverter(messageConverter);
        template.setExchange(DMS_EVENTS_EXCHANGE);
        // OBX-02. This template publishes on the outbox's own connection (see outboxPublisherConnection), on which
        // confirms and returns are switched on. That alone changes nothing for a hand-built template: nothing waits for a confirm, and without
        // `mandatory` the broker silently drops a message that no queue is bound for. OutboxRelay publishes every
        // message with its own CorrelationData and reads the broker's confirm (and any return) from it, so only
        // `mandatory` is needed here. With it, an unroutable message is returned to the publisher - recorded on
        // that publish's CorrelationData before its confirm - instead of vanishing.
        template.setMandatory(true);
        // Diagnostic only: the relay decides from the CorrelationData, not from this callback. The payload is left
        // out of the log on purpose.
        template.setReturnsCallback(returned -> LoggerFactory.getLogger(RabbitMqConfig.class).warn(
                "RabbitMQ returned an unroutable message: exchange='{}', routingKey='{}', reply={} {}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
        return template;
    }
}
