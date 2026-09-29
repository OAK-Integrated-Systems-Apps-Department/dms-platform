package com.oak.dms.metadata.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.dto.CreateMetadataFieldOptionRequest;
import com.oak.dms.metadata.mapper.MetadataFieldOptionMapper;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldOptionRequest;
import com.oak.dms.metadata.entity.MetadataFieldOption;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.service.MetadataFieldOptionService;
import com.oak.dms.metadata.helper.DisplayOrderNormalizer;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * See {@link MetadataFieldOptionService}. {@code fieldId} is trusted from
 * the path but every read/write is still re-scoped to the caller's tenant
 * (via {@link #requireField} and the repository's own tenant-filtered
 * queries), so a field id from another tenant 404s rather than leaking
 * options across the boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MetadataFieldOptionServiceImpl implements MetadataFieldOptionService {

    private final MetadataFieldOptionRepository optionRepository;
    private final MetadataFieldRepository metadataFieldRepository;
    private final MetadataFieldOptionMapper optionMapper;
    private final SchemaCacheService schemaCacheService;
    private final AuditService auditService;

    @Override
    public MetadataFieldOptionResponse createOption(UUID fieldId, CreateMetadataFieldOptionRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        requireField(fieldId, tenantId);

        MetadataFieldOption option = MetadataFieldOption.builder()
                .tenantId(tenantId)
                .fieldId(fieldId)
                .parentOptionId(request.parentOptionId())
                .optionValue(request.optionValue())
                .optionLabel(request.optionLabel())
                .status(MetadataFieldOption.OptionStatus.active)
                .createdBy(TenantContext.getUserId())
                .build();

        List<MetadataFieldOption> siblings = optionRepository.findAllByFieldOrdered(fieldId, tenantId);
        List<MetadataFieldOption> touchedSiblings = DisplayOrderNormalizer.renumber(
                siblings, option, request.displayOrder(),
                MetadataFieldOption::getDisplayOrder, MetadataFieldOption::setDisplayOrder);

        MetadataFieldOption saved = optionRepository.save(option);
        if (!touchedSiblings.isEmpty()) {
            optionRepository.saveAll(touchedSiblings);
        }
        schemaCacheService.evictTenant(tenantId);
        log.info("Created metadata field option {} for field {}", saved.getOptionId(), fieldId);

        auditService.event(AuditActionCatalog.METADATA_FIELD_OPTION_CREATED)
                .objectType(AuditObjectTypes.METADATA_FIELD_OPTION)
                .objectId(saved.getOptionId())
                .resourceName(saved.getOptionValue())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "fieldId", fieldId,
                        "optionValue", saved.getOptionValue(),
                        "optionLabel", saved.getOptionLabel()))
                .log();

        return optionMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetadataFieldOptionResponse> listOptions(UUID fieldId) {
        UUID tenantId = TenantContext.getTenantId();
        requireField(fieldId, tenantId);
        return optionRepository.findAllByFieldOrdered(fieldId, tenantId).stream()
                .map(optionMapper::toResponse)
                .toList();
    }

    @Override
    public MetadataFieldOptionResponse updateOption(UUID fieldId, UUID optionId, UpdateMetadataFieldOptionRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        requireField(fieldId, tenantId);
        MetadataFieldOption option = findOwnedByFieldAndTenant(optionId, fieldId, tenantId);

        Map<String, Object> previousValue = Map.of(
                "optionValue", option.getOptionValue(),
                "optionLabel", option.getOptionLabel(),
                "status", option.getStatus());

        option.setOptionValue(request.optionValue());
        option.setOptionLabel(request.optionLabel());
        option.setParentOptionId(request.parentOptionId());
        option.setStatus(MetadataFieldOption.OptionStatus.valueOf(request.status().name()));
        option.setUpdatedBy(TenantContext.getUserId());
        option.setUpdatedAt(OffsetDateTime.now());

        List<MetadataFieldOption> siblings = optionRepository.findAllByFieldOrdered(fieldId, tenantId).stream()
                .filter(o -> !o.getOptionId().equals(optionId))
                .toList();
        List<MetadataFieldOption> touchedSiblings = DisplayOrderNormalizer.renumber(
                siblings, option, request.displayOrder(),
                MetadataFieldOption::getDisplayOrder, MetadataFieldOption::setDisplayOrder);

        MetadataFieldOption saved = optionRepository.save(option);
        if (!touchedSiblings.isEmpty()) {
            optionRepository.saveAll(touchedSiblings);
        }
        schemaCacheService.evictTenant(tenantId);
        log.info("Updated metadata field option {}", saved.getOptionId());

        auditService.event(AuditActionCatalog.METADATA_FIELD_OPTION_UPDATED)
                .objectType(AuditObjectTypes.METADATA_FIELD_OPTION)
                .objectId(saved.getOptionId())
                .resourceName(saved.getOptionValue())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "optionValue", saved.getOptionValue(),
                        "optionLabel", saved.getOptionLabel(),
                        "status", saved.getStatus()))
                .log();

        return optionMapper.toResponse(saved);
    }

    @Override
    public void deleteOption(UUID fieldId, UUID optionId) {
        UUID tenantId = TenantContext.getTenantId();
        requireField(fieldId, tenantId);
        MetadataFieldOption option = findOwnedByFieldAndTenant(optionId, fieldId, tenantId);

        option.setDeleted(true);
        option.setDeletedAt(OffsetDateTime.now());
        option.setUpdatedBy(TenantContext.getUserId());
        option.setUpdatedAt(OffsetDateTime.now());
        optionRepository.save(option);
        schemaCacheService.evictTenant(tenantId);
        log.info("Deleted metadata field option {}", optionId);

        auditService.event(AuditActionCatalog.METADATA_FIELD_OPTION_DELETED)
                .objectType(AuditObjectTypes.METADATA_FIELD_OPTION)
                .objectId(optionId)
                .resourceName(option.getOptionValue())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    private void requireField(UUID fieldId, UUID tenantId) {
        metadataFieldRepository.findByFieldIdAndTenantIdAndDeletedFalse(fieldId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MetadataField", fieldId));
    }

    private MetadataFieldOption findOwnedByFieldAndTenant(UUID optionId, UUID fieldId, UUID tenantId) {
        MetadataFieldOption option = optionRepository.findByOptionIdAndTenantIdAndDeletedFalse(optionId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MetadataFieldOption", optionId));
        if (!option.getFieldId().equals(fieldId)) {
            throw new ResourceNotFoundException("MetadataFieldOption", optionId);
        }
        return option;
    }
}
