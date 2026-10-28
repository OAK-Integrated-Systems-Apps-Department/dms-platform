package com.oak.dms.importexport.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BULK_IMPORT_* audit events - one when a batch is submitted, one when it
 * finishes or is cancelled. Every document a batch creates is audited on
 * its own already (DOCUMENT_UPLOADED, by the scan gate), so rows aren't
 * audited again here. Tenant and object come from the batch, not the
 * caller, because the worker and completion job log these too.
 */
@Component
@RequiredArgsConstructor
public class BulkImportAuditor {

    private final AuditService auditService;

    public void submitted(ImportBatch batch, int filesReceived) {
        Map<String, Object> details = details(batch);
        details.put("filesReceived", filesReceived);
        log(AuditActionCatalog.BULK_IMPORT_STARTED, batch, AuditOutcome.SUCCESS, details);
    }

    public void cancelled(ImportBatch batch) {
        log(AuditActionCatalog.BULK_IMPORT_CANCELLED, batch, AuditOutcome.SUCCESS, details(batch));
    }

    public void finished(ImportBatch batch) {
        AuditOutcome outcome = batch.getStatus() == ImportBatchStatus.failed ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS;
        log(AuditActionCatalog.BULK_IMPORT_COMPLETED, batch, outcome, details(batch));
    }

    private void log(String action, ImportBatch batch, AuditOutcome outcome, Map<String, Object> details) {
        auditService.event(action)
                .tenantId(batch.getTenantId())
                .objectType(AuditObjectTypes.IMPORT_BATCH)
                .objectId(batch.getImportBatchId())
                .outcome(outcome)
                .newValue(details)
                .log();
    }

    private static Map<String, Object> details(ImportBatch batch) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", batch.getStatus().name());
        details.put("documentTypeId", batch.getDocumentTypeId());
        details.put("schemaVersion", batch.getSchemaVersion());
        details.put("totalRecords", batch.getTotalRecords());
        details.put("successCount", batch.getSuccessCount());
        details.put("failureCount", batch.getFailureCount());
        details.put("correlationId", batch.getCorrelationId());
        return details;
    }
}
