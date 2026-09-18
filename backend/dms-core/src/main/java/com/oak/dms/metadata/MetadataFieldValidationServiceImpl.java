package com.oak.dms.metadata;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.dto.CreateMetadataFieldValidationRequest;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldValidationRequest;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MetadataFieldValidationServiceImpl implements MetadataFieldValidationService {

    private final MetadataFieldValidationRepository validationRepository;
    private final MetadataFieldRepository metadataFieldRepository;
    private final MetadataFieldValidationMapper validationMapper;
    private final SchemaCacheService schemaCacheService;

    @Override
    public MetadataFieldValidationResponse createValidation(UUID fieldId, CreateMetadataFieldValidationRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        requireField(fieldId, tenantId);

        MetadataFieldValidation validation = MetadataFieldValidation.builder()
                .tenantId(tenantId)
                .fieldId(fieldId)
                .ruleType(request.ruleType())
                .ruleValue(request.ruleValue())
                .errorMessage(request.errorMessage())
                .severity(request.severity() == null ? MetadataFieldValidation.Severity.error : request.severity())
                .createdBy(TenantContext.getUserId())
                .build();

        MetadataFieldValidation saved = validationRepository.save(validation);
        schemaCacheService.evictTenant(tenantId);
        log.info("Created metadata field validation {} for field {}", saved.getValidationId(), fieldId);
        return validationMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetadataFieldValidationResponse> listValidations(UUID fieldId) {
        UUID tenantId = TenantContext.getTenantId();
        requireField(fieldId, tenantId);
        return validationRepository.findAllByField(fieldId, tenantId).stream()
                .map(validationMapper::toResponse)
                .toList();
    }

    @Override
    public MetadataFieldValidationResponse updateValidation(UUID fieldId, UUID validationId,
                                                              UpdateMetadataFieldValidationRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        requireField(fieldId, tenantId);
        MetadataFieldValidation validation = findOwnedByFieldAndTenant(validationId, fieldId, tenantId);

        validation.setRuleValue(request.ruleValue());
        validation.setErrorMessage(request.errorMessage());
        if (request.severity() != null) {
            validation.setSeverity(request.severity());
        }
        validation.setUpdatedBy(TenantContext.getUserId());
        validation.setUpdatedAt(OffsetDateTime.now());

        MetadataFieldValidation saved = validationRepository.save(validation);
        schemaCacheService.evictTenant(tenantId);
        log.info("Updated metadata field validation {}", saved.getValidationId());
        return validationMapper.toResponse(saved);
    }

    @Override
    public void deleteValidation(UUID fieldId, UUID validationId) {
        UUID tenantId = TenantContext.getTenantId();
        requireField(fieldId, tenantId);
        MetadataFieldValidation validation = findOwnedByFieldAndTenant(validationId, fieldId, tenantId);

        validation.setDeleted(true);
        validation.setDeletedAt(OffsetDateTime.now());
        validation.setUpdatedBy(TenantContext.getUserId());
        validation.setUpdatedAt(OffsetDateTime.now());
        validationRepository.save(validation);
        schemaCacheService.evictTenant(tenantId);
        log.info("Deleted metadata field validation {}", validationId);
    }

    private void requireField(UUID fieldId, UUID tenantId) {
        metadataFieldRepository.findByFieldIdAndTenantIdAndDeletedFalse(fieldId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MetadataField", fieldId));
    }

    private MetadataFieldValidation findOwnedByFieldAndTenant(UUID validationId, UUID fieldId, UUID tenantId) {
        MetadataFieldValidation validation = validationRepository
                .findByValidationIdAndTenantIdAndDeletedFalse(validationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("MetadataFieldValidation", validationId));
        if (!validation.getFieldId().equals(fieldId)) {
            throw new ResourceNotFoundException("MetadataFieldValidation", validationId);
        }
        return validation;
    }
}
