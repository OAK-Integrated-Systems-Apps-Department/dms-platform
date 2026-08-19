package com.oak.dms.security.auth;

import com.oak.dms.security.rbac.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Data-driven, fine-grained permission checks on top of the
 * role_permissions / user_roles tables, complementing the coarse
 * ROLE_* checks derived straight from the Keycloak token.
 *
 * Usage in controllers:
 *   {@code @PreAuthorize("@authz.hasPermission('user.manage')")}
 */
@Component("authz")
@RequiredArgsConstructor
public class AuthorizationService {

    private final RolePermissionRepository rolePermissionRepository;

    public boolean hasPermission(String permissionCode) {
        Set<String> effective = effectivePermissions();
        return effective.contains(permissionCode);
    }

    public boolean hasAnyPermission(String... permissionCodes) {
        Set<String> effective = effectivePermissions();
        for (String code : permissionCodes) {
            if (effective.contains(code)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> effectivePermissions() {
        List<String> codes = rolePermissionRepository.findEffectivePermissionCodes(
                TenantContext.getUserId(), TenantContext.getTenantId());
        return Set.copyOf(codes);
    }
}
