package com.oak.dms.storage.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.CreateStorageLocationRequest;
import com.oak.dms.storage.dto.StorageLocationResponse;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.helper.StorageLocationMapper;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.StorageLocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StorageLocationServiceImpl implements StorageLocationService {

    private final StorageLocationRepository storageLocationRepository;
    private final StorageLocationMapper storageLocationMapper;
    private final AuditService auditService;

    @Override
    public StorageLocationResponse createLocation(CreateStorageLocationRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        StorageLocation location = StorageLocation.builder()
                .tenantId(tenantId)
                .name(request.name())
                .provider(request.provider())
                .endpoint(request.endpoint())
                .region(request.region())
                .bucket(request.bucket())
                .basePath(request.basePath())
                .pathStyleAccess(request.pathStyleAccess())
                .credentialRef(request.credentialRef())
                .status(StorageLocation.Status.active)
                .createdBy(TenantContext.getUserId())
                .build();

        StorageLocation saved = storageLocationRepository.save(location);
        log.info("Created storage location {} ({}) for tenant {}", saved.getStorageLocationId(),
                saved.getProvider(), tenantId);

        auditService.event(AuditActionCatalog.STORAGE_LOCATION_CREATED)
                .objectType(AuditObjectTypes.STORAGE_LOCATION)
                .objectId(saved.getStorageLocationId())
                .resourceName(saved.getName())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("provider", saved.getProvider(), "name", saved.getName()))
                .log();

        return storageLocationMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public StorageLocationResponse getLocation(UUID storageLocationId) {
        return storageLocationMapper.toResponse(findOwnedByCallerTenant(storageLocationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StorageLocationResponse> listLocations() {
        return storageLocationRepository.findAllByTenantIdAndDeletedFalse(TenantContext.getTenantId())
                .stream()
                .map(storageLocationMapper::toResponse)
                .toList();
    }

    private StorageLocation findOwnedByCallerTenant(UUID storageLocationId) {
        StorageLocation location = storageLocationRepository.findByStorageLocationIdAndDeletedFalse(storageLocationId)
                .orElseThrow(() -> new ResourceNotFoundException("StorageLocation", storageLocationId));
        if (!TenantContext.getTenantId().equals(location.getTenantId())) {
            throw new ResourceNotFoundException("StorageLocation", storageLocationId);
        }
        return location;
    }
}
