package com.oak.dms.security.auth;

import com.oak.dms.security.rbac.Permission;
import com.oak.dms.security.rbac.PermissionRepository;
import com.oak.dms.security.rbac.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Data-driven, fine-grained permission checks on top of the
 * role_permissions / user_roles tables, complementing the coarse
 * ROLE_* checks derived straight from the Keycloak token.
 *
 * Usage in controllers - reference permission codes via {@link com.oak.dms.security.rbac.PermissionCodes}
 * rather than a raw string literal (Spring EL has no notion of a Java
 * import, so this needs a fully-qualified {@code T(...)} type reference):
 *   {@code @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).OTUA_USERS_UPDATE)")}
 */
@Component("authz")
@RequiredArgsConstructor
public class AuthorizationService {

    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;

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

    /**
     * Whether the caller holds the TENANT_ADMIN or PLATFORM_ADMIN Keycloak
     * realm role - same check {@link #hasFullAccess()} already does for
     * every fine-grained permission, exposed here as its own method for
     * call sites that specifically need to restrict something to the
     * tenant admin rather than to any permission code. Currently only
     * {@code UserController#purgeUser} (the User List's permanent-delete
     * action) uses this - unlike ordinary activate/deactivate, which stays
     * available to anyone holding the granular
     * {@code otua.manage-users.activate-deactivate} permission, permanently
     * removing a user is deliberately kept off the permission-code system
     * entirely (see that method's own doc comment for why) and restricted
     * to the fixed tenant-admin realm role instead.
     */
    public boolean isTenantAdmin() {
        return hasFullAccess();
    }

    /**
     * The fixed platform roles (PLATFORM_ADMIN, TENANT_ADMIN, MEMBER) the
     * currently authenticated caller holds, read straight off the
     * authorities {@code JwtAuthConverter} already derived from their own
     * validated JWT for this very request.
     * <p>
     * Deliberately distinct from
     * {@link com.oak.dms.security.keycloak.KeycloakUserService#getPlatformRealmRoles(java.util.UUID)},
     * which looks up an <em>arbitrary other</em> user via the Keycloak
     * admin API for tenant-admin-facing screens (User List / View User)
     * and, per that method's own doc comment, deliberately excludes
     * PLATFORM_ADMIN there since a cross-tenant platform administrator
     * isn't meaningful to surface on one tenant's user management view.
     * That exclusion doesn't apply here: this is always the caller asking
     * about themselves, so there's no cross-tenant disclosure risk in
     * telling them they hold PLATFORM_ADMIN - and the frontend needs
     * exactly that to decide whether to show platform-admin-only screens
     * like Permission Catalog management (see {@code UserController#getMyPlatformRoles}).
     */
    public List<String> myPlatformRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(name -> name.startsWith("ROLE_"))
                .map(name -> name.substring("ROLE_".length()))
                .filter(PLATFORM_ROLE_NAMES::contains)
                .collect(Collectors.toList());
    }

    private static final Set<String> PLATFORM_ROLE_NAMES = Set.of("PLATFORM_ADMIN", "TENANT_ADMIN", "MEMBER");

    /**
     * The caller's own resolved permission set - what {@link #hasPermission}
     * actually checks against, exposed for {@code GET /api/v1/users/me} so
     * the frontend can gate its own UI (which buttons/links to show) the
     * same way the backend gates the request itself, rather than the
     * frontend guessing from the coarse Keycloak role name alone (a
     * tenant-defined role name like "KP" carries no permission
     * information the frontend could decode).
     * <p>
     * TENANT_ADMIN/PLATFORM_ADMIN need special handling here, the same way
     * {@link #hasFullAccess} already special-cases them for every
     * individual check: neither necessarily has any {@code role_permissions}
     * rows of their own (see {@link #hasFullAccess}'s own doc comment - a
     * freshly provisioned tenant admin has zero), so returning
     * {@link #effectivePermissions()} unmodified for one of them would
     * under-report what they can actually do and the frontend would hide
     * controls a full-access caller is completely entitled to use. Instead,
     * for those two, this returns every permission code visible to the
     * caller's own tenant (shared catalog + that tenant's own custom
     * permissions - see {@link PermissionRepository#findAllVisibleToTenant}) -
     * i.e. "the full catalog", which is what full access actually means.
     * Everyone else gets their real, role-assignment-derived set.
     */
    public List<String> myEffectivePermissionCodes() {
        if (hasFullAccess()) {
            return permissionRepository
                    .findAllVisibleToTenant(TenantContext.getTenantId(), Pageable.unpaged())
                    .map(Permission::getPermissionCode)
                    .toList();
        }
        return List.copyOf(effectivePermissions());
    }

    private Set<String> effectivePermissions() {
        List<String> codes = rolePermissionRepository.findEffectivePermissionCodes(
                TenantContext.getUserId(), TenantContext.getTenantId());
        return Set.copyOf(codes);
    }
}