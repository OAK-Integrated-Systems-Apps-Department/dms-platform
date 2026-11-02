package com.oak.dms.ownership.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.ownership.dto.DocumentOwnershipInfo;
import com.oak.dms.ownership.dto.PersonRef;
import com.oak.dms.ownership.helper.OwnedDocument;
import com.oak.dms.ownership.helper.OwnershipLocks;
import com.oak.dms.ownership.helper.OwnershipNames;
import com.oak.dms.ownership.helper.OwnershipScope;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The document page's Owner field: owner, department, state and whether the caller may transfer it. */
@Service
@RequiredArgsConstructor
public class OwnershipInfoService {

    private final OwnershipLocks locks;
    private final OwnershipNames names;
    private final OwnershipScope ownershipScope;
    private final DocumentAccessService documentAccess;
    private final AuthorizationService authorization;

    @Transactional(readOnly = true)
    public DocumentOwnershipInfo info(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        List<OwnedDocument> found = locks.readDocuments(tenantId, Set.of(documentId));
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        OwnedDocument document = found.get(0);
        boolean admin = authorization.isTenantAdmin();
        if (!admin && !documentAccess.forCaller().canSee(document.accessStub())) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        Map<UUID, PersonRef> people = names.persons(tenantId, List.of(document.ownerUserId()));
        var department = document.ownerDepartmentId() == null ? null
                : names.departments(tenantId, Collections.singletonList(document.ownerDepartmentId())).get(document.ownerDepartmentId());
        boolean canTransfer = authorization.hasPermission(PermissionCodes.DOT_TRANSFER_INITIATE);
        if (canTransfer && !admin) {
            var scope = ownershipScope.forCaller(tenantId, TenantContext.getUserId());
            canTransfer = !scope.empty() && ownershipScope.ownerInScope(tenantId, scope, document.ownerUserId());
        }
        return new DocumentOwnershipInfo(documentId, names.person(people, document.ownerUserId(), null), department,
                document.ownershipState(), canTransfer);
    }
}
