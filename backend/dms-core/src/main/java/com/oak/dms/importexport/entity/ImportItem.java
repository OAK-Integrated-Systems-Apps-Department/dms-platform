package com.oak.dms.importexport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * One upload into an {@link ImportBatch} - one file, which becomes one
 * document - and its outcome. {@code rowNumber} is the upload's place in
 * the batch. {@code filenameReference} is the uploaded file's name without
 * any folder path, and deliberately not unique: two files with the same
 * name are two documents.
 */
@Entity
@Table(name = "import_items", uniqueConstraints = {
        @UniqueConstraint(name = "uq_import_items_batch_row_number", columnNames = {"import_batch_id", "row_number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportItem {

    @Id
    @UuidGenerator
    @Column(name = "import_item_id", updatable = false, nullable = false)
    private UUID importItemId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "import_batch_id", nullable = false)
    private UUID importBatchId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "filename_reference", nullable = false)
    private String filenameReference;

    /**
     * The template row's raw values, for template-based batches. Items are
     * now one uploaded file each with no values of their own, so new items
     * store {@code {}}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_values", nullable = false, columnDefinition = "jsonb")
    private String rowValues;

    /**
     * FK to be added once the branch that creates upload_sessions is merged.
     * Do not create upload_sessions here.
     */
    @Column(name = "upload_session_id")
    private UUID uploadSessionId;

    @Column(name = "document_id")
    private UUID documentId;

    /** Object key of the uploaded file while it waits for the import worker; null until received. */
    @Column(name = "staged_object_key", length = 1000)
    private String stagedObjectKey;

    @Column(name = "staged_storage_location_id")
    private UUID stagedStorageLocationId;

    @Column(name = "staged_size_bytes")
    private Long stagedSizeBytes;

    /**
     * The scan gate's ticket for this row's file, saved as soon as the gate
     * accepts it; null until then. See {@code ImportRecordCreator}.
     */
    @Column(name = "scan_result_id")
    private UUID scanResultId;

    /**
     * Error code the upload was refused with at intake (see
     * {@code BulkImportServiceImpl.receiveFile}) - the item is then
     * {@code failed} with FILE_REJECTED; null for an accepted upload.
     */
    @Column(name = "upload_rejection_code", length = 50)
    private String uploadRejectionCode;

    /** Why that file was refused, naming the uploaded file; null with {@link #uploadRejectionCode}. */
    @Column(name = "upload_rejection_message", columnDefinition = "text")
    private String uploadRejectionMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ImportItemStatus status = ImportItemStatus.pending;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_errors", columnDefinition = "jsonb")
    private String validationErrors;

    /**
     * True for a row of a template-based batch that failed the template
     * checks when its batch was created. No longer set - the template flow
     * was removed - but kept for the rows that have it.
     */
    @Column(name = "skipped_at_preview", nullable = false)
    @Builder.Default
    private boolean skippedAtPreview = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public enum ImportItemStatus {
        pending, success, failed
    }
}
