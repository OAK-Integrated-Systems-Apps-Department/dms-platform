package com.oak.dms.compliance;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies retention rules to documents (CRM-FR-007, 012, 015) and to their old versions: moves a document to Archive once its
 * active period has elapsed, flags it for an administrator once its total retention has elapsed, and applies the rule's
 * version limits. It never deletes a document; old versions are removed in two steps (hidden, then destroyed after the
 * organisation's grace period) - see {@link VersionRetentionService}.
 *
 * <p>Safe to run any number of times and from several servers: every change is one conditional UPDATE in the database
 * ({@link DocumentRepository#changeLifecycle}), so a second run finds nothing to do, and a legal hold placed at the same
 * moment as an archive always wins (CRM-NFR-003). A document under hold is not even looked at (CRM-FR-015); when the hold is
 * lifted {@link #catchUp} applies whatever fell due meanwhile at once. At the end of a run the people concerned are told
 * (see {@link RetentionNotifier}).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RetentionEngine {

    private static final int BATCH = 200;

    /** What one run did. */
    public record Result(int archived, int flagged, int withoutPolicy, int versionsRemoved, int versionsDestroyed, int failed) {
    }

    private final DocumentRepository documentRepository;
    private final RetentionPolicyRepository policyRepository;
    private final RetentionSettingsService settingsService;
    private final FolderRepository folderRepository;
    private final AuditService auditService;
    private final VersionRetentionService versions;
    private final RetentionNotifier notifier;

    /** Counters for one pass. */
    private static final class Tally {
        int archived;
        int flagged;
        int withoutPolicy;
        int versionsRemoved;
        int failed;
    }

    /** One organisation, start to finish. A document that fails is logged and left for the next run. */
    public Result runForTenant(UUID tenantId) {
        Context context = context(tenantId);
        Tally tally = new Tally();
        RetentionNotifier.RunLog runLog = new RetentionNotifier.RunLog();
        OffsetDateTime now = OffsetDateTime.now();
        UUID after = new UUID(0L, 0L);
        while (true) {
            List<Document> batch = documentRepository.findRetentionCandidates(tenantId, after, PageRequest.of(0, BATCH));
            if (batch.isEmpty()) {
                break;
            }
            for (Document document : batch) {
                try {
                    evaluate(document, context, now, "scheduled", tally, runLog);
                } catch (RuntimeException e) {
                    tally.failed++;
                    log.warn("Retention could not be applied to document {} - will try again next run: {}",
                            document.getDocumentId(), e.getMessage());
                }
                after = document.getDocumentId();
            }
            if (batch.size() < BATCH) {
                break;
            }
        }
        int destroyed = 0;
        try {
            destroyed = versions.destroyDue(tenantId, OffsetDateTime.now());
        } catch (RuntimeException e) {
            tally.failed++;
            log.warn("Retention could not destroy removed versions for organisation {} - will try again: {}",
                    tenantId, e.getMessage());
        }
        notifier.notifyRun(tenantId, runLog);
        return new Result(tally.archived, tally.flagged, tally.withoutPolicy, tally.versionsRemoved, destroyed, tally.failed);
    }

    /**
     * One document right now - after its legal hold was lifted, so a transition that fell due during the hold happens at
     * once (CRM alternate flow) instead of waiting for the next scheduled run. Never throws: the caller's own action
     * (lifting the hold) must not fail because of this.
     */
    public void catchUp(UUID documentId, UUID tenantId) {
        try {
            Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId).orElse(null);
            if (document == null || document.isTemplate()) {
                return;
            }
            RetentionNotifier.RunLog run = new RetentionNotifier.RunLog();
            evaluate(document, context(tenantId), OffsetDateTime.now(), "legal hold lifted", new Tally(), run);
            notifier.notifyRun(tenantId, run);
        } catch (RuntimeException e) {
            log.warn("Retention catch-up for document {} failed - the scheduled run will apply it: {}", documentId, e.getMessage());
        }
    }

    /** What a rule set says about one document, without changing anything (the document page's "retention" panel). */
    public Optional<RetentionResolver.Resolution> preview(Document document) {
        Context context = context(document.getTenantId());
        return RetentionResolver.resolve(facts(document), context.policies(), context.settings().precedence(), context.folderParents());
    }

    private void evaluate(Document document, Context context, OffsetDateTime now, String trigger, Tally tally,
                          RetentionNotifier.RunLog run) {
        if (document.isLegalHold()) {
            return; // suspended entirely while the hold lasts
        }
        UUID tenantId = document.getTenantId();
        Optional<RetentionResolver.Resolution> resolution = RetentionResolver.resolve(facts(document), context.policies(),
                context.settings().precedence(), context.folderParents());
        if (resolution.isEmpty()) {
            if (RetentionSettingsService.FLAG.equals(context.settings().whenNoPolicy())
                    && documentRepository.flagNoPolicy(document.getDocumentId(), tenantId, now) == 1) {
                auditService.event(AuditActionCatalog.DOCUMENT_NO_RETENTION_POLICY).tenantId(tenantId).systemActor()
                        .objectType(AuditObjectTypes.DOCUMENT).objectId(document.getDocumentId())
                        .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                        .newValue(Map.of("action", "flagged for an administrator to assign a retention rule")).log();
                tally.withoutPolicy++;
                run.withoutPolicy(document.getDocumentId(), document.getTitle(), document.getOwnerUserId());
            }
            return;
        }
        RetentionResolver.Resolution r = resolution.get();
        // Only write when something moved - otherwise every run would rewrite every document.
        if (!r.policy().getPolicyId().equals(document.getRetentionPolicyId()) || !same(r.archiveDue(), document.getArchiveDueAt())
                || !same(r.reviewDue(), document.getReviewDueAt())) {
            documentRepository.storeRetention(document.getDocumentId(), tenantId, r.policy().getPolicyId(), r.archiveDue(),
                    r.reviewDue());
        }

        boolean archived = false;
        if (!Document.ARCHIVED.equals(document.getLifecycleState()) && !r.archiveDue().isAfter(now)) {
            archived = documentRepository.changeLifecycle(document.getDocumentId(), tenantId,
                    List.of(Document.ACTIVE, Document.INACTIVE), Document.ARCHIVED, false, "retention", null,
                    "Active retention period elapsed (" + r.policy().getName() + ")", now) == 1;
            if (archived) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", document.getLifecycleState());
                change.put("to", Document.ARCHIVED);
                change.put("rule", r.policy().getName());
                change.put("framework", r.policy().getFramework());
                change.put("archiveDue", r.archiveDue().toString());
                change.put("trigger", trigger);
                auditService.event(AuditActionCatalog.DOCUMENT_AUTO_ARCHIVED).tenantId(tenantId).systemActor()
                        .objectType(AuditObjectTypes.DOCUMENT).objectId(document.getDocumentId())
                        .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS).newValue(change).log();
                tally.archived++;
                run.archived(document.getDocumentId(), document.getTitle(), document.getOwnerUserId());
            }
        }
        boolean isArchived = archived || Document.ARCHIVED.equals(document.getLifecycleState());
        if (isArchived && !r.reviewDue().isAfter(now)
                && documentRepository.flagForReview(document.getDocumentId(), tenantId, now) == 1) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("rule", r.policy().getName());
            change.put("framework", r.policy().getFramework());
            change.put("totalRetentionEnded", r.reviewDue().toString());
            change.put("trigger", trigger);
            auditService.event(AuditActionCatalog.DOCUMENT_RETENTION_FLAGGED).tenantId(tenantId).systemActor()
                    .objectType(AuditObjectTypes.DOCUMENT).objectId(document.getDocumentId())
                    .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS).newValue(change).log();
            tally.flagged++;
            run.flagged(document.getDocumentId(), document.getTitle(), document.getOwnerUserId());
        }
        // The same rule's limits on old versions.
        tally.versionsRemoved += versions.removeOldVersions(document, r.policy(), now);
    }

    private static boolean same(OffsetDateTime a, OffsetDateTime b) {
        return a != null && b != null && a.toInstant().equals(b.toInstant());
    }

    private static RetentionResolver.Facts facts(Document d) {
        return new RetentionResolver.Facts(d.getDocumentTypeId(), d.getOwnerDepartmentId(), d.getFolderId(), d.getCreatedAt(),
                d.getUpdatedAt(), d.getReactivatedAt(), d.getRetentionExtendedUntil());
    }

    private record Context(List<RetentionPolicy> policies, RetentionSettingsService.Settings settings,
                           Map<UUID, UUID> folderParents) {
    }

    private Context context(UUID tenantId) {
        Map<UUID, UUID> parents = new HashMap<>();
        for (Folder folder : folderRepository.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(tenantId)) {
            parents.put(folder.getFolderId(), folder.getParentFolderId());
        }
        return new Context(policyRepository.findByTenantIdAndDeletedFalseAndEnabledTrue(tenantId),
                settingsService.forTenant(tenantId), parents);
    }
}
