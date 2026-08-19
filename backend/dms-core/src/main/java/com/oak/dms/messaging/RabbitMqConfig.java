package com.oak.dms.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * dms-core only ever PUBLISHES onto this exchange - it does not declare or
 * bind any queues here. Each worker (backend/workers/*) owns and declares
 * its own queue(s) bound to this exchange with the routing-key patterns it
 * cares about (e.g. "document.*" for document-processing-worker, "user.*"
 * for integration-worker). Keeping queue ownership with the consumer avoids
 * dms-core needing to know about every downstream worker.
 */
@Configuration
public class RabbitMqConfig {

    public static final String DMS_EVENTS_EXCHANGE = "oak.dms.events";

    @Bean
    public TopicExchange dmsEventsExchange() {
        return new TopicExchange(DMS_EVENTS_EXCHANGE, /* durable */ true, /* autoDelete */ false);
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
