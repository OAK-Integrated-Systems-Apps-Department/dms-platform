package com.oak.dms.metadata;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.dto.CreateMetadataFieldOptionRequest;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldOptionRequest;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
                .displayOrder(request.displayOrder())
                .status(MetadataFieldOption.OptionStatus.active)
                .createdBy(TenantContext.getUserId())
                .build();

        MetadataFieldOption saved = optionRepository.save(option);
        log.info("Created metadata field option {} for field {}", saved.getOptionId(), fieldId);
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

        option.setOptionValue(request.optionValue());
        option.setOptionLabel(request.optionLabel());
        option.setParentOptionId(request.parentOptionId());
        option.setDisplayOrder(request.displayOrder());
        option.setStatus(MetadataFieldOption.OptionStatus.valueOf(request.status().name()));
        option.setUpdatedBy(TenantContext.getUserId());
        option.setUpdatedAt(OffsetDateTime.now());

        MetadataFieldOption saved = optionRepository.save(option);
        log.info("Updated metadata field option {}", saved.getOptionId());
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
        log.info("Deleted metadata field option {}", optionId);
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
