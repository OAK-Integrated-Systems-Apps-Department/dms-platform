package com.oak.dms.compliance;

import com.oak.dms.document.Document;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * When a retention rule WILL archive documents, worked out live for the list badge: a document uploaded a moment ago has no
 * stored date yet (the retention job stores them), but its "archives on ..." hover should not wait for the next run.
 *
 * <p>Deliberately light - it needs only the rules, the organisation's settings and its folder tree, so the document listing
 * can use it without pulling in the retention engine and everything that engine depends on. An organisation with no rules
 * costs one small query.
 */
@Component
@RequiredArgsConstructor
public class RetentionDueDates {

    private final RetentionPolicyRepository policyRepository;
    private final RetentionSettingsService settingsService;
    private final FolderRepository folderRepository;

    /** The archive date for each given document a rule covers; documents no rule covers are simply absent. */
    public Map<UUID, OffsetDateTime> archiveDueFor(UUID tenantId, Collection<Document> documents) {
        Map<UUID, OffsetDateTime> due = new HashMap<>();
        if (documents.isEmpty()) {
            return due;
        }
        List<RetentionPolicy> policies = policyRepository.findByTenantIdAndDeletedFalseAndEnabledTrue(tenantId);
        if (policies.isEmpty()) {
            return due;
        }
        String precedence = settingsService.forTenant(tenantId).precedence();
        Map<UUID, UUID> parents = new HashMap<>();
        for (Folder folder : folderRepository.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(tenantId)) {
            parents.put(folder.getFolderId(), folder.getParentFolderId());
        }
        for (Document d : documents) {
            RetentionResolver.resolve(new RetentionResolver.Facts(d.getDocumentTypeId(), d.getOwnerDepartmentId(), d.getFolderId(),
                            d.getCreatedAt(), d.getUpdatedAt(), d.getReactivatedAt(), d.getRetentionExtendedUntil()),
                    policies, precedence, parents).ifPresent(r -> due.put(d.getDocumentId(), r.archiveDue()));
        }
        return due;
    }
}
