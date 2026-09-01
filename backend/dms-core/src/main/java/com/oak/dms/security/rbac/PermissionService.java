package com.oak.dms.security.rbac;

import com.oak.dms.security.rbac.dto.CreatePermissionRequest;
import com.oak.dms.security.rbac.dto.PermissionResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Lifecycle of the platform-wide permission catalog: create, lookup, list,
 * update (metadata only, not the code), delete. Not tenant-scoped - see
 * {@link Permission}.
 */
public interface PermissionService {
    PermissionResponse createPermission(CreatePermissionRequest request);

    PermissionResponse getPermission(UUID permissionId);

    Page<PermissionResponse> listPermissions(Pageable pageable);

    PermissionResponse updatePermission(UUID permissionId, UpdatePermissionRequest request);

    void deletePermission(UUID permissionId);
}
