package com.oak.dms.security.auth;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Small bean exposed to method-security SpEL expressions
 * (@PreAuthorize("...@tenantSecurity.isSameTenant(#tenantId)")) so
 * controllers can allow a tenant's own admins to act on their own tenant
 * without granting them the cross-tenant PLATFORM_ADMIN role.
 */
@Component("tenantSecurity")
public class TenantSecurity {

    /**
     * True when the given tenantId matches the authenticated caller's own
     * tenant. Intended for use alongside a platform-admin role check, e.g.
     * {@code @PreAuthorize("hasRole('PLATFORM_ADMIN') or @tenantSecurity.isSameTenant(#tenantId)")}
     */
    public boolean isSameTenant(UUID tenantId) {
        return tenantId != null && tenantId.equals(TenantContext.getTenantId());
    }
}
