package com.oak.dms.notification.helper;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decides, per recipient, whether a notification may name and link a document
 * (3.2, decision 7 / N9): "can this person open it" is
 * {@link DocumentAccessService#forUsers} + {@link DocumentAccessView#canSee},
 * which already includes workflow-scoped read for the people an approval is
 * waiting on - plus, when asked ({@code requireViewPermission}, used by
 * mentions), the {@code docm.documents.view} permission. One call covers every
 * recipient.
 * <p>
 * Notifications are written in the triggering transaction, before commit, and
 * the workflow-scoped check reads through plain JDBC - so pending changes are
 * flushed first, or a freshly assigned approver would be judged to have no
 * access to the document they were just asked to approve.
 */
@Component
public class NotificationAccessResolver {

    private final DocumentAccessService documentAccess;
    private final DocumentRepository documentRepository;
    private final EntityManager entityManager;
    private final UserPermissions userPermissions;

    public NotificationAccessResolver(DocumentAccessService documentAccess, DocumentRepository documentRepository,
                                      EntityManager entityManager, UserPermissions userPermissions) {
        this.documentAccess = documentAccess;
        this.documentRepository = documentRepository;
        this.entityManager = entityManager;
        this.userPermissions = userPermissions;
    }

    /** The subset of {@code recipientIds} who can open the document. Unknown or other-tenant document: nobody. */
    public Set<UUID> canOpen(UUID tenantId, UUID documentId, Collection<UUID> recipientIds,
                             boolean requireViewPermission) {
        Document document = documentRepository.findById(documentId)
                .filter(d -> tenantId.equals(d.getTenantId()))
                .orElse(null);
        if (document == null || recipientIds.isEmpty()) {
            return Set.of();
        }
        entityManager.flush();
        // Mentions (decision 10) also need the permission; everything else follows the access check alone.
        Set<UUID> mayView = requireViewPermission
                ? userPermissions.filterHoldingAny(tenantId, recipientIds, PermissionCodes.DOCM_DOCUMENTS_VIEW)
                : null;
        Map<UUID, DocumentAccessView> views = documentAccess.forUsers(tenantId, recipientIds);
        return views.entrySet().stream()
                .filter(e -> (mayView == null || mayView.contains(e.getKey())) && e.getValue().canSee(document))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
