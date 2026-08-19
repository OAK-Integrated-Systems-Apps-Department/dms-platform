package com.oak.dms.tenant;

import com.oak.dms.tenant.dto.CreateTenantRequest;
import com.oak.dms.tenant.dto.UpdateTenantRequest;
import com.oak.dms.tenant.dto.TenantResponse;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.tenant.TenantMapper;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

        Tenant saved = tenantRepository.save(tenant);
        log.info("Created tenant {} ({})", saved.getTenantId(), saved.getDomain());
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
    public Page<TenantResponse> listTenants(Pageable pageable) {
        return tenantRepository.findAllActive(pageable).map(tenantMapper::toResponse);
    }

    @Override
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = tenantRepository.findByTenantIdAndDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        tenant.setTenantName(request.tenantName());
        tenant.setSubscriptionTier(request.subscriptionTier());
        tenant.setStatus(Tenant.TenantStatus.valueOf(request.status().name()));
        tenant.setUpdatedAt(OffsetDateTime.now());

        Tenant saved = tenantRepository.save(tenant);
        log.info("Updated tenant {}", saved.getTenantId());
        return tenantMapper.toResponse(saved);
    }

    @Override
    public void deactivateTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findByTenantIdAndDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        tenant.setDeleted(true);
        tenant.setDeletedAt(OffsetDateTime.now());
        tenant.setStatus(Tenant.TenantStatus.archived);
        tenantRepository.save(tenant);
        log.info("Deactivated tenant {}", tenantId);
    }
}
