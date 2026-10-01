package com.oak.dms.importexport.entity;

import com.oak.dms.importexport.enums.MigrationItemStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One row of an import template (Table Dictionary 11.5 - see V42 for the
 * added columns). {@link #rowData} keeps the row's cells exactly as
 * submitted, keyed by column header, so a failed row can be handed back as
 * a correctable template.
 */
@Entity
@Table(name = "migration_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationItem {

    @Id
    @UuidGenerator
    @Column(name = "migration_item_id", updatable = false, nullable = false)
    private UUID migrationItemId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "migration_batch_id", nullable = false, updatable = false)
    private UUID migrationBatchId;

    /** The row's filename cell. */
    @Column(name = "source_reference", nullable = false, updatable = false)
    private String sourceReference;

    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private MigrationItemStatus status = MigrationItemStatus.pending;

    /** Why the row failed, one "column: reason" per line. */
    @Column(name = "validation_result")
    private String validationResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /** Spreadsheet row number as the user sees it (the header is row 1). */
    @Column(name = "row_number", nullable = false, updatable = false)
    private int rowNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_data", nullable = false, columnDefinition = "jsonb", updatable = false)
    @Builder.Default
    private Map<String, String> rowData = new LinkedHashMap<>();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** Set while the row's file is still being scanned - see BulkImportReconciler. */
    @Column(name = "scan_result_id")
    private UUID scanResultId;
}
