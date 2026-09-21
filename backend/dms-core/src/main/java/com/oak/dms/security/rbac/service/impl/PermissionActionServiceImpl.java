package com.oak.dms.security.rbac.service.impl;

import com.oak.dms.security.rbac.dto.PermissionActionMapper;
import com.oak.dms.security.rbac.entity.Permission;
import com.oak.dms.security.rbac.entity.PermissionAction;
import com.oak.dms.security.rbac.entity.PermissionModule;
import com.oak.dms.security.rbac.repository.PermissionActionRepository;
import com.oak.dms.security.rbac.repository.PermissionModuleRepository;
import com.oak.dms.security.rbac.repository.PermissionRepository;
import com.oak.dms.security.rbac.service.PermissionActionService;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.dto.CreatePermissionActionRequest;
import com.oak.dms.security.rbac.dto.PermissionActionResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionActionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * See {@link PermissionActionService}. Middle of the permission catalog
 * hierarchy: {@link PermissionModule} (1) -> {@link PermissionAction} (many)
 * -> {@link Permission} (many). Not tenant-scoped, same as
 * {@link PermissionServiceImpl}. Deletion is a soft-delete but is blocked
 * while any non-deleted permission still references the action, mirroring
 * how {@link PermissionModuleServiceImpl#deleteModule} guards against
 * orphaning live actions one level up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PermissionActionServiceImpl implements PermissionActionService {

    private final PermissionActionRepository permissionActionRepository;
    private final PermissionModuleRepository permissionModuleRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionActionMapper permissionActionMapper;
    private final AuditService auditService;

    @Override
    public PermissionActionResponse createAction(CreatePermissionActionRequest request) {
        PermissionModule module = requireActiveModule(request.moduleId());

        if (permissionActionRepository.existsByModuleIdAndActionCodeAndDeletedFalse(
                module.getModuleId(), request.actionCode())) {
            throw new DuplicateResourceException(
                    "Module " + module.getModuleCode() + " already has an action with code " + request.actionCode());
        }

        PermissionAction action = PermissionAction.builder()
                .moduleId(module.getModuleId())
                .actionCode(request.actionCode())
                .actionName(request.actionName())
                .description(request.description())
                .createdBy(TenantContext.getUserId())
                .build();

        PermissionAction saved = permissionActionRepository.save(action);
        log.info("Created permission action {} ({}.{})", saved.getActionId(), module.getModuleCode(), saved.getActionCode());

        auditService.event(AuditActionCatalog.PERMISSION_ACTION_CREATED)
                .objectType(AuditObjectTypes.PERMISSION_ACTION)
                .objectId(saved.getActionId())
                .resourceName(module.getModuleCode() + "." + saved.getActionCode())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "moduleCode", module.getModuleCode(),
                        "actionCode", saved.getActionCode(),
                        "actionName", saved.getActionName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription()))
                .log();

        return permissionActionMapper.toResponse(saved, module.getModuleCode());
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionActionResponse getAction(UUID actionId) {
        return toEnrichedResponse(findActive(actionId), null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PermissionActionResponse> listActions(Pageable pageable) {
        return permissionActionRepository.findAllActive(pageable).map(a -> toEnrichedResponse(a, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionActionResponse> listActionsForModule(UUID moduleId) {
        PermissionModule module = requireActiveModule(moduleId);
        return permissionActionRepository.findAllByModuleIdAndDeletedFalse(moduleId).stream()
                .map(a -> permissionActionMapper.toResponse(a, module.getModuleCode()))
                .toList();
    }

    @Override
    public PermissionActionResponse updateAction(UUID actionId, UpdatePermissionActionRequest request) {
        PermissionAction action = findActive(actionId);
        Map<String, Object> previousValue = Map.of(
                "actionName", action.getActionName(),
                "description", action.getDescription() == null ? "" : action.getDescription());

        action.setActionName(request.actionName());
        action.setDescription(request.description());
        action.setUpdatedBy(TenantContext.getUserId());
        action.setUpdatedAt(OffsetDateTime.now());

        PermissionAction saved = permissionActionRepository.save(action);
        log.info("Updated permission action {}", saved.getActionId());

        auditService.event(AuditActionCatalog.PERMISSION_ACTION_UPDATED)
                .objectType(AuditObjectTypes.PERMISSION_ACTION)
                .objectId(saved.getActionId())
                .resourceName(saved.getActionCode())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "actionName", saved.getActionName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription()))
                .log();

        return toEnrichedResponse(saved, null);
    }

    @Override
    public void deleteAction(UUID actionId) {
        PermissionAction action = findActive(actionId);

        if (permissionRepository.existsByActionIdAndDeletedFalse(actionId)) {
            throw new DuplicateResourceException(
                    "Cannot delete action " + action.getActionCode() + " while it still has active permissions");
        }

        action.setDeleted(true);
        action.setDeletedAt(OffsetDateTime.now());
        action.setUpdatedBy(TenantContext.getUserId());
        action.setUpdatedAt(OffsetDateTime.now());
        permissionActionRepository.save(action);
        log.info("Deleted permission action {}", actionId);

        auditService.event(AuditActionCatalog.PERMISSION_ACTION_DELETED)
                .objectType(AuditObjectTypes.PERMISSION_ACTION)
                .objectId(actionId)
                .resourceName(action.getActionCode())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    private PermissionAction findActive(UUID actionId) {
        return permissionActionRepository.findByActionIdAndDeletedFalse(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("PermissionAction", actionId));
    }

    private PermissionModule requireActiveModule(UUID moduleId) {
        return permissionModuleRepository.findByModuleIdAndDeletedFalse(moduleId)
                .orElseThrow(() -> new ResourceNotFoundException("PermissionModule", moduleId));
    }

    /**
     * Resolves and attaches the action's module code for the response.
     * Accepts an already-known module to skip a redundant lookup right
     * after create; pass null (e.g. from a list/get read) to resolve it
     * here instead.
     */
    private PermissionActionResponse toEnrichedResponse(PermissionAction action, PermissionModule knownModule) {
        PermissionModule module = knownModule != null ? knownModule : requireActiveModule(action.getModuleId());
        return permissionActionMapper.toResponse(action, module.getModuleCode());
    }
}
