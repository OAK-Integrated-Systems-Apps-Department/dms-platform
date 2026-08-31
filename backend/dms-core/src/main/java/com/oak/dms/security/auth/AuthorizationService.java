package com.oak.dms.security.auth;

import com.oak.dms.security.rbac.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
        return hasFullAccess() || effectivePermissions().contains(permissionCode);
    }

    public boolean hasAnyPermission(String... permissionCodes) {
        if (hasFullAccess()) {
            return true;
        }
        Set<String> effective = effectivePermissions();
        for (String code : permissionCodes) {
            if (effective.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * TENANT_ADMIN ("full administrative access within one tenant") and
     * PLATFORM_ADMIN ("cross-tenant platform administrator") are Keycloak
     * realm roles whose own descriptions already say they mean full
     * access - so both trivially satisfy every fine-grained permission
     * check here, without needing a matching row in role_permissions for
     * every permission that ever gets defined.
     * <p>
     * Without this, a freshly provisioned tenant admin (see
     * TenantProvisioningService) has zero rows in user_roles and
     * therefore zero effective permissions until someone remembers to
     * separately assign them a DB role - which is exactly the bug this
     * fixes (POST /api/v1/roles and similar returning 403 for a brand new
     * TENANT_ADMIN despite the role being correctly granted in Keycloak).
     */
    private boolean hasFullAccess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if ("ROLE_TENANT_ADMIN".equals(name) || "ROLE_PLATFORM_ADMIN".equals(name)) {
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
