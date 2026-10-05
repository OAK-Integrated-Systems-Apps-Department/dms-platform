package com.oak.dms.security.auth;

import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Binds a background job (bulk import, export) to the user who started it, so
 * its access checks run as that user <em>now</em>: their current roles and
 * folder/document access entries, and their realm roles (TENANT_ADMIN) looked
 * up live from Keycloak - see {@link TenantContext#markBackgroundJob()}.
 */
@Component
@RequiredArgsConstructor
public class JobIdentity {

    private final UserRepository users;
    private final OrganisationStatusGuard organisationStatus;

    /**
     * @return false - and nothing bound - when the user no longer exists or isn't active in the
     *         tenant: the job must not go on acting for them
     */
    public boolean bind(UUID tenantId, UUID userId) {
        Optional<User> user = users.findById(userId)
                .filter(u -> tenantId.equals(u.getTenantId()))
                .filter(u -> !u.isDeleted() && u.getStatus() == User.UserStatus.active);
        if (user.isEmpty() || organisationStatus.inactiveReason(tenantId).isPresent()) {
            return false; // gone, deactivated - or their organisation is suspended or closed
        }
        TenantContext.set(tenantId, userId, user.get().getDisplayName(), user.get().getKeycloakId());
        TenantContext.markBackgroundJob();
        return true;
    }
}
