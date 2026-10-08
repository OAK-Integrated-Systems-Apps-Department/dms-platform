package com.oak.dms.tenant;

import com.oak.dms.tenant.dto.CreateTenantRequest;
import com.oak.dms.tenant.dto.UpdateTenantRequest;
import com.oak.dms.tenant.dto.TenantResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Tenant lifecycle: provisioning, lookup, update, and deactivation.
 * Tenant creation is a platform-administration operation - callers reach this
 * through {@link TenantController}, which restricts it to PLATFORM_ADMIN.
 */
public interface TenantService {
    TenantResponse createTenant(CreateTenantRequest request);

    TenantResponse getTenant(UUID tenantId);

    Page<TenantResponse> listTenants(Pageable pageable);

    /** @param includeDeactivated deactivated (removed) organisations too */
    default Page<TenantResponse> listTenants(Pageable pageable, boolean includeDeactivated) {
        return listTenants(pageable);
    }

    /** Brings a deactivated organisation back - refused once its encryption keys were destroyed. */
    TenantResponse reactivateTenant(UUID tenantId);

    TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request);

    void deactivateTenant(UUID tenantId);
}
