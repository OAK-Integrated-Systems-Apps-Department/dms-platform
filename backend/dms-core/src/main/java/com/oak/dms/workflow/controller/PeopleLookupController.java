package com.oak.dms.workflow.controller;

import com.oak.dms.common.sql.LikePatterns;
import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.dto.PersonOptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * People picker for @mentions and information requests (Phase 2 S6). Unlike
 * /workflow/lookups/users (template designers only), any user who can
 * comment, request information, approve or reassign may search active
 * users of their own tenant. hasDocumentAccess flags who could open the
 * document - they may still be mentioned or asked (decisions 10, 15).
 */
@RestController
@RequestMapping("/api/v1/people")
@RequiredArgsConstructor
@Tag(name = "People lookup")
public class PeopleLookupController {

    private static final int MAX_LIMIT = 50;

    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final DocumentRepository documentRepository;
    private final DocumentAccessService documentAccessService;
    private final UserPermissions userPermissions;

    @GetMapping("/lookup")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_COMMENTS_CREATE, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_REQUEST_INFORMATION, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_APPROVE, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_REJECT, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_REASSIGN)")
    @Operation(summary = "Search active users of my tenant by name, username or email",
            description = "documentId: work hasDocumentAccess out for that one document (the permission plus "
                    + "folder/document access); without it, the permission alone.")
    @Transactional(readOnly = true)
    public List<PersonOptionResponse> lookup(@RequestParam(required = false) String search,
                                             @RequestParam(required = false) UUID documentId,
                                             @RequestParam(defaultValue = "20") int limit) {
        UUID tenantId = TenantContext.getTenantId();
        String pattern = search == null || search.isBlank() ? null : LikePatterns.contains(search.trim());
        List<User> users = userRepository.searchActive(tenantId, pattern,
                PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_LIMIT)));
        List<UUID> userIds = users.stream().map(User::getUserId).toList();
        Set<UUID> holdPermission = userPermissions.filterHoldingAny(tenantId, userIds,
                PermissionCodes.DOCM_DOCUMENTS_VIEW);
        // Named a document? Then "has document access" means that document, for each of them
        // (V53/V54 plus workflow-scoped read access) - not just the permission. A document the
        // caller can't open themselves is treated as not named at all, so the flags can never
        // tell them whether it exists.
        Document document = documentId == null ? null
                : documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .filter(documentAccessService.forCaller()::canSee).orElse(null);
        Set<UUID> withAccess;
        if (document == null) {
            withAccess = holdPermission;
        } else {
            Map<UUID, DocumentAccessView> access = documentAccessService.forUsers(tenantId, holdPermission);
            withAccess = new HashSet<>();
            for (UUID id : holdPermission) {
                if (access.get(id).canSee(document)) {
                    withAccess.add(id);
                }
            }
        }
        Map<UUID, String> departments = new HashMap<>();
        departmentRepository.findByDepartmentIdInAndTenantIdAndDeletedFalse(
                        users.stream().map(User::getDepartmentId).filter(Objects::nonNull).toList(), tenantId)
                .forEach((Department d) -> departments.put(d.getDepartmentId(), d.getName()));
        return users.stream()
                .map(u -> new PersonOptionResponse(u.getUserId(), u.getDisplayName(), u.getUsername(),
                        departments.get(u.getDepartmentId()), withAccess.contains(u.getUserId())))
                .toList();
    }
}
