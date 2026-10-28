package com.oak.dms.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * docs/database/V4 4.2, ported into V32 alongside {@link Document} - see
 * that entity's own doc comment for the shared circular-FK/DEFERRABLE
 * reasoning. {@code storageLocationId}/{@code storageObjectKey} are
 * deliberately here, not on {@link Document}: each version is a distinct
 * physical object, so its location and its key within that location belong
 * on the same row - see docs/storage-architecture.md's "why DocumentVersion,
 * not Document" section. Neither is ever the sole tenant-isolation boundary
 * on its own - {@code DocumentServiceImpl} re-validates tenant ownership on
 * both this row and its storage location before every storage call.
 * <p>
 * This is also the exact table ocr-worker's own SQLAlchemy model
 * (DocumentVersion in backend/workers/ocr-worker/src/ocr_worker/models.py)
 * reads and writes: {@code scanStatus}/{@code ocrStatus}/
 * {@code storageObjectKey}/{@code mimeType} must stay in sync with that
 * model's column expectations.
 */
@Entity
@Table(name = "document_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentVersion {

    /** Scanned clean by the malware scan gate. */
    public static final String SCAN_STATUS_CLEAN = "clean";
    /** Released from quarantine by an administrator override despite the scanner's verdict (V40). */
    public static final String SCAN_STATUS_OVERRIDDEN = "overridden";

    /** Whether this version's bytes may be served - scanned clean, or explicitly released by an administrator. */
    public boolean isServable() {
        return SCAN_STATUS_CLEAN.equals(scanStatus) || SCAN_STATUS_OVERRIDDEN.equals(scanStatus);
    }

    // No @UuidGenerator - same reasoning as Document.documentId: assigned by
    // DocumentServiceImpl before save() so it can build the matching
    // Document.latestVersionId/currentVersionId up front.
    @Id
    @Column(name = "version_id", updatable = false, nullable = false)
    private UUID versionId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "major_version", nullable = false)
    private int majorVersion;

    @Column(name = "minor_version", nullable = false)
    private int minorVersion;

    @Column(name = "version_label")
    private String versionLabel;

    @Column(name = "storage_location_id", nullable = false)
    private UUID storageLocationId;

    @Column(name = "storage_object_key", nullable = false)
    private String storageObjectKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    /** SHA-256 hex digest, computed by DocumentServiceImpl from the upload stream before persisting - not a provider ETag. */
    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "version_comment")
    private String versionComment;

    @Column(name = "created_reason", nullable = false)
    private String createdReason;

    /** 'pending' | 'clean' | 'infected' | 'failed' | 'quarantined' -- see docs/database/V4's CHECK constraint. */
    @Column(name = "scan_status", nullable = false)
    @Builder.Default
    private String scanStatus = "pending";

    @Column(name = "rendition_status", nullable = false)
    @Builder.Default
    private String renditionStatus = "not_required";

    /** ocr-worker owns writing/reading this column going forward -- dms-core only sets the initial value. */
    @Column(name = "ocr_status", nullable = false)
    @Builder.Default
    private String ocrStatus = "not_required";

    /**
     * Phase 4b (V41). Written by ocr-worker in the same transaction as
     * {@link #ocrStatus}; dms-core only reads it, to build the review queue.
     * <p>
     * NOT a synonym for low confidence -- production prompt 3.10 makes it
     * any mandatory field not extracted, any validation failure, or poor
     * image quality. A document can be read with low confidence and still
     * need no review, and read confidently and still be wrong.
     */
    @Column(name = "requires_review", nullable = false)
    @Builder.Default
    private boolean requiresReview = false;

    /**
     * Why {@link #requiresReview} is true, as raw JSON -- same convention
     * as {@link #requestedFields}: this codebase has no JSON-to-object
     * converter, so callers deserialize via the injected ObjectMapper.
     * NULL before a version has been through OCR, which is different from
     * "reviewed and found clean".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_reasons", columnDefinition = "jsonb")
    private String reviewReasons;

    /**
     * Pages in the source document (V42), written by ocr-worker when it
     * loads the file.
     * <p>
     * NULL before OCR has run, and for a document that failed before the
     * file could be opened at all -- which is honest rather than guessing
     * 1, and lets the review screen say "we never got to read this" for
     * exactly the documents where that is true.
     */
    @Column(name = "page_count")
    private Integer pageCount;

    /**
     * JSON array of field_key strings ("selectable OCR field extraction" --
     * see V35): which fields the caller asked OCR to attempt to extract for
     * this version, set at upload and unioned-in on every re-run request.
     * Stored as raw JSON text via {@code @JdbcTypeCode(SqlTypes.JSON)},
     * same convention as {@code OutboxEvent.payload} -- this codebase has no
     * JSON-column-to-List&lt;String&gt; converter, so callers serialize/
     * deserialize via the injected {@code ObjectMapper} themselves.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_fields", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String requestedFields = "[]";

    /**
     * When {@code applyOcrFields} last ran for this version; {@code null}
     * means never. See {@code V38__ocr_fields_applied_marker.sql} for why
     * this is an explicit marker rather than something inferred from
     * {@code document_metadata_values}. Stamped by {@code OcrEventsConsumer},
     * {@code OcrFieldsReconciler} and the poll-time re-apply.
     */
    @Column(name = "ocr_fields_applied_at")
    private OffsetDateTime ocrFieldsAppliedAt;

    @Column(name = "is_protected", nullable = false)
    @Builder.Default
    private boolean protectedVersion = false;

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

    /** Set when its document was permanently deleted (V62): the file and its key are destroyed. */
    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;

    /** The PDF it is previewed as, when it is an Office file (V64) - beside the original, same location. */
    @Column(name = "rendition_object_key")
    private String renditionObjectKey;
}
