package com.oak.dms.importexport.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.entity.MigrationBatch;
import com.oak.dms.importexport.enums.MigrationBatchStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BULK_IMPORT_* audit events - one per batch start and one per finish.
 * Each document created is audited on its own already (DOCUMENT_UPLOADED,
 * by the scan gate), so rows aren't audited again here.
 */
@Component
@RequiredArgsConstructor
public class BulkImportAuditor {

    private final AuditService auditService;

    public void started(MigrationBatch batch, String templateName) {
        Map<String, Object> details = counts(batch);
        details.put("template", templateName);
        auditService.event(AuditActionCatalog.BULK_IMPORT_STARTED)
                .tenantId(batch.getTenantId())
                .objectType(AuditObjectTypes.MIGRATION_BATCH)
                .objectId(batch.getMigrationBatchId())
                .resourceName(templateName)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(details)
                .log();
    }

    public void finished(MigrationBatch batch) {
        boolean cancelled = batch.getStatus() == MigrationBatchStatus.cancelled;
        boolean failed = batch.getStatus() == MigrationBatchStatus.failed;
        var event = auditService.event(cancelled ? AuditActionCatalog.BULK_IMPORT_CANCELLED
                        : AuditActionCatalog.BULK_IMPORT_COMPLETED)
                .tenantId(batch.getTenantId())
                .objectType(AuditObjectTypes.MIGRATION_BATCH)
                .objectId(batch.getMigrationBatchId())
                .outcome(failed ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS)
                .newValue(counts(batch));
        if (failed && batch.getFailureReason() != null) {
            event.failureReason(batch.getFailureReason());
        }
        event.log();
    }

    private static Map<String, Object> counts(MigrationBatch batch) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", batch.getStatus().name());
        details.put("documentTypeId", batch.getDocumentTypeId());
        details.put("schemaVersion", batch.getSchemaVersion());
        details.put("totalRecords", batch.getTotalRecords());
        details.put("successCount", batch.getSuccessCount());
        details.put("failureCount", batch.getFailureCount());
        return details;
    }
}
