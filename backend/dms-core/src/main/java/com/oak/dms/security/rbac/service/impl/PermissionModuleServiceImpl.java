package com.oak.dms.security.rbac.service.impl;

import com.oak.dms.security.rbac.dto.PermissionModuleMapper;
import com.oak.dms.security.rbac.entity.Permission;
import com.oak.dms.security.rbac.entity.PermissionAction;
import com.oak.dms.security.rbac.entity.PermissionModule;
import com.oak.dms.security.rbac.repository.PermissionActionRepository;
import com.oak.dms.security.rbac.repository.PermissionModuleRepository;
import com.oak.dms.security.rbac.service.PermissionModuleService;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.dto.CreatePermissionModuleRequest;
import com.oak.dms.security.rbac.dto.PermissionModuleResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionModuleRequest;
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
 * See {@link PermissionModuleService}. Top of the permission catalog
 * hierarchy: {@link PermissionModule} (1) -> {@link PermissionAction} (many)
 * -> {@link Permission} (many). Not tenant-scoped, same as
 * {@link PermissionServiceImpl}. Deletion is a soft-delete but is blocked
 * while any non-deleted action still references the module, mirroring the
 * same guard used one level down by {@link PermissionActionServiceImpl#deleteAction}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PermissionModuleServiceImpl implements PermissionModuleService {

    private final PermissionModuleRepository permissionModuleRepository;
    private final PermissionActionRepository permissionActionRepository;
    private final PermissionModuleMapper permissionModuleMapper;
    private final AuditService auditService;

    @Override
    public PermissionModuleResponse createModule(CreatePermissionModuleRequest request) {
        if (permissionModuleRepository.existsByModuleCodeAndDeletedFalse(request.moduleCode())) {
            throw new DuplicateResourceException(
                    "A permission module already exists with code " + request.moduleCode());
        }

        PermissionModule module = PermissionModule.builder()
                .moduleCode(request.moduleCode())
                .moduleName(request.moduleName())
                .description(request.description())
                .createdBy(TenantContext.getUserId())
                .build();

        PermissionModule saved = permissionModuleRepository.save(module);
        log.info("Created permission module {} ({})", saved.getModuleId(), saved.getModuleCode());

        auditService.event(AuditActionCatalog.PERMISSION_MODULE_CREATED)
                .objectType(AuditObjectTypes.PERMISSION_MODULE)
                .objectId(saved.getModuleId())
                .resourceName(saved.getModuleCode())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "moduleCode", saved.getModuleCode(),
                        "moduleName", saved.getModuleName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription()))
                .log();

        return permissionModuleMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionModuleResponse getModule(UUID moduleId) {
        return permissionModuleMapper.toResponse(findActive(moduleId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PermissionModuleResponse> listModules(Pageable pageable) {
        return permissionModuleRepository.findAllActive(pageable).map(permissionModuleMapper::toResponse);
    }

    @Override
    public PermissionModuleResponse updateModule(UUID moduleId, UpdatePermissionModuleRequest request) {
        PermissionModule module = findActive(moduleId);
        Map<String, Object> previousValue = Map.of(
                "moduleName", module.getModuleName(),
                "description", module.getDescription() == null ? "" : module.getDescription());

        module.setModuleName(request.moduleName());
        module.setDescription(request.description());
        module.setUpdatedBy(TenantContext.getUserId());
        module.setUpdatedAt(OffsetDateTime.now());

        PermissionModule saved = permissionModuleRepository.save(module);
        log.info("Updated permission module {}", saved.getModuleId());

        auditService.event(AuditActionCatalog.PERMISSION_MODULE_UPDATED)
                .objectType(AuditObjectTypes.PERMISSION_MODULE)
                .objectId(saved.getModuleId())
                .resourceName(saved.getModuleCode())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "moduleName", saved.getModuleName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription()))
                .log();

        return permissionModuleMapper.toResponse(saved);
    }

    @Override
    public void deleteModule(UUID moduleId) {
        PermissionModule module = findActive(moduleId);

        if (permissionActionRepository.existsByModuleIdAndDeletedFalse(moduleId)) {
            throw new DuplicateResourceException(
                    "Cannot delete module " + module.getModuleCode() + " while it still has active actions");
        }

        module.setDeleted(true);
        module.setDeletedAt(OffsetDateTime.now());
        module.setUpdatedBy(TenantContext.getUserId());
        module.setUpdatedAt(OffsetDateTime.now());
        permissionModuleRepository.save(module);
        log.info("Deleted permission module {}", moduleId);

        auditService.event(AuditActionCatalog.PERMISSION_MODULE_DELETED)
                .objectType(AuditObjectTypes.PERMISSION_MODULE)
                .objectId(moduleId)
                .resourceName(module.getModuleCode())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    private PermissionModule findActive(UUID moduleId) {
        return permissionModuleRepository.findByModuleIdAndDeletedFalse(moduleId)
                .orElseThrow(() -> new ResourceNotFoundException("PermissionModule", moduleId));
    }
}
