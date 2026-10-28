package com.oak.dms.security.auth;

import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Whether an organisation (tenant) may be used: only an active one. A
 * suspended, archived or deactivated organisation's users can't sign in, and
 * every request they make with a session they already had is refused - see
 * {@link TenantContextFilter}, {@link AuthService#login} and {@link JobIdentity}.
 * Platform admins are let through, so they can reactivate it.
 */
@Component
@RequiredArgsConstructor
public class OrganisationStatusGuard {

    public static final String CODE = "ORGANISATION_INACTIVE";

    private final TenantRepository tenantRepository;

    /** Why the organisation can't be used, in words - empty when it is active. */
    public Optional<String> inactiveReason(UUID tenantId) {
        Optional<Tenant> tenant = tenantRepository.findById(tenantId);
        if (tenant.isEmpty() || tenant.get().isDeleted()
                || tenant.get().getStatus() == Tenant.TenantStatus.archived) {
            return Optional.of("Your organisation's account has been closed. Contact your administrator.");
        }
        if (tenant.get().getStatus() == Tenant.TenantStatus.suspended) {
            return Optional.of("Your organisation's account is suspended. Contact your administrator.");
        }
        return Optional.empty();
    }
}
