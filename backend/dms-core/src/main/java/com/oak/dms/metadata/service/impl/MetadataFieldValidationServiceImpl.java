package com.oak.dms.metadata.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.dto.CreateMetadataFieldValidationRequest;
import com.oak.dms.metadata.mapper.MetadataFieldValidationMapper;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldValidationRequest;
import com.oak.dms.metadata.entity.MetadataFieldValidation;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.repository.MetadataFieldValidationRepository;
import com.oak.dms.metadata.service.MetadataFieldValidationService;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
    private final AuditService auditService;

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

        auditService.event(AuditActionCatalog.METADATA_FIELD_VALIDATION_CREATED)
                .objectType(AuditObjectTypes.METADATA_FIELD_VALIDATION)
                .objectId(saved.getValidationId())
                .resourceName(saved.getRuleType().name())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "fieldId", fieldId,
                        "ruleType", saved.getRuleType(),
                        "ruleValue", saved.getRuleValue() == null ? "" : saved.getRuleValue(),
                        "severity", saved.getSeverity()))
                .log();

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

        Map<String, Object> previousValue = Map.of(
                "ruleValue", validation.getRuleValue() == null ? "" : validation.getRuleValue(),
                "errorMessage", validation.getErrorMessage() == null ? "" : validation.getErrorMessage(),
                "severity", validation.getSeverity());

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

        auditService.event(AuditActionCatalog.METADATA_FIELD_VALIDATION_UPDATED)
                .objectType(AuditObjectTypes.METADATA_FIELD_VALIDATION)
                .objectId(saved.getValidationId())
                .resourceName(saved.getRuleType().name())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "ruleValue", saved.getRuleValue() == null ? "" : saved.getRuleValue(),
                        "errorMessage", saved.getErrorMessage() == null ? "" : saved.getErrorMessage(),
                        "severity", saved.getSeverity()))
                .log();

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

        auditService.event(AuditActionCatalog.METADATA_FIELD_VALIDATION_DELETED)
                .objectType(AuditObjectTypes.METADATA_FIELD_VALIDATION)
                .objectId(validationId)
                .resourceName(validation.getRuleType().name())
                .outcome(AuditOutcome.SUCCESS)
                .log();
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
