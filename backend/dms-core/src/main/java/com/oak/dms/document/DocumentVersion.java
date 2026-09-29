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

    @Column(name = "scan_status", nullable = false)
    @Builder.Default
    private String scanStatus = "pending";

    @Column(name = "rendition_status", nullable = false)
    @Builder.Default
    private String renditionStatus = "not_required";

    @Column(name = "ocr_status", nullable = false)
    @Builder.Default
    private String ocrStatus = "not_required";

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
}
