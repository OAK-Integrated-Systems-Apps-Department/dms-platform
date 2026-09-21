package com.oak.dms.security.rbac.service.impl;

import com.oak.dms.security.rbac.mapper.PermissionMapper;
import com.oak.dms.security.rbac.entity.Permission;
import com.oak.dms.security.rbac.entity.PermissionAction;
import com.oak.dms.security.rbac.entity.PermissionModule;
import com.oak.dms.security.rbac.repository.PermissionActionRepository;
import com.oak.dms.security.rbac.repository.PermissionModuleRepository;
import com.oak.dms.security.rbac.repository.PermissionRepository;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.service.PermissionService;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.InvalidPermissionStateException;
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
import java.util.Map;
import java.util.UUID;

/**
 * See {@link PermissionService}. Hybrid tenant-scoping (see {@link Permission}'s
 * doc comment for the full rationale): every permission this creates
 * belongs to the caller's own tenant - there's no way to create a shared/
 * platform-wide permission through this API, only via a migration. Reads
 * (get/list) return the shared catalog plus the caller's own tenant's
 * permissions; writes (update/delete) only ever succeed against the
 * caller's own tenant's permissions - the shared catalog is read-only from
 * every tenant's perspective, since one tenant renaming or deleting it
 * would affect every other tenant relying on it too.
 * <p>
 * Deletion is a soft-delete, consistent with the rest of the RBAC module
 * (see {@link RoleServiceImpl}), and - like {@link RoleServiceImpl#deleteRole} -
 * blocked while any role still holds the permission (see {@link #deletePermission}).
 * <p>
 * Every permission belongs to exactly one {@link PermissionAction}, which
 * in turn belongs to exactly one {@link PermissionModule} - see
 * {@link Permission#getActionId()}. Both stay fully global regardless of
 * which tenant a permission itself belongs to - see {@link Permission}.
 * Responses are enriched with the resolved action/module so callers get
 * "MODULE / ACTION" without a second request; see {@link PermissionMapper}.
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
    private final AuditService auditService;

    @Override
    public PermissionResponse createPermission(CreatePermissionRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        // Every code created here becomes this tenant's own - so it must
        // be free both within the shared catalog (a tenant shouldn't be
        // able to silently shadow a platform-default permission other
        // tenants also rely on) and within this tenant's own existing
        // custom permissions. Two *different* tenants independently using
        // the same code is fine and expected - see the partial unique
        // indexes in V11__add_tenant_scoping_to_permissions.sql, which
        // only enforce each of these two checks separately and can't
        // express "doesn't collide with the shared catalog either" as a
        // single DB constraint.
        if (permissionRepository.existsByPermissionCodeAndTenantIdIsNullAndDeletedFalse(request.permissionCode())) {
            throw new DuplicateResourceException(
                    "'" + request.permissionCode() + "' is already used by the platform's shared permission catalog");
        }
        if (permissionRepository.existsByPermissionCodeAndTenantIdAndDeletedFalse(request.permissionCode(), tenantId)) {
            throw new DuplicateResourceException(
                    "A permission already exists with code " + request.permissionCode());
        }

        PermissionAction action = requireActiveAction(request.actionId());

        Permission permission = Permission.builder()
                .permissionCode(request.permissionCode())
                .name(request.name())
                .description(request.description())
                .tenantId(tenantId)
                .actionId(action.getActionId())
                .createdBy(TenantContext.getUserId())
                .build();

        Permission saved = permissionRepository.save(permission);
        log.info("Created permission {} ({}) for tenant {}", saved.getPermissionId(), saved.getPermissionCode(), tenantId);

        auditService.event(AuditActionCatalog.PERMISSION_CREATED)
                .objectType(AuditObjectTypes.PERMISSION)
                .objectId(saved.getPermissionId())
                .resourceName(saved.getPermissionCode())
                .permissionCode(saved.getPermissionCode())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "permissionCode", saved.getPermissionCode(),
                        "name", saved.getName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription(),
                        "actionId", saved.getActionId()))
                .log();

        return toEnrichedResponse(saved, action);
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionResponse getPermission(UUID permissionId) {
        return toEnrichedResponse(findVisible(permissionId), null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PermissionResponse> listPermissions(Pageable pageable) {
        return permissionRepository.findAllVisibleToTenant(TenantContext.getTenantId(), pageable)
                .map(p -> toEnrichedResponse(p, null));
    }

    @Override
    public PermissionResponse updatePermission(UUID permissionId, UpdatePermissionRequest request) {
        Permission permission = requireOwnedByCallerTenant(permissionId);
        PermissionAction action = requireActiveAction(request.actionId());

        Map<String, Object> previousValue = Map.of(
                "name", permission.getName(),
                "description", permission.getDescription() == null ? "" : permission.getDescription(),
                "actionId", permission.getActionId());

        permission.setName(request.name());
        permission.setDescription(request.description());
        permission.setActionId(action.getActionId());
        permission.setUpdatedBy(TenantContext.getUserId());
        permission.setUpdatedAt(OffsetDateTime.now());

        Permission saved = permissionRepository.save(permission);
        log.info("Updated permission {}", saved.getPermissionId());

        auditService.event(AuditActionCatalog.PERMISSION_UPDATED)
                .objectType(AuditObjectTypes.PERMISSION)
                .objectId(saved.getPermissionId())
                .resourceName(saved.getPermissionCode())
                .permissionCode(saved.getPermissionCode())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "name", saved.getName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription(),
                        "actionId", saved.getActionId()))
                .log();

        return toEnrichedResponse(saved, action);
    }

    @Override
    public void deletePermission(UUID permissionId) {
        Permission permission = requireOwnedByCallerTenant(permissionId);

        // Mirrors deleteAction/deleteModule one level up - a permission
        // still granted to any role can't be removed. Without this, the
        // permission disappears from the Permission Catalog (findVisible
        // above excludes it) while role_permissions still nominally
        // grants it - not a live access leak (findEffectivePermissionCodes
        // already filters p.deleted = false, so it never actually applies
        // to anyone), but an orphaned reference an admin has no way to see
        // or knowingly clean up: Role Permission Configuration would keep
        // listing a permission code that no longer resolves to anything
        // visible in the catalog.
        if (rolePermissionRepository.existsByPermissionId(permissionId)) {
            throw new InvalidPermissionStateException(
                    "Cannot delete permission " + permission.getPermissionCode()
                            + " while it is still granted to one or more roles");
        }

        permission.setDeleted(true);
        permission.setDeletedAt(OffsetDateTime.now());
        permission.setUpdatedBy(TenantContext.getUserId());
        permission.setUpdatedAt(OffsetDateTime.now());
        permissionRepository.save(permission);
        log.info("Deleted permission {}", permissionId);

        auditService.event(AuditActionCatalog.PERMISSION_DELETED)
                .objectType(AuditObjectTypes.PERMISSION)
                .objectId(permissionId)
                .resourceName(permission.getPermissionCode())
                .permissionCode(permission.getPermissionCode())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    /** Visible to the caller (shared, or owned by their tenant) - used by reads, which are allowed to return shared permissions. */
    private Permission findVisible(UUID permissionId) {
        return permissionRepository.findVisibleToTenant(permissionId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Permission", permissionId));
    }

    /**
     * Owned by the caller's own tenant specifically - used by update/delete,
     * which must never succeed against the shared catalog even though reads
     * can see it. A shared permission resolves fine via {@link #findVisible}
     * but fails this check, since {@code tenantId} is null for it - the
     * distinct message here (rather than a plain not-found) tells the
     * caller why, instead of implying the permission doesn't exist at all
     * when it does, just not editable by them.
     */
    private Permission requireOwnedByCallerTenant(UUID permissionId) {
        Permission permission = findVisible(permissionId);
        if (permission.getTenantId() == null) {
            throw new InvalidPermissionStateException(
                    "'" + permission.getPermissionCode() + "' is part of the platform's shared permission "
                            + "catalog and can't be edited or deleted from a single tenant.");
        }
        return permission;
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
