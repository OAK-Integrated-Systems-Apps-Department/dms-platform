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

/** A guest's access to one document or folder (and everything inside it) - guest_grants, V144. */
@Entity
@Table(name = "guest_grants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestGrant {

    @Id
    @UuidGenerator
    @Column(name = "grant_id", updatable = false, nullable = false)
    private UUID grantId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "guest_id", nullable = false, updatable = false)
    private UUID guestId;

    @Column(name = "target_type", nullable = false, updatable = false)
    private String targetType;

    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Column(name = "folder_id", updatable = false)
    private UUID folderId;

    @Column(name = "access", nullable = false)
    private String access;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    /** Set when a tenant administrator shared something confidential anyway (the reason they gave). */
    @Column(name = "override_reason")
    private String overrideReason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public boolean isDocument() {
        return ShareLink.TYPE_DOCUMENT.equals(targetType);
    }

    public UUID targetId() {
        return isDocument() ? documentId : folderId;
    }

    public boolean isLive(OffsetDateTime now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean allowsDownload() {
        return ShareLink.ACCESS_DOWNLOAD.equals(access);
    }
}
