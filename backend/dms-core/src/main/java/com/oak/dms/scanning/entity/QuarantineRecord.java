package com.oak.dms.scanning.entity;

import com.oak.dms.scanning.enums.QuarantineStatus;
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
 * An infected file isolated in a {@code purpose = quarantine} storage
 * location - one per {@link ScanResult}. Phase 1 only ever creates these in
 * {@code held}; review/release/delete are Phase 2 admin actions. Nothing in
 * Phase 1 exposes this entity or its object through any API.
 */
@Entity
@Table(name = "quarantine_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuarantineRecord {

    @Id
    @UuidGenerator
    @Column(name = "quarantine_id", updatable = false, nullable = false)
    private UUID quarantineId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "scan_result_id", nullable = false, updatable = false)
    private UUID scanResultId;

    @Column(name = "quarantine_location_id", nullable = false)
    private UUID quarantineLocationId;

    @Column(name = "quarantine_object_key", nullable = false)
    private String quarantineObjectKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "threat_name", nullable = false)
    private String threatName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private QuarantineStatus status = QuarantineStatus.held;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "review_reason")
    private String reviewReason;

    @Column(name = "released_document_id")
    private UUID releasedDocumentId;

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
