package com.oak.dms.comment.entity;

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

/**
 * A user mentioned in a comment (V49). Insert-only apart from notified_at
 * (N2). had_document_access snapshots whether they could open the document
 * when mentioned, so it stays visible later why they never responded
 * (decision 10).
 */
@Entity
@Table(name = "comment_mentions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentMention {

    @Id
    @UuidGenerator
    @Column(name = "mention_id", updatable = false, nullable = false)
    private UUID mentionId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "comment_id", nullable = false, updatable = false)
    private UUID commentId;

    @Column(name = "mentioned_user_id", nullable = false, updatable = false)
    private UUID mentionedUserId;

    @Column(name = "had_document_access", nullable = false, updatable = false)
    private boolean hadDocumentAccess;

    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
