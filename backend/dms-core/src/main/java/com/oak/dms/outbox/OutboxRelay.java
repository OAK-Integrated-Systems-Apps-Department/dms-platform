package com.oak.dms.outbox;

import com.oak.dms.messaging.RabbitMqConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Polls PENDING outbox rows on a fixed interval and forwards each to the
 * RabbitMQ topic exchange, routed by event type (e.g. "user.created").
 * A polling relay (vs. e.g. Debezium/CDC) is the simplest thing that
 * satisfies the outbox pattern for this stage of the platform; swap it
 * for log-based CDC later without touching any producer code; every
 * module still just calls {@link OutboxEventPublisher}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelayString = "${dms.outbox.relay-interval-ms:2000}")
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxEventRepository.findBatchToPublish(PageRequest.of(0, BATCH_SIZE));
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.DMS_EVENTS_EXCHANGE,
                    event.getEventType(),
                    event.getPayload());
            event.setStatus(OutboxEvent.Status.published);
            event.setPublishedAt(OffsetDateTime.now());
        } catch (Exception e) {
            event.setAttempts(event.getAttempts() + 1);
            event.setLastError(e.getMessage());
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                event.setStatus(OutboxEvent.Status.failed);
                log.error("Outbox event {} permanently failed after {} attempts: {}",
                        event.getOutboxEventId(), event.getAttempts(), e.getMessage());
            } else {
                log.warn("Outbox event {} failed to publish (attempt {}/{}): {}",
                        event.getOutboxEventId(), event.getAttempts(), MAX_ATTEMPTS, e.getMessage());
            }
        } finally {
            outboxEventRepository.save(event);
        }
    }
}
