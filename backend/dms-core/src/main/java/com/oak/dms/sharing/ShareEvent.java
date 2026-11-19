package com.oak.dms.sharing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One line of the sharing log (share_events, V144): who did what to which shared item, from where, when. */
@Entity
@Table(name = "share_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShareEvent {

    @Id
    @UuidGenerator
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime occurredAt = OffsetDateTime.now();

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "link_id", updatable = false)
    private UUID linkId;

    @Column(name = "guest_id", updatable = false)
    private UUID guestId;

    @Column(name = "target_type", updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "target_name", updatable = false)
    private String targetName;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_label", nullable = false, updatable = false)
    private String actorLabel;

    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    @Column(name = "detail", updatable = false)
    private String detail;
}
