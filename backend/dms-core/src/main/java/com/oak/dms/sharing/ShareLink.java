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

/**
 * One external share link to a document or folder (share_links, V144). Only the SHA-256 of the token is stored, so
 * the link itself exists in plain text once - in the response that created it.
 */
@Entity
@Table(name = "share_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShareLink {

    public static final String TYPE_DOCUMENT = "document";
    public static final String TYPE_FOLDER = "folder";
    public static final String ACCESS_VIEW = "view";
    public static final String ACCESS_DOWNLOAD = "download";

    public static final String REVOKED_MANUAL = "manual";
    public static final String REVOKED_RECLASSIFIED = "reclassified";

    @Id
    @UuidGenerator
    @Column(name = "link_id", updatable = false, nullable = false)
    private UUID linkId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "target_type", nullable = false, updatable = false)
    private String targetType;

    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Column(name = "folder_id", updatable = false)
    private UUID folderId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "access", nullable = false)
    private String access;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "max_views")
    private Integer maxViews;

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private int viewCount = 0;

    @Column(name = "recipient_email")
    private String recipientEmail;

    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "needs_review", nullable = false)
    @Builder.Default
    private boolean needsReview = false;

    @Column(name = "override_reason")
    private String overrideReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "last_accessed_at")
    private OffsetDateTime lastAccessedAt;

    public boolean isDocument() {
        return TYPE_DOCUMENT.equals(targetType);
    }

    public UUID targetId() {
        return isDocument() ? documentId : folderId;
    }

    public boolean allowsDownload() {
        return ACCESS_DOWNLOAD.equals(access);
    }

    /** What the link is doing right now - the same words the API and the public page use. */
    public String status(OffsetDateTime now) {
        if (revokedAt != null) {
            return "revoked";
        }
        if (!expiresAt.isAfter(now)) {
            return "expired";
        }
        if (maxViews != null && viewCount >= maxViews) {
            return "exhausted";
        }
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            return "locked";
        }
        return "active";
    }
}
