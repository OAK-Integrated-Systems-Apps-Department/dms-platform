package com.oak.dms.outbox;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
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
 * Uses {@link com.oak.dms.audit.helper.AuditEventBuilder#systemActor()} (via {@code AuditService}) -
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
                auditPermanentFailure(event, e);
            } else {
                log.warn("Outbox event {} failed to publish (attempt {}/{}): {}",
                        event.getOutboxEventId(), event.getAttempts(), MAX_ATTEMPTS, e.getMessage());
            }
        } finally {
            outboxEventRepository.save(event);
        }
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
