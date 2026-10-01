package com.oak.dms.importexport.entity;

import com.oak.dms.importexport.enums.UploadSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A resumable, chunked upload of one large file (Table Dictionary 6.1 - see
 * V42 for the added columns). {@link #receivedBytes} is the resume point:
 * the next chunk must start exactly there.
 */
@Entity
@Table(name = "upload_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadSession {

    /** source of sessions that carry a bulk import ZIP (ck_upload_sessions_source). */
    public static final String SOURCE_BULK_IMPORT = "bulk_import";

    @Id
    @UuidGenerator
    @Column(name = "upload_session_id", updatable = false, nullable = false)
    private UUID uploadSessionId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private UUID initiatedBy;

    @Column(name = "source", nullable = false, updatable = false)
    @Builder.Default
    private String source = SOURCE_BULK_IMPORT;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private UploadSessionStatus status = UploadSessionStatus.pending;

    @Column(name = "quarantine_object_key")
    private String quarantineObjectKey;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "original_filename", nullable = false, updatable = false)
    private String originalFilename;

    @Column(name = "total_size_bytes", nullable = false, updatable = false)
    private long totalSizeBytes;

    @Column(name = "received_bytes", nullable = false)
    private long receivedBytes;

    /** Local staging file - never returned to clients. */
    @Column(name = "staging_path")
    private String stagingPath;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
