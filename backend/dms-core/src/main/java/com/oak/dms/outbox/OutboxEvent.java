package com.oak.dms.outbox;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transactional outbox row. Any module that needs to notify the rest of
 * the system of something that happened (a document was uploaded, a user
 * was created, ...) writes one of these in the SAME database transaction
 * as its own business-table change, via {@link OutboxEventPublisher}.
 * A separate relay ({@link OutboxRelay}) polls PENDING rows and forwards
 * them to RabbitMQ, decoupling "the change definitely happened" from
 * "the message definitely got published" - the two hardest parts of
 * reliable eventing - without a two-phase commit.
 *
 * aggregateType/aggregateId are intentionally NOT foreign keys: this
 * table is polymorphic by design, shared across every future module
 * (document, workflow, sharing, ...), matching the pattern already used
 * for polymorphic fields elsewhere in the confirmed data dictionary.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @UuidGenerator
    @Column(name = "outbox_event_id", updatable = false, nullable = false)
    private UUID outboxEventId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** e.g. "user", "tenant", "document" - which module/entity this event is about. */
    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    /** Polymorphic target id. No FK by design - see aggregateType. */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** e.g. "user.created", "user.role_assigned" - used as the RabbitMQ routing key. */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private Status status = Status.pending;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public enum Status {
        pending, published, failed
    }
}
