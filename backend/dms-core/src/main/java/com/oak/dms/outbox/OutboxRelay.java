package com.oak.dms.outbox;

import com.oak.dms.audit.AuditActionCatalog;
import com.oak.dms.audit.AuditObjectTypes;
import com.oak.dms.audit.AuditOutcome;
import com.oak.dms.audit.AuditService;
import com.oak.dms.messaging.RabbitMqConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Polls PENDING outbox rows on a fixed interval and forwards each to the
 * RabbitMQ topic exchange, routed by event type (e.g. "user.created").
 * A polling relay (vs. e.g. Debezium/CDC) is the simplest thing that
 * satisfies the outbox pattern for this stage of the platform; swap it
 * for log-based CDC later without touching any producer code; every
 * module still just calls {@link OutboxEventPublisher}.
 *
 * Audit logging here is deliberately narrow: only a row's PERMANENT
 * failure (exhausting {@link #MAX_ATTEMPTS}) writes an
 * OUTBOX_EVENT_PUBLISH_FAILED event. Two things are intentionally never
 * audited:
 *  - a successful publish - this runs on a 2-second tick over up to 100
 *    rows at a time; logging every success would flood the audit trail
 *    with routine, non-actionable volume, working against the whole
 *    point of an audit trail (a record of things worth someone's
 *    attention).
 *  - a merely-transient failure (attempts 1 through MAX_ATTEMPTS-1) -
 *    RabbitMQ hiccups are expected and this row will simply retry on the
 *    next tick; only the terminal "this will never be delivered, someone
 *    needs to look at this" state is genuinely audit-worthy.
 *
 * Uses {@link com.oak.dms.audit.AuditEventBuilder#systemActor()} (via {@code AuditService}) -
 * this is the actual, correct use case that method exists for: a
 * scheduled job with no HTTP request/authenticated user behind it, as
 * opposed to the SYSTEM-actor fallback {@code AuditService#resolveActor}
 * applies automatically when a request has no bound identity (e.g.
 * LOGIN_FAILURE).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final AuditService auditService;

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
            rabbitTemplate.send(
                    RabbitMqConfig.DMS_EVENTS_EXCHANGE,
                    event.getEventType(),
                    jsonMessage(event.getPayload()));
            event.setStatus(OutboxEvent.Status.published);
            event.setPublishedAt(OffsetDateTime.now());
        } catch (Exception e) {
            event.setAttempts(event.getAttempts() + 1);
            event.setLastError(e.getMessage());
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                event.setStatus(OutboxEvent.Status.failed);
                log.error("Outbox event {} permanently failed after {} attempts: {}",
                        event.getOutboxEventId(), event.getAttempts(), e.getMessage());
                auditPermanentFailure(event, e);
            } else {
                log.warn("Outbox event {} failed to publish (attempt {}/{}): {}",
                        event.getOutboxEventId(), event.getAttempts(), MAX_ATTEMPTS, e.getMessage());
            }
        } finally {
            outboxEventRepository.save(event);
        }
    }

    /**
     * {@code payload} is a {@code jsonb} column -- it is ALREADY a serialized
     * JSON object, so it goes on the wire as raw UTF-8 bytes with
     * {@code content_type: application/json} rather than through
     * {@link RabbitTemplate}'s converter.
     * <p>
     * This is not a stylistic preference. The previous
     * {@code convertAndSend(exchange, routingKey, event.getPayload())} handed
     * a {@code String} to the configured
     * {@link org.springframework.amqp.support.converter.Jackson2JsonMessageConverter},
     * which serialized it AGAIN -- as a quoted JSON string literal
     * ({@code "{\"tenant_id\": ...}"}), stamped {@code __TypeId__=java.lang.String}
     * -- so every consumer got a String where an object was expected. Verified
     * live against the local stack: every ocr-worker-produced
     * {@code document.version.ocr.completed} event dead-lettered with
     * {@code MismatchedInputException: Cannot construct instance of
     * java.util.LinkedHashMap ... from String value}, and every dms-core-produced
     * {@code document.version.ocr.requested} event broke ocr-worker's Python
     * consumer with {@code AttributeError: 'str' object has no attribute 'get'}.
     * Both directions of the Phase 1 queue path were dead.
     * <p>
     * No {@code __TypeId__} header is set, on purpose: Spring AMQP's converter
     * defaults to {@code TypePrecedence.INFERRED}, so each listener's own
     * parameter type (e.g. {@code Map<String, Object>}) governs deserialization.
     */
    private static Message jsonMessage(String payloadJson) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(payloadJson.getBytes(StandardCharsets.UTF_8), properties);
    }

    private void auditPermanentFailure(OutboxEvent event, Exception cause) {
        auditService.event(AuditActionCatalog.OUTBOX_EVENT_PUBLISH_FAILED)
                .systemActor()
                .tenantId(event.getTenantId())
                .objectType(AuditObjectTypes.OUTBOX_EVENT)
                .objectId(event.getOutboxEventId())
                .resourceName(event.getEventType())
                .outcome(AuditOutcome.FAILURE)
                .failureReason(cause.getMessage())
                .newValue(Map.of(
                        "aggregateType", event.getAggregateType(),
                        "aggregateId", event.getAggregateId(),
                        "attempts", event.getAttempts()))
                .log();
    }
}
