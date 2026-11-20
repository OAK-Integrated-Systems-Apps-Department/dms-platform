package com.oak.dms.outbox;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.messaging.RabbitMqConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls PENDING outbox rows on a fixed interval and forwards each to the
 * RabbitMQ topic exchange, routed by event type (e.g. "user.created").
 * A polling relay (vs. e.g. Debezium/CDC) is the simplest thing that
 * satisfies the outbox pattern for this stage of the platform; swap it
 * for log-based CDC later without touching any producer code; every
 * module still just calls {@link OutboxEventPublisher}.
 *
 * <b>Several instances (OBX-01).</b> Every dms-core instance runs this relay. The batch query row-locks the pending
 * rows it returns ({@code FOR UPDATE SKIP LOCKED}, see {@link OutboxEventRepository#findBatchToPublish}) and this
 * method's transaction keeps those locks until the status updates have been committed. Two instances therefore never
 * publish the same row at the same time: each takes different rows, a row the other one holds is skipped, and nobody
 * waits. If an instance dies, PostgreSQL drops its locks and its rows stay {@code pending} for the next poll.
 *
 * <b>Broker confirmation (OBX-02).</b> {@code RabbitTemplate.send} returning only means the frame was written to the
 * channel; it says nothing about whether the broker took the message. A row is therefore marked {@code published} only
 * after the broker has positively confirmed THAT publish AND has not returned it as unroutable:
 * <ul>
 *   <li>Every publish carries its own {@link CorrelationData}, whose id is unique per publish (event id + a random
 *       UUID), so a confirmation or a return can only ever be matched to the publish it belongs to - never to another
 *       event, another relay instance, or a later attempt at the same event.</li>
 *   <li>The whole batch is sent first and the confirmations are awaited afterwards, against ONE deadline for the batch
 *       ({@code dms.outbox.confirm-timeout-ms}, default {@value #DEFAULT_CONFIRM_TIMEOUT_MS} ms), not one timeout per
 *       message. There is no unbounded wait.</li>
 *   <li>A negative confirmation (nack), a confirmation that does not arrive in time, a failed confirmation future, and
 *       a returned message (the template publishes with {@code mandatory}, see {@link RabbitMqConfig}) are all treated
 *       like a send that threw: one attempt is counted and the row stays {@code pending} (see the retry paragraph below).
 *       A return arrives before the confirm of the same publish, so a positive confirm after a return is still a
 *       failure.</li>
 *   <li>An abandoned confirmation (timeout) can complete later; it completes only that attempt's own
 *       {@code CorrelationData}, which nothing reads any more.</li>
 *   <li>If the waiting thread is interrupted the relay stops waiting, keeps the interrupt flag, and leaves the rows it
 *       did not hear about untouched ({@code pending}, attempt not counted); they are sent again on a later poll.</li>
 * </ul>
 *
 * That is NOT exactly-once delivery. If the broker confirms a message and the relay then dies (or its transaction
 * fails) before the commit, the row is still {@code pending} and is published again; a timed-out message the broker did
 * in fact accept is published again too. Delivery is at-least-once, and a consumer must tolerate a repeated event (the
 * current ones do, by acting on domain state). The transaction - and so the row locks and one database connection -
 * lasts for the whole batch: sending up to {@link #BATCH_SIZE} messages plus at most the confirmation deadline, so a
 * stalled broker lengthens it by at most that deadline.
 *
 * <b>Retry (OBX-03).</b> A failed publish used to be retried at the very next poll, two seconds later, and the fifth failure
 * - about eight seconds into a broker outage - failed the event for good. Now a failed publish schedules the next attempt:
 * the relay writes {@code next_attempt_at} (database clock + {@link OutboxRetryPolicy#delayAfter exponential backoff with
 * jitter}) and the batch query only selects events whose time has come, so a failing event neither hammers a recovering broker
 * nor holds a place ahead of events that are ready. An event is failed for good only when its FIRST FAILED PUBLISH is older than
 * the retry window ({@code dms.outbox.retry.max-age-ms}, default 24 h - an event that waited for the relay still gets all of
 * it), or hits the runaway guard of {@code dms.outbox.retry.max-attempts}, or
 * when its payload cannot even be turned into a message (a permanent error: retrying cannot change it). Every other outcome -
 * connection or channel failure, confirmation timeout, nack, return (including an unbound routing key or a missing exchange,
 * which become routable when a worker starts or a binding is added) - is retried on the same schedule; none of them is assumed
 * permanent. Attempts are counted exactly as before, in the relay's own transaction, so a rollback leaves the row as it was.
 * Nothing in the application re-publishes a FAILED event: replaying one needs an audited administrative operation that does
 * not exist yet (see the OBX-03 write-up).
 *
 * Audit logging here is deliberately narrow: only a row's PERMANENT
 * failure (retry window or attempt guard exhausted, or an unencodable payload) writes an
 * OUTBOX_EVENT_PUBLISH_FAILED event. Two things are intentionally never
 * audited:
 *  - a successful publish - this runs on a 2-second tick over up to 100
 *    rows at a time; logging every success would flood the audit trail
 *    with routine, non-actionable volume, working against the whole
 *    point of an audit trail (a record of things worth someone's
 *    attention).
 *  - a merely-transient failure (any attempt before the retry window ends) -
 *    RabbitMQ hiccups are expected and this row will simply retry at its
 *    scheduled time; only the terminal "this will never be delivered, someone
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
public class OutboxRelay {

    private static final int BATCH_SIZE = 100;
    /**
     * Default for the whole batch's confirmation wait. A healthy broker confirms in milliseconds; 5 s leaves room for a
     * slow disk or a brief network stall while bounding how long the batch's row locks and connection can be held
     * beyond the sends themselves. Too short and slow-but-working brokers cause needless retries (duplicates); too
     * long and a stalled broker holds locks and a connection for that long every poll.
     */
    static final long DEFAULT_CONFIRM_TIMEOUT_MS = 5000;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final AuditService auditService;
    private final long confirmTimeoutMs;
    private final OutboxRetryPolicy retryPolicy;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate,
                       AuditService auditService) {
        this(outboxEventRepository, rabbitTemplate, auditService, DEFAULT_CONFIRM_TIMEOUT_MS);
    }

    public OutboxRelay(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate,
                       AuditService auditService, long confirmTimeoutMs) {
        this(outboxEventRepository, rabbitTemplate, auditService, confirmTimeoutMs, OutboxRetryPolicy.defaults());
    }

    @Autowired
    public OutboxRelay(OutboxEventRepository outboxEventRepository, RabbitTemplate rabbitTemplate,
                       AuditService auditService,
                       @Value("${dms.outbox.confirm-timeout-ms:" + DEFAULT_CONFIRM_TIMEOUT_MS + "}") long confirmTimeoutMs,
                       OutboxRetryPolicy retryPolicy) {
        if (confirmTimeoutMs <= 0) {
            throw new IllegalArgumentException("dms.outbox.confirm-timeout-ms must be positive (no unbounded wait)");
        }
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.auditService = auditService;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.retryPolicy = retryPolicy;
    }

    /** One publish of one event: its own correlation, and what became of it. */
    private static final class Publish {
        final OutboxEvent event;
        final CorrelationData correlation;      // null when the send itself threw
        String failure;                         // null while not failed
        boolean permanent;                      // retrying cannot help (the payload cannot be encoded)
        boolean confirmed;
        boolean unknown;                        // waiting was interrupted: outcome not heard, row left untouched

        Publish(OutboxEvent event, CorrelationData correlation, String failure) {
            this.event = event;
            this.correlation = correlation;
            this.failure = failure;
        }
    }

    @Scheduled(fixedDelayString = "${dms.outbox.relay-interval-ms:2000}")
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxEventRepository.findBatchToPublish(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }
        List<Publish> publishes = new ArrayList<>(batch.size());
        try {
            // One dedicated channel for the whole batch: every send and every wait below uses it. Without this each send
            // can have to open a channel of its own (a round trip to the broker that the batch deadline does not cover:
            // against a frozen broker it was seen to block for minutes).
            rabbitTemplate.invoke(operations -> {
                for (OutboxEvent event : batch) {
                    publishes.add(send(operations, event));
                }
                awaitConfirmations(publishes);
                return null;
            });
        } catch (RuntimeException e) {
            // No channel could be opened, or it was lost: whatever has no answer yet is a failed publish.
            failUnresolved(batch, publishes, e);
        }
        // One read of the database clock per batch, only when something failed, BEFORE any row is touched: if it fails, nothing
        // has been recorded and the transaction rolls back as a whole.
        Instant now = publishes.stream().anyMatch(publish -> publish.failure != null && !publish.unknown) ? databaseNow() : null;
        for (Publish publish : publishes) {
            settle(publish, now);
        }
    }

    /**
     * The database clock - the one the batch query's eligibility test uses, so retries are scheduled and judged on ONE clock.
     * There is deliberately no fallback to the application clock: a failed read means the relay's transaction is broken (in
     * PostgreSQL a failed statement aborts it, so the status updates that follow would fail anyway) and the batch is rolled
     * back as a whole - nothing is recorded, the rows stay as they were and are picked up again (the messages already sent
     * are sent again: at-least-once). Substituting application time would make retry eligibility depend on the skew between
     * two clocks.
     */
    private Instant databaseNow() {
        return Instant.ofEpochMilli(outboxEventRepository.databaseClockMillis());
    }

    private static void failUnresolved(List<OutboxEvent> batch, List<Publish> publishes, RuntimeException cause) {
        for (Publish publish : publishes) {
            if (publish.failure == null && !publish.confirmed && !publish.unknown) {
                publish.failure = "publishing failed: " + describe(cause);
            }
        }
        for (int i = publishes.size(); i < batch.size(); i++) {
            publishes.add(new Publish(batch.get(i), null, "publishing failed: " + describe(cause)));
        }
    }

    /** Sends one event with a correlation of its own; a send that throws is recorded as that event's failure. */
    private Publish send(RabbitOperations operations, OutboxEvent event) {
        // Unique per publish (not just per event): a late answer for an earlier attempt can never match this one.
        CorrelationData correlation = new CorrelationData(event.getOutboxEventId() + ":" + UUID.randomUUID());
        Message message;
        try {
            message = jsonMessage(event.getPayload());
        } catch (RuntimeException e) {
            // The payload cannot be turned into a message at all: the same bytes will fail the same way every time.
            Publish publish = new Publish(event, null, "payload cannot be encoded as a message: " + describe(e));
            publish.permanent = true;
            return publish;
        }
        try {
            operations.send(
                    RabbitMqConfig.DMS_EVENTS_EXCHANGE,
                    event.getEventType(),
                    message,
                    correlation);
            return new Publish(event, correlation, null);
        } catch (Exception e) {
            return new Publish(event, null, describe(e));
        }
    }

    /**
     * Waits for the answer to every message that was sent, against one deadline for the whole batch. An answer that is
     * already there is always read, even after the deadline; anything else after the deadline is a timeout.
     */
    private void awaitConfirmations(List<Publish> publishes) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs);
        for (int i = 0; i < publishes.size(); i++) {
            Publish publish = publishes.get(i);
            if (publish.failure != null) {
                continue;
            }
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                CorrelationData.Confirm confirm = publish.correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
                ReturnedMessage returned = publish.correlation.getReturned();
                if (!confirm.isAck()) {
                    publish.failure = "broker negatively acknowledged the publish"
                            + (confirm.getReason() != null ? ": " + confirm.getReason() : "");
                } else if (returned != null) {
                    publish.failure = "message was returned by the broker as unroutable (reply " + returned.getReplyCode()
                            + " " + returned.getReplyText() + ", exchange '" + returned.getExchange()
                            + "', routing key '" + returned.getRoutingKey() + "')";
                } else {
                    publish.confirmed = true;
                }
            } catch (TimeoutException e) {
                publish.failure = "no broker confirmation within the " + confirmTimeoutMs + " ms batch deadline";
            } catch (ExecutionException e) {
                publish.failure = "confirmation failed: " + describe(e.getCause() != null ? e.getCause() : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for broker confirmations; {} unconfirmed outbox rows are left pending",
                        publishes.size() - i);
                for (int j = i; j < publishes.size(); j++) {
                    if (publishes.get(j).failure == null) {
                        publishes.get(j).unknown = true;
                    }
                }
                return;
            }
        }
    }

    /**
     * Writes the outcome onto the row: published only on a positive, unreturned confirm; otherwise one attempt is counted and
     * the row is either given its next attempt time or, when the retry window is over, failed for good.
     */
    private void settle(Publish publish, Instant now) {
        OutboxEvent event = publish.event;
        if (publish.unknown) {
            return;                                 // not heard: untouched, retried on a later poll
        }
        try {
            if (publish.confirmed) {
                event.setStatus(OutboxEvent.Status.published);
                event.setPublishedAt(OffsetDateTime.now());
                return;
            }
            event.setAttempts(event.getAttempts() + 1);
            event.setLastError(publish.failure);
            if (event.getFirstFailedAt() == null) {
                event.setFirstFailedAt(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));      // the retry window starts here
            }
            Duration age = Duration.between(event.getFirstFailedAt().toInstant(), now);
            if (publish.permanent || retryPolicy.isExhausted(event.getAttempts(), age)) {
                event.setStatus(OutboxEvent.Status.failed);
                log.error("Outbox event {} permanently failed after {} attempts over {}: {}",
                        event.getOutboxEventId(), event.getAttempts(), age, publish.failure);
                auditPermanentFailure(event, publish.failure);
            } else {
                Duration delay = retryPolicy.delayAfter(event.getAttempts());
                event.setNextAttemptAt(OffsetDateTime.ofInstant(now.plus(delay), ZoneOffset.UTC));
                log.warn("Outbox event {} failed to publish (attempt {}); next attempt in {}: {}",
                        event.getOutboxEventId(), event.getAttempts(), delay, publish.failure);
            }
        } finally {
            outboxEventRepository.save(event);
        }
    }

    private static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
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

    private void auditPermanentFailure(OutboxEvent event, String reason) {
        auditService.event(AuditActionCatalog.OUTBOX_EVENT_PUBLISH_FAILED)
                .systemActor()
                .tenantId(event.getTenantId())
                .objectType(AuditObjectTypes.OUTBOX_EVENT)
                .objectId(event.getOutboxEventId())
                .resourceName(event.getEventType())
                .outcome(AuditOutcome.FAILURE)
                .failureReason(reason)
                .newValue(Map.of(
                        "aggregateType", event.getAggregateType(),
                        "aggregateId", event.getAggregateId(),
                        "attempts", event.getAttempts()))
                .log();
    }
}
