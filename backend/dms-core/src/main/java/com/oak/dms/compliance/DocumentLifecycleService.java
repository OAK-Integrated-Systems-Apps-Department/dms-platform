package com.oak.dms.compliance;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentApprovals;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentService;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.relationship.repository.DocumentRelationshipRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A document's lifecycle by hand and by review (CRM-FR-001 .. 005, 012, 013 .. 018): switching it between Active and
 * Inactive, showing where it stands in its retention, and what an administrator decides once its total retention has
 * elapsed (keep it longer, or send it to the recycle bin - never a deletion here).
 *
 * <p>Every change is a conditional database update that refuses a document under legal hold, so the hold is verifiably
 * stronger than any status change (CRM-NFR-003), and every change is audited with who did it and why (CRM-FR-018).
 */
@Service
@RequiredArgsConstructor
public class DocumentLifecycleService {

    private final DocumentRepository documentRepository;
    private final DocumentAccessService documentAccessService;
    private final AuthorizationService authorizationService;
    private final RetentionSettingsService settingsService;
    private final RetentionEngine engine;
    private final RetentionPolicyRepository policyRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentRelationshipRepository relationshipRepository;
    private final DocumentApprovals approvals;
    private final DocumentService documentService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    /** What the document page shows about a document's lifecycle and retention. */
    public record RetentionView(String lifecycleState, String changeSource, String changeReason, OffsetDateTime changedAt,
                                String changedByName, boolean legalHold, String legalHoldReason, UUID policyId,
                                String policyName, String framework, String activePeriod, String archivePeriod,
                                OffsetDateTime archiveDueAt, OffsetDateTime reviewDueAt, OffsetDateTime reviewFlaggedAt,
                                OffsetDateTime extendedUntil, boolean needsPolicy, boolean automationEnabled) {
    }

    /** A document waiting for an administrator (past its total retention, or with no rule covering it). */
    public record ReviewItem(UUID documentId, String title, UUID documentTypeId, String documentTypeName, UUID folderId,
                             String lifecycleState, String policyName, String framework, OffsetDateTime reviewDueAt,
                             OffsetDateTime flaggedAt) {
    }

    // ---------------------------------------------------------------- manual status (CRM-FR-002 / 003 / 014)

    /**
     * Switches a document to {@code active} or {@code inactive}. Switching to Inactive needs
     * {@code crm.change-document-status.mark-inactive}; back to Active needs {@code ...mark-active}; both need edit access
     * to the document. A document under legal hold cannot be switched either way.
     *
     * @throws ComplianceException 409 {@code DOCUMENT_ON_LEGAL_HOLD} (message names the hold and its reason),
     *                             409 {@code DOCUMENT_STATUS_UNCHANGED}, 400 {@code STATUS_REASON_REQUIRED} / {@code STATUS_INVALID}
     */
    public RetentionView changeStatus(UUID documentId, String target, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        String to = target == null ? "" : target.strip().toLowerCase(Locale.ROOT);
        if (!Document.ACTIVE.equals(to) && !Document.INACTIVE.equals(to)) {
            throw ComplianceException.badRequest("STATUS_INVALID", "A document can be switched to \"active\" or \"inactive\".");
        }
        String needed = Document.INACTIVE.equals(to) ? PermissionCodes.CRM_MARK_INACTIVE : PermissionCodes.CRM_MARK_ACTIVE;
        if (!authorizationService.hasPermission(needed)) {
            throw new AccessDeniedException("You are not allowed to mark documents " + to + ".");
        }
        Document document = find(documentId, tenantId);
        documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.edit);
        guardNotOnHold(document);

        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why == null && settingsService.forTenant(tenantId).requireStatusReason()) {
            throw ComplianceException.badRequest("STATUS_REASON_REQUIRED", "Give a reason for changing the status.");
        }
        String before = document.getLifecycleState();
        if (before.equals(to)) {
            throw ComplianceException.conflict("DOCUMENT_STATUS_UNCHANGED", "\"" + document.getTitle() + "\" is already " + to + ".");
        }
        if (Document.INACTIVE.equals(to) && Document.ARCHIVED.equals(before)) {
            throw ComplianceException.conflict("DOCUMENT_ALREADY_ARCHIVED",
                    "\"" + document.getTitle() + "\" is archived. Mark it active to bring it back.");
        }
        boolean reactivate = Document.ACTIVE.equals(to);
        List<String> from = reactivate ? List.of(Document.INACTIVE, Document.ARCHIVED) : List.of(Document.ACTIVE);
        int changed = documentRepository.changeLifecycle(documentId, tenantId, from, to, reactivate, "manual",
                TenantContext.getUserId(), why, OffsetDateTime.now());
        if (changed == 0) {
            // Lost a race: a hold was placed, or someone else changed the status first. Say which.
            Document now = find(documentId, tenantId);
            guardNotOnHold(now);
            throw ComplianceException.conflict("DOCUMENT_STATUS_CHANGED_MEANWHILE",
                    "Someone else just changed this document's status. It is now " + now.getLifecycleState() + ".");
        }
        Map<String, Object> previous = Map.of("state", before);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("state", to);
        change.put("source", "manual");
        auditService.event(AuditActionCatalog.DOCUMENT_STATUS_CHANGED).objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId).resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                .reason(why).previousValue(previous).newValue(change).log();
        return retention(documentId);
    }

    private void guardNotOnHold(Document document) {
        if (document.isLegalHold()) {
            String why = document.getLegalHoldReason();
            throw ComplianceException.conflict("DOCUMENT_ON_LEGAL_HOLD", "\"" + document.getTitle()
                    + "\" is under legal hold" + (why == null || why.isBlank() ? "" : " (" + why + ")")
                    + ", so its status can't be changed until the hold is lifted.");
        }
    }

    // ---------------------------------------------------------------- the document page's panel

    @Transactional(readOnly = true)
    public RetentionView retention(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = find(documentId, tenantId);
        documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.view);
        RetentionSettingsService.Settings settings = settingsService.forTenant(tenantId);

        var resolution = engine.preview(document);
        RetentionPolicy policy = resolution.map(RetentionResolver.Resolution::policy).orElse(null);
        OffsetDateTime archiveDue = resolution.map(RetentionResolver.Resolution::archiveDue).orElse(null);
        OffsetDateTime reviewDue = resolution.map(RetentionResolver.Resolution::reviewDue).orElse(null);
        String changedBy = document.getLifecycleChangedBy() == null ? null : userRepository.findById(document.getLifecycleChangedBy())
                .map(User::getDisplayName).orElse(null);
        if (document.getLifecycleChangedBy() == null && document.getLifecycleChangedAt() != null) {
            changedBy = "The retention schedule";
        }
        return new RetentionView(document.getLifecycleState(), document.getLifecycleChangeSource(),
                document.getLifecycleReason(), document.getLifecycleChangedAt(), changedBy, document.isLegalHold(),
                document.getLegalHoldReason(), policy == null ? null : policy.getPolicyId(),
                policy == null ? null : policy.getName(), policy == null ? null : policy.getFramework(),
                policy == null ? null : policy.getActiveAmount() + " " + policy.getActiveUnit(),
                policy == null ? null : policy.getArchiveAmount() + " " + policy.getArchiveUnit(),
                Document.ARCHIVED.equals(document.getLifecycleState()) ? null : archiveDue, reviewDue,
                document.getReviewFlaggedAt(), document.getRetentionExtendedUntil(),
                policy == null && !document.isTemplate(), settings.automationEnabled());
    }

    // ---------------------------------------------------------------- review (CRM-FR-012 and the alternate flows)

    @Transactional(readOnly = true)
    public Page<ReviewItem> awaitingReview(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        return toReviewItems(documentRepository.findAwaitingReview(tenantId, pageable), documentAccessService.forCaller());
    }

    @Transactional(readOnly = true)
    public Page<ReviewItem> withoutPolicy(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        return toReviewItems(documentRepository.findWithoutPolicy(tenantId, pageable), documentAccessService.forCaller());
    }

    /**
     * The review lists name documents, so a reviewer sees only the ones they could open anyway - the same access rules as the
     * document browser. Holding the review permission lets someone decide about a document; it does not show them titles in
     * a restricted folder they have no access to.
     */
    private Page<ReviewItem> toReviewItems(Page<Document> page, DocumentAccessService.DocumentAccessView access) {
        List<Document> visible = page.getContent().stream().filter(access::canSee).toList();
        Map<UUID, String> types = new HashMap<>();
        documentTypeRepository.findAllById(visible.stream().map(Document::getDocumentTypeId).distinct().toList())
                .forEach((DocumentType t) -> types.put(t.getDocumentTypeId(), t.getTypeName()));
        Map<UUID, RetentionPolicy> policies = new HashMap<>();
        policyRepository.findAllById(visible.stream().map(Document::getRetentionPolicyId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(p -> policies.put(p.getPolicyId(), p));
        return new PageImpl<>(visible.stream().map(d -> {
            RetentionPolicy p = d.getRetentionPolicyId() == null ? null : policies.get(d.getRetentionPolicyId());
            return new ReviewItem(d.getDocumentId(), d.getTitle(), d.getDocumentTypeId(), types.get(d.getDocumentTypeId()),
                    d.getFolderId(), d.getLifecycleState(), p == null ? null : p.getName(), p == null ? null : p.getFramework(),
                    d.getReviewDueAt(), d.getReviewFlaggedAt());
        }).toList(), page.getPageable(), page.getTotalElements());
    }

    /**
     * Keeps a flagged document longer (the alternate flow: "extend rather than proceed to deletion"): the flag clears and
     * its review date moves out by the given span from today.
     */
    public RetentionView extend(UUID documentId, int amount, String unit, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        String u = unit == null ? "" : unit.strip().toLowerCase(Locale.ROOT);
        if (amount < 1 || amount > 1000 || !RetentionSpan.UNITS.contains(u)) {
            throw ComplianceException.badRequest("RETENTION_EXTENSION_INVALID", "Extend by 1 to 1000 days, months or years.");
        }
        if (reason == null || reason.isBlank()) {
            throw ComplianceException.badRequest("REASON_REQUIRED", "Give a reason for extending the retention.");
        }
        Document document = find(documentId, tenantId);
        documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.view);
        OffsetDateTime until = new RetentionSpan(amount, u).addTo(OffsetDateTime.now());
        if (documentRepository.extendRetention(documentId, tenantId, until) == 0) {
            throw ComplianceException.conflict("DOCUMENT_NOT_AWAITING_REVIEW",
                    "\"" + document.getTitle() + "\" isn't waiting for a retention review.");
        }
        auditService.event(AuditActionCatalog.DOCUMENT_RETENTION_EXTENDED).objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId).resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                .reason(reason.strip()).newValue(Map.of("retainedUntil", until.toString(), "extension", amount + " " + u)).log();
        return retention(documentId);
    }

    /**
     * Sends a flagged document to the recycle bin (where its own recycle-bin retention then runs) - never a permanent
     * deletion here. Refused while the document is under legal hold, in an active approval, or linked to other documents
     * (CRM alternate flow: "still referenced by another active record or workflow").
     */
    public void dispose(UUID documentId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        if (reason == null || reason.isBlank()) {
            throw ComplianceException.badRequest("REASON_REQUIRED", "Give a reason for disposing of the document.");
        }
        Document document = find(documentId, tenantId);
        documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.edit);
        if (document.getReviewFlaggedAt() == null) {
            throw ComplianceException.conflict("DOCUMENT_NOT_AWAITING_REVIEW",
                    "\"" + document.getTitle() + "\" hasn't reached the end of its retention, so it can't be disposed of.");
        }
        if (document.isLegalHold()) {
            throw ComplianceException.conflict("DOCUMENT_ON_LEGAL_HOLD", "\"" + document.getTitle()
                    + "\" is under legal hold, so it can't be disposed of until the hold is lifted.");
        }
        if (approvals.inProgress(tenantId, documentId)) {
            throw ComplianceException.conflict("DOCUMENT_REFERENCED",
                    "\"" + document.getTitle() + "\" is in an active approval. Finish or cancel it first.");
        }
        int links = relationshipRepository.findActiveTouching(tenantId, documentId).size();
        if (links > 0) {
            throw ComplianceException.conflict("DOCUMENT_REFERENCED", "\"" + document.getTitle() + "\" is linked to " + links
                    + " other document" + (links == 1 ? "" : "s") + ". Remove the links first.");
        }
        // Used as a supporting document in an approval that is still running: that approval needs it.
        Long supporting = jdbc.queryForObject("""
                select count(*) from workflow_supporting_documents w join workflow_instances i on i.instance_id = w.instance_id
                where w.tenant_id = ? and w.document_id = ? and not w.is_deleted and i.status = 'in_progress'
                """, Long.class, tenantId, documentId);
        if (supporting != null && supporting > 0) {
            throw ComplianceException.conflict("DOCUMENT_REFERENCED", "\"" + document.getTitle()
                    + "\" is a supporting document in an approval that is still in progress. Finish or cancel the approval first.");
        }
        documentService.deleteDocument(documentId);
        auditService.event(AuditActionCatalog.DOCUMENT_RETENTION_DISPOSED).objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId).resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                .reason(reason.strip()).newValue(Map.of("action", "moved to the recycle bin")).log();
    }

    private Document find(UUID documentId, UUID tenantId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .filter(d -> !d.isTemplate())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }
}
