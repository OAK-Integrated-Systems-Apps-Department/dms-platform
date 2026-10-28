package com.oak.dms.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
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

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setExchange(DMS_EVENTS_EXCHANGE);
        // Publisher confirms/returns are enabled via spring.rabbitmq.publisher-*
        // in application.yml; OutboxRelay relies on send() throwing on
        // failure rather than handling confirms asynchronously, which is
        // sufficient for the polling-relay pattern used here.
        return template;
    }
}
