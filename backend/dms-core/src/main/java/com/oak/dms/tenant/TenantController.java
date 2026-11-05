package com.oak.dms.tenant;

import com.oak.dms.tenant.dto.CreateTenantRequest;
import com.oak.dms.tenant.dto.ProvisionTenantRequest;
import com.oak.dms.tenant.dto.TenantProvisioningResponse;
import com.oak.dms.tenant.dto.TenantAdminResponse;
import com.oak.dms.tenant.dto.UpdateTenantAdminRequest;
import com.oak.dms.tenant.dto.UpdateTenantRequest;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.tenant.dto.TenantResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import com.oak.dms.security.auth.PasswordConfirmation;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
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
    private final TenantAdminService tenantAdminService;
    private final PasswordConfirmation passwordConfirmation;
    private final com.oak.dms.editing.PlatformEditingAccess platformEditingAccess;

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
            @Valid @RequestBody ProvisionTenantRequest request, HttpServletRequest http) {
        passwordConfirmation.require(http);
        TenantProvisioningResponse response = tenantProvisioningService.provisionTenant(request);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + response.tenant().tenantId()))
                .body(response);
    }

    /** Which organisations the platform administrator has allowed online editing - one call for the whole list. */
    public record OnlineEditingAllowed(java.util.List<UUID> tenantIds) {
    }

    public record OnlineEditingRequest(boolean allowed) {
    }

    public record OnlineEditingResult(UUID tenantId, boolean allowed) {
    }

    @GetMapping("/online-editing")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "The organisations the platform administrator has allowed to use online editing")
    public OnlineEditingAllowed onlineEditingAllowed() {
        return new OnlineEditingAllowed(platformEditingAccess.allowedTenants());
    }

    @PutMapping("/{tenantId}/online-editing")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Allow or stop online editing for an organisation",
            description = "While allowed, the organisation's own administrator can turn it on or off for its people; "
                    + "while not, nobody there gets the editor and its settings page is not shown. Needs the platform "
                    + "administrator's own password (X-Confirm-Password). Audited.")
    public OnlineEditingResult setOnlineEditing(@PathVariable UUID tenantId,
                                                @RequestBody OnlineEditingRequest request, HttpServletRequest http) {
        passwordConfirmation.require(http);
        return new OnlineEditingResult(tenantId, platformEditingAccess.setAllowed(tenantId, request.allowed()));
    }

    @GetMapping("/{tenantId}/admins")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "List a tenant's administrators (the users holding TENANT_ADMIN)")
    public List<TenantAdminResponse> listTenantAdmins(@PathVariable UUID tenantId) {
        return tenantAdminService.listAdmins(tenantId);
    }

    @PutMapping("/{tenantId}/admins/{userId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Correct a tenant administrator's name or email")
    public TenantAdminResponse updateTenantAdmin(@PathVariable UUID tenantId, @PathVariable UUID userId,
                                                 @Valid @RequestBody UpdateTenantAdminRequest request,
                                                 HttpServletRequest http) {
        passwordConfirmation.require(http);
        return tenantAdminService.updateAdmin(tenantId, userId, request);
    }

    @PostMapping("/{tenantId}/admins/{userId}/resend-invite")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Send the invitation email again to an administrator who has not set up their account")
    public void resendTenantAdminInvite(@PathVariable UUID tenantId, @PathVariable UUID userId) {
        tenantAdminService.resendInvite(tenantId, userId);
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
    public PageResponse<TenantResponse> listTenants(
            Pageable pageable,
            @org.springframework.web.bind.annotation.RequestParam(value = "includeDeactivated",
                    defaultValue = "false") boolean includeDeactivated) {
        return PageResponse.from(tenantService.listTenants(pageable, includeDeactivated));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{tenantId}/reactivate")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Bring a deactivated tenant back - not once its encryption keys were destroyed")
    public TenantResponse reactivateTenant(@PathVariable UUID tenantId) {
        return tenantService.reactivateTenant(tenantId);
    }

    @PutMapping("/{tenantId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or @tenantSecurity.isSameTenant(#tenantId)")
    @Operation(summary = "Update a tenant")
    public TenantResponse updateTenant(@PathVariable UUID tenantId, @Valid @RequestBody UpdateTenantRequest request,
                                       HttpServletRequest http) {
        // A platform administrator confirms with their own password; an organisation's own administrator
        // editing their own organisation is not asked.
        passwordConfirmation.requireIfPlatformAdmin(http);
        return tenantService.updateTenant(tenantId, request);
    }

    @DeleteMapping("/{tenantId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate (soft-delete) a tenant")
    public void deactivateTenant(@PathVariable UUID tenantId, HttpServletRequest http) {
        passwordConfirmation.require(http);
        tenantService.deactivateTenant(tenantId);
    }
}
