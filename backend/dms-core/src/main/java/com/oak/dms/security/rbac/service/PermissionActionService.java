package com.oak.dms.security.rbac.service;

import com.oak.dms.security.rbac.entity.PermissionModule;

import com.oak.dms.security.rbac.dto.CreatePermissionActionRequest;
import com.oak.dms.security.rbac.dto.PermissionActionResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionActionRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Lifecycle of the platform-wide permission action catalog: create,
 * lookup, list (optionally scoped to one module), update (metadata only,
 * not the code), delete. Not tenant-scoped - see {@link PermissionModule}.
 */
public interface PermissionActionService {
    PermissionActionResponse createAction(CreatePermissionActionRequest request);

    PermissionActionResponse getAction(UUID actionId);

    Page<PermissionActionResponse> listActions(Pageable pageable);

    List<PermissionActionResponse> listActionsForModule(UUID moduleId);

    PermissionActionResponse updateAction(UUID actionId, UpdatePermissionActionRequest request);

    void deleteAction(UUID actionId);
}
