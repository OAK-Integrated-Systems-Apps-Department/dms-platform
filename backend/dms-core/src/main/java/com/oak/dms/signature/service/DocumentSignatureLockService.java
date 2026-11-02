package com.oak.dms.signature.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Decision 6/7: a workflow that ends approved with at least one still-valid
 * signature locks its document to the signed version; a locked document
 * refuses new versions, restore, metadata edits, move and delete until a
 * permission-gated, reason-required unlock. Deliberately an always-present
 * bean (not gated on {@code dms.signature.enabled}) - a document locked
 * while the feature was on must stay locked even if the feature is later
 * switched off; {@link #guard} is a cheap column read, nothing more.
 * <p>
 * {@link #lock} is called from {@code WorkflowSignatureListener}'s
 * {@code @TransactionalEventListener(AFTER_COMMIT)} and from the scheduled
 * reconcile job, both in a fresh {@code REQUIRES_NEW} transaction - never
 * from inside the approval transaction itself, which already holds the
 * instance lock and must not also take the document row lock (see
 * {@code WorkflowEngine}'s own lock-order comment: documents before
 * instances, never the reverse within one transaction).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSignatureLockService {

    private final DocumentRepository documentRepository;
    private final DocumentSignatureRepository signatureRepository;
    private final AuditService auditService;

    /** @throws SignatureException 409 DOCUMENT_SIGNATURE_LOCKED if the document is currently locked */
    public void guard(Document document) {
        if (document.getSignatureLockedAt() != null) {
            throw SignatureException.conflict("DOCUMENT_SIGNATURE_LOCKED",
                    "\"" + document.getTitle() + "\" is locked because it carries a valid signature - "
                            + "unlock it before changing it.");
        }
    }

    public void guard(UUID documentId) {
        guard(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId)));
    }

    /**
     * Idempotent: a no-op if the document is already locked, no longer
     * exists, or (the race {@code WorkflowSignatureListener}'s own comment
     * describes) no longer has a valid signature for {@code versionId} by
     * the time this runs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void lock(UUID tenantId, UUID documentId, UUID versionId) {
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElse(null);
        if (document == null || document.getSignatureLockedAt() != null) {
            return;
        }
        boolean stillValidlySigned = signatureRepository
                .findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId).stream()
                .anyMatch(s -> s.isValid() && s.getVersionId().equals(versionId));
        if (!stillValidlySigned) {
            return;
        }
        document.setSignatureLockedAt(OffsetDateTime.now());
        document.setSignatureLockedVersionId(versionId);
        documentRepository.save(document);

        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_LOCKED)
                .tenantId(tenantId)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("signatureLockedVersionId", versionId))
                .log();
        log.info("Locked document {} to signed version {}", documentId, versionId);
    }

    /** @throws SignatureException 409 if the document isn't currently locked */
    @Transactional
    public void unlock(UUID documentId, String reason, UUID actorUserId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        if (document.getSignatureLockedAt() == null) {
            throw SignatureException.conflict("DOCUMENT_NOT_SIGNATURE_LOCKED", "This document isn't locked.");
        }
        UUID unlockedVersionId = document.getSignatureLockedVersionId();
        document.setSignatureLockedAt(null);
        document.setSignatureLockedVersionId(null);
        document.setUpdatedBy(actorUserId);
        document.setUpdatedAt(OffsetDateTime.now());
        documentRepository.save(document);

        // Unlocking does NOT invalidate the signatures that locked it - they stay
        // valid for the version they signed (decision 6); nothing to touch there.
        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_UNLOCKED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .reason(reason)
                .previousValue(Map.of("signatureLockedVersionId", unlockedVersionId))
                .log();
    }
}
