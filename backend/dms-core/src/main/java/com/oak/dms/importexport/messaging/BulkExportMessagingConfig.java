package com.oak.dms.importexport.messaging;

import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.service.impl.BulkExportServiceImpl;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Queue, dead-letter queue and listener container for {@code BulkExportWorker}
 * - the same arrangement as {@link BulkImportMessagingConfig}, and in-process
 * here for the same reason: building an export needs the document, metadata
 * and storage services of this module. It can move to
 * backend/workers/export-worker (with this class) once those are reachable
 * from there.
 *
 * <p>Delivery: {@code export_job.requested} events from the outbox land on
 * a durable queue. A failed delivery (the database, or a Keycloak role
 * lookup while re-checking the requester) is retried in-process a few times
 * with backoff, then rejected without requeue so the broker dead-letters it
 * to the DLQ. Prefetch is 1: an export can take minutes.
 */
@Configuration
public class BulkExportMessagingConfig {

    public static final String QUEUE = "oak.dms.importexport.bulk-export";
    public static final String DEAD_LETTER_EXCHANGE = QUEUE + ".dlx";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";
    public static final String ROUTING_KEY = BulkExportServiceImpl.REQUESTED_EVENT_TYPE;
    public static final String LISTENER_CONTAINER_FACTORY = "bulkExportListenerContainerFactory";

    /** Retries after the first failed attempt, so 4 attempts in all. */
    private static final int MAX_RETRIES = 3;

    @Bean
    public Queue bulkExportQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Binding bulkExportBinding(Queue bulkExportQueue, TopicExchange dmsEventsExchange) {
        return BindingBuilder.bind(bulkExportQueue).to(dmsEventsExchange).with(ROUTING_KEY);
    }

    @Bean
    public DirectExchange bulkExportDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, /* durable */ true, /* autoDelete */ false);
    }

    @Bean
    public Queue bulkExportDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding bulkExportDeadLetterBinding(Queue bulkExportDeadLetterQueue,
                                               DirectExchange bulkExportDeadLetterExchange) {
        return BindingBuilder.bind(bulkExportDeadLetterQueue).to(bulkExportDeadLetterExchange).with(DEAD_LETTER_QUEUE);
    }

    /** Built by hand, like {@link BulkImportMessagingConfig}'s, so no global listener setting applies. */
    @Bean(LISTENER_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory bulkExportListenerContainerFactory(
            ConnectionFactory connectionFactory, ImportExportProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);
        int concurrency = Math.max(1, properties.getExportWorkerConcurrency());
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        // A rejected message goes to the DLQ, never back onto the queue.
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .configureRetryPolicy(policy -> policy
                        .maxRetries(MAX_RETRIES)
                        .delay(Duration.ofSeconds(1))
                        .multiplier(2)
                        .maxDelay(Duration.ofSeconds(10))
                        // A malformed message won't get better on retry.
                        .predicate(e -> !causedBy(e, AmqpRejectAndDontRequeueException.class)))
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
