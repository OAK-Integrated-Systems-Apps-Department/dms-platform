package com.oak.dms.checkout;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The smallest document-level legal hold that makes CICO-FR-009 true: a flag with who, when and
 * why, refused-by-everything-that-edits while set (see {@link DocumentEditGuard}). Retention and
 * disposal rules that also honour it belong to the lifecycle module (DLM) and are NOT done here -
 * FOLLOW-UP: version purge / recycle-bin purge do not yet look at this flag.
 */
@Service
@RequiredArgsConstructor
public class LegalHoldService {

    private final DocumentRepository documentRepository;
    private final AuditService auditService;
    private final com.oak.dms.compliance.RetentionEngine retentionEngine;

    public void place(UUID documentId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = find(documentId, tenantId);
        if (documentRepository.placeLegalHold(documentId, tenantId, TenantContext.getUserId(),
                OffsetDateTime.now(), reason) == 0) {
            throw CheckoutException.conflict("DOCUMENT_ALREADY_ON_LEGAL_HOLD",
                    "\"" + document.getTitle() + "\" is already under legal hold.");
        }
        auditService.event(AuditActionCatalog.DOCUMENT_LEGAL_HOLD_PLACED)
                .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                .reason(reason).log();
    }

    public void lift(UUID documentId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = find(documentId, tenantId);
        String placedFor = document.getLegalHoldReason();
        if (documentRepository.liftLegalHold(documentId, tenantId) == 0) {
            throw CheckoutException.conflict("DOCUMENT_NOT_ON_LEGAL_HOLD",
                    "\"" + document.getTitle() + "\" isn't under legal hold.");
        }
        auditService.event(AuditActionCatalog.DOCUMENT_LEGAL_HOLD_LIFTED)
                .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                .reason(reason)
                .previousValue(Map.of("placedReason", placedFor == null ? "" : placedFor))
                .log();
        // Retention was suspended while the hold lasted; a transition that fell due meanwhile happens now, not next run.
        retentionEngine.catchUp(documentId, tenantId);
    }

    private Document find(UUID documentId, UUID tenantId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }
}
