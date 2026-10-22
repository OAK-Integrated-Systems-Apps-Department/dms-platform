package com.oak.dms.tenant;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.metadata.seed.TenantStarterCatalogService;
import com.oak.dms.relationship.seed.RelationshipTypeSeeder;
import com.oak.dms.tenant.dto.CreateTenantRequest;
import com.oak.dms.tenant.dto.UpdateTenantRequest;
import com.oak.dms.tenant.dto.TenantResponse;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * See {@link TenantService}. Deactivation is a soft-delete (is_deleted +
 * deleted_at + status=archived) rather than a row delete, so historical
 * references from other tables never dangle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;
    private final TenantMapper tenantMapper;
    private final AuditService auditService;
    private final TenantStarterCatalogService starterCatalogService;
    private final RelationshipTypeSeeder relationshipTypeSeeder;

    @Override
    public TenantResponse createTenant(CreateTenantRequest request) {
        if (tenantRepository.existsByDomainAndDeletedFalse(request.domain())) {
            throw new DuplicateResourceException("A tenant already exists for domain " + request.domain());
        }

        Tenant tenant = Tenant.builder()
                .tenantName(request.tenantName())
                .domain(request.domain())
                .subscriptionTier(request.subscriptionTier())
                .status(Tenant.TenantStatus.active)
                .build();

        // Flushed so the tenants row exists before the catalogue rows that
        // reference it; neither has a JPA association to the other.
        Tenant saved = tenantRepository.saveAndFlush(tenant);
        starterCatalogService.install(saved.getTenantId());
        relationshipTypeSeeder.install(saved.getTenantId());
        log.info("Created tenant {} ({})", saved.getTenantId(), saved.getDomain());

        // platformLevel() - this event isn't about the caller's own tenant
        // (PLATFORM_ADMIN has none in the tenant-scoped sense), and
        // defaulting tenant_id to whatever TenantContext happened to
        // resolve would misattribute it. See AuditEventBuilder#platformLevel.
        auditService.event(AuditActionCatalog.TENANT_CREATED)
                .platformLevel()
                .objectType(AuditObjectTypes.TENANT)
                .objectId(saved.getTenantId())
                .resourceName(saved.getTenantName())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "tenantName", saved.getTenantName(),
                        "domain", saved.getDomain(),
                        "subscriptionTier", saved.getSubscriptionTier(),
                        "status", saved.getStatus()))
                .log();

        return tenantMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TenantResponse getTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findByTenantIdAndDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        return tenantMapper.toResponse(tenant);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TenantResponse> listTenants(Pageable pageable, boolean includeDeactivated) {
        return includeDeactivated
                ? tenantRepository.findAllIncludingDeactivated(pageable).map(tenantMapper::toResponse)
                : listTenants(pageable);
    }

    @Override
    @Transactional
    public TenantResponse reactivateTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        if (tenant.getKeysShreddedAt() != null) {
            throw new com.oak.dms.folder.FolderException(org.springframework.http.HttpStatus.CONFLICT,
                    "TENANT_KEYS_SHREDDED", "This organisation's encryption keys were destroyed, so its documents "
                    + "can never be read again - it can't be reactivated.");
        }
        Map<String, Object> previousValue = Map.of("status", tenant.getStatus(), "deactivated", tenant.isDeleted());
        tenant.setDeleted(false);
        tenant.setDeletedAt(null);
        tenant.setStatus(Tenant.TenantStatus.active);
        tenant.setUpdatedAt(OffsetDateTime.now());
        Tenant saved = tenantRepository.save(tenant);
        log.info("Reactivated tenant {}", tenantId);
        auditService.event(AuditActionCatalog.TENANT_UPDATED)
                .platformLevel()
                .objectType(AuditObjectTypes.TENANT)
                .objectId(tenantId)
                .resourceName(saved.getTenantName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of("status", saved.getStatus(), "deactivated", false))
                .log();
        return tenantMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TenantResponse> listTenants(Pageable pageable) {
        return tenantRepository.findAllActive(pageable).map(tenantMapper::toResponse);
    }

    @Override
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = tenantRepository.findByTenantIdAndDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        Map<String, Object> previousValue = Map.of(
                "tenantName", tenant.getTenantName(),
                "subscriptionTier", tenant.getSubscriptionTier(),
                "status", tenant.getStatus());

        tenant.setTenantName(request.tenantName());
        tenant.setSubscriptionTier(request.subscriptionTier());
        tenant.setStatus(Tenant.TenantStatus.valueOf(request.status().name()));
        tenant.setUpdatedAt(OffsetDateTime.now());

        Tenant saved = tenantRepository.save(tenant);
        log.info("Updated tenant {}", saved.getTenantId());

        auditService.event(AuditActionCatalog.TENANT_UPDATED)
                .platformLevel()
                .objectType(AuditObjectTypes.TENANT)
                .objectId(saved.getTenantId())
                .resourceName(saved.getTenantName())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "tenantName", saved.getTenantName(),
                        "subscriptionTier", saved.getSubscriptionTier(),
                        "status", saved.getStatus()))
                .log();

        return tenantMapper.toResponse(saved);
    }

    @Override
    public void deactivateTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findByTenantIdAndDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        String tenantName = tenant.getTenantName();

        // From here its users can't sign in, and sessions they already have stop working on their next
        // request (OrganisationStatusGuard).
        tenant.setDeleted(true);
        tenant.setDeletedAt(OffsetDateTime.now());
        tenant.setStatus(Tenant.TenantStatus.archived);
        tenantRepository.save(tenant);
        log.info("Deactivated tenant {}", tenantId);

        auditService.event(AuditActionCatalog.TENANT_DEACTIVATED)
                .platformLevel()
                .objectType(AuditObjectTypes.TENANT)
                .objectId(tenantId)
                .resourceName(tenantName)
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }
}
