package com.oak.dms.security.auth;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Does user X - not the caller - hold permission P?", answered the way
 * {@link AuthorizationService#hasPermission} answers it for the caller:
 * a DB role grant, or the TENANT_ADMIN realm role, which holds every
 * permission implicitly. Used to vet a delegate or an escalation target and
 * to snapshot whether a mentioned user could open the document.
 * <p>
 * The tenant-admin half asks Keycloak. When Keycloak can't be reached the
 * answer <b>fails closed</b> (not an admin): these checks gate who receives
 * work or sees a link, and over-granting is the worse mistake.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPermissions {

    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private final RolePermissionRepository rolePermissionRepository;
    private final UserRepository userRepository;
    private final KeycloakUserService keycloakUserService;

    public boolean holds(UUID tenantId, UUID userId, String permissionCode) {
        return holdsAny(tenantId, userId, permissionCode);
    }

    public boolean holdsAny(UUID tenantId, UUID userId, String... permissionCodes) {
        List<String> granted = rolePermissionRepository.findEffectivePermissionCodes(userId, tenantId);
        for (String code : permissionCodes) {
            if (granted.contains(code)) {
                return true;
            }
        }
        return isTenantAdmin(tenantId, userId);
    }

    public boolean isTenantAdmin(UUID tenantId, UUID userId) {
        User user = userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId).orElse(null);
        if (user == null || user.getKeycloakId() == null) {
            return false;
        }
        try {
            return keycloakUserService.getPlatformRealmRoles(user.getKeycloakId()).contains(TENANT_ADMIN_ROLE);
        } catch (RuntimeException e) {
            log.warn("Could not read realm roles of user {} from Keycloak - treating them as not a tenant admin: {}",
                    userId, e.getMessage());
            return false;
        }
    }

    /**
     * Of {@code candidates}, those holding any of the codes - one query per
     * code plus one Keycloak call for the tenant admins, instead of a lookup
     * per user. For pickers; fails closed like {@link #isTenantAdmin}.
     */
    public Set<UUID> filterHoldingAny(UUID tenantId, Collection<UUID> candidates, String... permissionCodes) {
        Set<UUID> holders = new HashSet<>();
        for (String code : permissionCodes) {
            holders.addAll(rolePermissionRepository.findUserIdsWithPermission(tenantId, code));
        }
        holders.addAll(tenantAdminIds(tenantId));
        Set<UUID> result = new HashSet<>(candidates);
        result.retainAll(holders);
        return result;
    }

    /**
     * After a failed Keycloak lookup, how long to answer "nobody" at once instead of waiting out another
     * timeout. The failure path already answers "nobody" (fails closed), so this changes only how fast, never
     * what: it stops a slow or down Keycloak from stalling every request that asks - approvals now write their
     * notifications, access check included, inside the user's own transaction.
     */
    private static final long KEYCLOAK_FAILURE_BACKOFF_MILLIS = 30_000;

    private volatile long keycloakFailedAtMillis;

    /** Users of the tenant holding the TENANT_ADMIN realm role; empty when Keycloak can't be reached. */
    public Set<UUID> tenantAdminIds(UUID tenantId) {
        Set<UUID> ids = new HashSet<>();
        long failedAt = keycloakFailedAtMillis;
        if (failedAt != 0 && System.currentTimeMillis() - failedAt < KEYCLOAK_FAILURE_BACKOFF_MILLIS) {
            return ids;   // Keycloak just failed: same answer as a failure, without the wait
        }
        try {
            for (UUID keycloakId : keycloakUserService.listRealmRoleMemberIds(TENANT_ADMIN_ROLE)) {
                userRepository.findByKeycloakIdAndDeletedFalse(keycloakId)
                        .filter(u -> tenantId.equals(u.getTenantId()))
                        .ifPresent(u -> ids.add(u.getUserId()));
            }
            keycloakFailedAtMillis = 0;
        } catch (RuntimeException e) {
            keycloakFailedAtMillis = System.currentTimeMillis();
            log.warn("Could not list tenant admins from Keycloak (answering \"none\" for the next {} s): {}",
                    KEYCLOAK_FAILURE_BACKOFF_MILLIS / 1000, e.getMessage());
        }
        return ids;
    }
}
