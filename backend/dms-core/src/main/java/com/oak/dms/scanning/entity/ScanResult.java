package com.oak.dms.scanning.entity;

import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanResultStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One uploaded file's journey through the scan gate, from staging to its
 * verdict - and the ingestion ticket an uploader polls. See V39 and
 * docs/scanning-architecture.md.
 * <p>
 * {@code documentVersionId} stays null until a clean file is promoted (set in
 * the same transaction as the version insert); infected files never get one.
 * The intent fields ({@code ingestionKind}, {@code createdReason},
 * {@code documentTypeId}, {@code targetDocumentId}, {@code title}) record what
 * to create once the file is clean, so an asynchronous cloud verdict can
 * promote it long after the upload request returned.
 * <p>
 * {@code @DynamicUpdate}: a callback may bump {@code nextAttemptAt}/{@code threatName}
 * while a worker holds this row, so a save only ever writes the columns it changed.
 */
@Entity
@DynamicUpdate
@Table(name = "scan_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanResult {

    @Id
    @UuidGenerator
    @Column(name = "scan_result_id", updatable = false, nullable = false)
    private UUID scanResultId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "scan_engine_id", nullable = false)
    private UUID scanEngineId;

    @Column(name = "document_version_id")
    private UUID documentVersionId;

    @Column(name = "staging_location_id", nullable = false)
    private UUID stagingLocationId;

    /** Null when the file was rejected before it was ever staged. */
    @Column(name = "staging_object_key")
    private String stagingObjectKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    /** SHA-256 hex, computed while staging. Null means staging never completed. */
    @Column(name = "file_hash")
    private String fileHash;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Canonical MIME from the allowlist - never the client's Content-Type. */
    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ScanResultStatus status = ScanResultStatus.pending;

    @Column(name = "threat_name")
    private String threatName;

    @Column(name = "engine_name")
    private String engineName;

    @Column(name = "engine_version")
    private String engineVersion;

    @Column(name = "signature_version")
    private String signatureVersion;

    /**
     * Cloud async correlation: the staged object's ETag, captured when it was
     * staged. Both Defender and GuardDuty report the eTag of the object they
     * scanned, so a verdict is only ever applied to exactly these bytes.
     */
    @Column(name = "external_scan_id")
    private String externalScanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_mode", nullable = false)
    private ScanMode scanMode;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    /** When the retry scheduler should next look at this row (pending/infected only). */
    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    /** Claim lease - whoever is working on this row holds it until then; a crashed worker's lease simply expires. */
    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "staging_purged_at")
    private OffsetDateTime stagingPurgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_kind", nullable = false, updatable = false)
    private IngestionKind ingestionKind;

    /** One of {@code document_versions.created_reason}'s CHECK values. */
    @Column(name = "created_reason", nullable = false, updatable = false)
    private String createdReason;

    @Column(name = "document_type_id", updatable = false)
    private UUID documentTypeId;

    @Column(name = "target_document_id", updatable = false)
    private UUID targetDocumentId;

    @Column(name = "title", updatable = false)
    private String title;

    /** A new version's "what changed" (V62), copied onto the version it becomes. */
    @Column(name = "version_comment", updatable = false)
    private String versionComment;

    /** The folder a new document lands in once clean (V51); null = not in a folder. */
    @Column(name = "folder_id", updatable = false)
    private UUID folderId;

    /**
     * Schema field keys OCR should extract once this file is clean (V82), as
     * raw JSON - same convention as {@code DocumentVersion.requestedFields}.
     * Null or {@code []} means no OCR was asked for.
     * <p>
     * Carried here rather than acted on at upload because OCR must not read an
     * unscanned file, and with a cloud scanner the clean verdict can arrive
     * long after the request that made this choice has gone.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ocr_requested_fields", columnDefinition = "jsonb", updatable = false)
    private String ocrRequestedFields;

    /** Bulk-import ingestion: OCR goes on the low-priority routing key (V82). */
    @Column(name = "ocr_low_priority", nullable = false, updatable = false)
    @Builder.Default
    private boolean ocrLowPriority = false;

    /**
     * The document the uploader chose to keep a copy of ("Keep both" after duplicate detection, V108),
     * or null. Read by DocumentVersionPromoter, which raises the event that links the new document to it
     * as "Duplicate of" once it exists.
     */
    @Column(name = "duplicate_of_document_id", updatable = false)
    private UUID duplicateOfDocumentId;

    /**
     * Set only on a quarantine release attempt (V40): the original,
     * quarantined scan result this row re-scans (or force-releases).
     */
    @Column(name = "released_from_scan_result_id", updatable = false)
    private UUID releasedFromScanResultId;

    /** The administrator who requested the release attempt. */
    @Column(name = "review_requested_by", updatable = false)
    private UUID reviewRequestedBy;

    /** Their mandatory reason - carried to the quarantine record once the attempt completes. */
    @Column(name = "review_reason", updatable = false)
    private String reviewReason;

    public boolean isReleaseAttempt() {
        return releasedFromScanResultId != null;
    }

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
