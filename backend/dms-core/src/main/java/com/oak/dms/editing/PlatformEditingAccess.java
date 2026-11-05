package com.oak.dms.editing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether the platform administrator has allowed an organisation to use online editing at all. It sits above the
 * organisation's own switch ({@link EditingSettingsService}): the organisation's administrator can only turn editing
 * on or off for its people once it has been allowed, and while it is not allowed nobody there gets the editor
 * whatever their own setting says. No row = not allowed.
 */
@Service
@RequiredArgsConstructor
public class PlatformEditingAccess {

    private final TenantPlatformSettingsRepository repository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public boolean allowed(UUID tenantId) {
        return repository.findById(tenantId).map(TenantPlatformSettings::isOnlineEditingAllowed).orElse(false);
    }

    /** Every organisation currently allowed. */
    @Transactional(readOnly = true)
    public List<UUID> allowedTenants() {
        return repository.tenantsAllowedOnlineEditing();
    }

    /**
     * Allows or stops online editing for one organisation. Audited, as a platform-level event on that organisation.
     *
     * @throws ResourceNotFoundException when there is no such organisation
     */
    @Transactional
    public boolean setAllowed(UUID tenantId, boolean allowed) {
        Tenant tenant = tenantRepository.findByTenantIdAndDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        boolean before = allowed(tenantId);
        TenantPlatformSettings row = repository.findById(tenantId)
                .orElseGet(() -> TenantPlatformSettings.builder().tenantId(tenantId).build());
        row.setOnlineEditingAllowed(allowed);
        // A platform administrator has no row in users, so the Keycloak id is what says who changed it.
        row.setUpdatedBy(TenantContext.getKeycloakIdOrNull());
        row.setUpdatedAt(OffsetDateTime.now());
        repository.save(row);

        auditService.event(AuditActionCatalog.TENANT_ONLINE_EDITING_CHANGED)
                .platformLevel()
                .objectType(AuditObjectTypes.TENANT)
                .objectId(tenantId)
                .resourceName(tenant.getTenantName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("onlineEditingAllowed", before))
                .newValue(Map.of("onlineEditingAllowed", allowed))
                .log();
        return allowed;
    }
}
