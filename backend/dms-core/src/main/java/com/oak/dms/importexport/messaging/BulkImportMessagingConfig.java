package com.oak.dms.importexport.messaging;

import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.service.impl.BulkImportServiceImpl;
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
 * Queue, dead-letter queue and listener container for {@code BulkImportWorker}.
 *
 * <p>This is the first in-process consumer in dms-core. {@code RabbitMqConfig}
 * describes dms-core as publish-only, with each worker under
 * backend/workers/* owning its own queues - but bulk import row validation
 * needs the metadata, user and schema services that live in this module,
 * and none of the worker deployables exist yet. So, until they do, the
 * consumer lives here and owns its queue here, the same way a worker would;
 * it can move to backend/workers/migration-worker (with this class) once
 * those services are reachable from there.
 *
 * <p>Delivery: {@code import_batch.submitted} events from the outbox land on
 * a durable queue. A failed delivery is retried in-process a few times with
 * backoff, then rejected without requeue so the broker dead-letters it to
 * the DLQ - never an endless redelivery loop. Prefetch is 1: a batch can
 * take minutes, and one consumer shouldn't sit on messages another could
 * be working on.
 */
@Configuration
public class BulkImportMessagingConfig {

    public static final String QUEUE = "oak.dms.importexport.bulk-import";
    public static final String DEAD_LETTER_EXCHANGE = QUEUE + ".dlx";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";
    public static final String ROUTING_KEY = BulkImportServiceImpl.SUBMITTED_EVENT_TYPE;
    public static final String LISTENER_CONTAINER_FACTORY = "bulkImportListenerContainerFactory";

    /** Retries after the first failed attempt, so 4 attempts in all. */
    private static final int MAX_RETRIES = 3;

    @Bean
    public Queue bulkImportQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Binding bulkImportBinding(Queue bulkImportQueue, TopicExchange dmsEventsExchange) {
        return BindingBuilder.bind(bulkImportQueue).to(dmsEventsExchange).with(ROUTING_KEY);
    }

    @Bean
    public DirectExchange bulkImportDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, /* durable */ true, /* autoDelete */ false);
    }

    @Bean
    public Queue bulkImportDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding bulkImportDeadLetterBinding(Queue bulkImportDeadLetterQueue,
                                               DirectExchange bulkImportDeadLetterExchange) {
        return BindingBuilder.bind(bulkImportDeadLetterQueue).to(bulkImportDeadLetterExchange).with(DEAD_LETTER_QUEUE);
    }

    /**
     * Built by hand rather than through Boot's configurer, so the global
     * spring.rabbitmq.listener.simple.* settings (including its retry
     * block) don't apply here - everything this queue relies on is set
     * explicitly below.
     */
    @Bean(LISTENER_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory bulkImportListenerContainerFactory(
            ConnectionFactory connectionFactory, ImportExportProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);
        int concurrency = Math.max(1, properties.getWorkerConcurrency());
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
