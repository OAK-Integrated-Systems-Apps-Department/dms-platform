package com.oak.dms.security.auth;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Small bean exposed to method-security SpEL expressions
 * (@PreAuthorize("...@tenantSecurity.isSameTenant(#tenantId)")) so
 * controllers can scope an endpoint to the caller's own tenant without
 * granting the cross-tenant PLATFORM_ADMIN role. It checks tenant membership
 * only, not authority: an endpoint that must be limited to a tenant's
 * administrators has to combine it with a role check, e.g.
 * {@code @tenantSecurity.isSameTenant(#tenantId) and @authz.isTenantAdmin()}.
 */
@Component("tenantSecurity")
public class TenantSecurity {

    /**
     * True when the given tenantId matches the authenticated caller's own
     * tenant - any member of that tenant, admin or not. Use it alone only
     * where every member may act (e.g. reading the tenant's own record);
     * for administrator-only actions also require {@code @authz.isTenantAdmin()}.
     */
    public boolean isSameTenant(UUID tenantId) {
        return tenantId != null && tenantId.equals(TenantContext.getTenantId());
    }
}
