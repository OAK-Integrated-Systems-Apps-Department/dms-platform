package com.oak.dms.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Entry point every module should use to announce a domain event. Because
 * this only ever does a plain JPA save() - never a direct RabbitMQ publish
 * - callers can call it in the middle of their own @Transactional method
 * and get exactly-once-in-the-database semantics for free: either the
 * business change and the outbox row both commit, or neither does.
 * Actual delivery to RabbitMQ happens later, asynchronously, via
 * {@link OutboxRelay}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publish(UUID tenantId, String aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = OutboxEvent.builder()
                    .tenantId(tenantId)
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(json)
                    .build();
            outboxEventRepository.save(event);
        } catch (Exception e) {
            // Serialization failures here are a programming error in the
            // caller's payload, not a transient infra issue - fail loudly
            // and let the enclosing transaction roll back rather than
            // silently dropping the event.
            throw new IllegalStateException("Failed to serialize outbox event payload for " + eventType, e);
        }
    }
}
