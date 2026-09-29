package com.oak.dms.tenant;

import com.oak.dms.tenant.dto.CreateTenantRequest;
import com.oak.dms.tenant.dto.ProvisionTenantRequest;
import com.oak.dms.tenant.dto.TenantProvisioningResponse;
import com.oak.dms.tenant.dto.UpdateTenantRequest;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.tenant.dto.TenantResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * Tenant lifecycle is a platform-administration concern: creating and
 * listing tenants is restricted to PLATFORM_ADMIN. Reading/updating a
 * single tenant is also allowed for that tenant's own admins, via
 * tenantSecurity.isSameTenant.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Platform-level tenant management")
public class TenantController {

    private final TenantService tenantService;
    private final TenantProvisioningService tenantProvisioningService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Provision a new tenant")
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        TenantResponse response = tenantService.createTenant(request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + response.tenantId())).body(response);
    }

    @PostMapping("/provision")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Onboard a new client: creates the tenant and its first TENANT_ADMIN user together, "
            + "atomically. Prefer this over POST /api/v1/tenants when the tenant needs a working admin - "
            + "there's no other endpoint that can create a user in a tenant other than the caller's own or "
            + "grant TENANT_ADMIN.")
    public ResponseEntity<TenantProvisioningResponse> provisionTenant(
            @Valid @RequestBody ProvisionTenantRequest request) {
        TenantProvisioningResponse response = tenantProvisioningService.provisionTenant(request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + response.tenant().tenantId()))
                .body(response);
    }

    @GetMapping("/{tenantId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or @tenantSecurity.isSameTenant(#tenantId)")
    @Operation(summary = "Get a tenant by id")
    public TenantResponse getTenant(@PathVariable UUID tenantId) {
        return tenantService.getTenant(tenantId);
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "List all tenants")
    public PageResponse<TenantResponse> listTenants(Pageable pageable) {
        return PageResponse.from(tenantService.listTenants(pageable));
    }

    @PutMapping("/{tenantId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or @tenantSecurity.isSameTenant(#tenantId)")
    @Operation(summary = "Update a tenant")
    public TenantResponse updateTenant(@PathVariable UUID tenantId, @Valid @RequestBody UpdateTenantRequest request) {
        return tenantService.updateTenant(tenantId, request);
    }

    @DeleteMapping("/{tenantId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate (soft-delete) a tenant")
    public void deactivateTenant(@PathVariable UUID tenantId) {
        tenantService.deactivateTenant(tenantId);
    }
}
