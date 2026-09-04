package com.oak.dms.security.rbac;

import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.dto.CreatePermissionRequest;
import com.oak.dms.security.rbac.dto.PermissionResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * See {@link PermissionService}. The permission catalog is global (not
 * tenant-scoped, unlike {@link Role}) - it's the platform's fixed set of
 * grantable capabilities, so every tenant's roles draw from the same list
 * via {@link RolePermission}. Deletion is a soft-delete, consistent with
 * the rest of the RBAC module (see {@link RoleServiceImpl}).
 *
 * Every permission belongs to exactly one {@link PermissionAction}, which
 * in turn belongs to exactly one {@link PermissionModule} - see
 * {@link Permission#getActionId()}. Responses are enriched with the
 * resolved action/module so callers get "MODULE / ACTION" without a
 * second request; see {@link PermissionMapper}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PermissionServiceImpl implements PermissionService {

    private final PermissionRepository permissionRepository;
    private final PermissionActionRepository permissionActionRepository;
    private final PermissionModuleRepository permissionModuleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionMapper permissionMapper;

    @Override
    public PermissionResponse createPermission(CreatePermissionRequest request) {
        if (permissionRepository.existsByPermissionCodeAndDeletedFalse(request.permissionCode())) {
            throw new DuplicateResourceException(
                    "A permission already exists with code " + request.permissionCode());
        }

        PermissionAction action = requireActiveAction(request.actionId());

        Permission permission = Permission.builder()
                .permissionCode(request.permissionCode())
                .name(request.name())
                .description(request.description())
                .actionId(action.getActionId())
                .createdBy(TenantContext.getUserId())
                .build();

        Permission saved = permissionRepository.save(permission);
        log.info("Created permission {} ({})", saved.getPermissionId(), saved.getPermissionCode());
        return toEnrichedResponse(saved, action);
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionResponse getPermission(UUID permissionId) {
        return toEnrichedResponse(findActive(permissionId), null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PermissionResponse> listPermissions(Pageable pageable) {
        return permissionRepository.findAllActive(pageable).map(p -> toEnrichedResponse(p, null));
    }

    @Override
    public PermissionResponse updatePermission(UUID permissionId, UpdatePermissionRequest request) {
        Permission permission = findActive(permissionId);
        PermissionAction action = requireActiveAction(request.actionId());

        permission.setName(request.name());
        permission.setDescription(request.description());
        permission.setActionId(action.getActionId());
        permission.setUpdatedBy(TenantContext.getUserId());
        permission.setUpdatedAt(OffsetDateTime.now());

        Permission saved = permissionRepository.save(permission);
        log.info("Updated permission {}", saved.getPermissionId());
        return toEnrichedResponse(saved, action);
    }

    @Override
    public void deletePermission(UUID permissionId) {
        Permission permission = findActive(permissionId);

        // Mirrors the same guard deleteAction/deleteModule already apply one
        // level up the hierarchy - without it, deleting a permission still
        // granted to a role would leave role_permissions rows pointing at a
        // permission_id that no longer resolves, silently breaking that
        // role's effective permission set instead of failing loudly here.
        if (rolePermissionRepository.existsByPermissionId(permissionId)) {
            throw new DuplicateResourceException(
                    "Cannot delete permission " + permission.getPermissionCode()
                            + " while it is still granted to one or more roles");
        }

        permission.setDeleted(true);
        permission.setDeletedAt(OffsetDateTime.now());
        permission.setUpdatedBy(TenantContext.getUserId());
        permission.setUpdatedAt(OffsetDateTime.now());
        permissionRepository.save(permission);
        log.info("Deleted permission {}", permissionId);
    }

    private Permission findActive(UUID permissionId) {
        return permissionRepository.findByPermissionIdAndDeletedFalse(permissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission", permissionId));
    }

    private PermissionAction requireActiveAction(UUID actionId) {
        return permissionActionRepository.findByActionIdAndDeletedFalse(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("PermissionAction", actionId));
    }

    /**
     * Resolves and attaches the permission's action + module for the
     * response. Accepts an already-known action to skip a redundant
     * lookup right after create/update; pass null (e.g. from a list/get
     * read) to resolve it here instead.
     */
    private PermissionResponse toEnrichedResponse(Permission permission, PermissionAction knownAction) {
        PermissionAction action = knownAction != null ? knownAction : requireActiveAction(permission.getActionId());
        PermissionModule module = permissionModuleRepository.findByModuleIdAndDeletedFalse(action.getModuleId())
                .orElseThrow(() -> new ResourceNotFoundException("PermissionModule", action.getModuleId()));
        return permissionMapper.toResponse(permission, action, module);
    }
}
