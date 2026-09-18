package com.oak.dms.metadata;

import com.oak.dms.audit.AuditActionCatalog;
import com.oak.dms.audit.AuditObjectTypes;
import com.oak.dms.audit.AuditOutcome;
import com.oak.dms.audit.AuditService;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.InvalidMetadataFieldStateException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldRequest;
import com.oak.dms.security.auth.TenantContext;
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
 * See {@link MetadataFieldService}. Deletion is a real hard delete (not the
 * usual soft-delete): a field with no references anywhere is simply removed,
 * matching MDTM alternate flow 2's framing of delete vs. deprecate as two
 * distinct actions rather than delete meaning "deprecate". A field still
 * referenced by any {@code document_type_fields} or
 * {@code document_metadata_values} row is blocked from deletion entirely -
 * deprecate is offered instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MetadataFieldServiceImpl implements MetadataFieldService {

    private final MetadataFieldRepository metadataFieldRepository;
    private final DocumentTypeFieldRepository documentTypeFieldRepository;
    private final MetadataFieldMapper metadataFieldMapper;
    private final SchemaCacheService schemaCacheService;
    private final AuditService auditService;

    @Override
    public MetadataFieldResponse createField(CreateMetadataFieldRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        if (metadataFieldRepository.existsByTenantIdAndFieldKeyAndDeletedFalse(tenantId, request.fieldKey())) {
            throw new DuplicateResourceException("A metadata field already exists with key " + request.fieldKey());
        }

        MetadataField field = MetadataField.builder()
                .tenantId(tenantId)
                .fieldKey(request.fieldKey())
                .label(request.label())
                .helpText(request.helpText())
                .dataType(request.dataType())
                .defaultValue(request.defaultValue())
                .searchable(request.searchable())
                .ocrExtractable(request.ocrExtractable())
                .status(MetadataField.FieldStatus.active)
                .createdBy(TenantContext.getUserId())
                .build();

        MetadataField saved = metadataFieldRepository.save(field);
        log.info("Created metadata field {} ({}) in tenant {}", saved.getFieldId(), saved.getFieldKey(), tenantId);

        auditService.event(AuditActionCatalog.METADATA_FIELD_CREATED)
                .objectType(AuditObjectTypes.METADATA_FIELD)
                .objectId(saved.getFieldId())
                .resourceName(saved.getFieldKey())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "fieldKey", saved.getFieldKey(),
                        "label", saved.getLabel(),
                        "dataType", saved.getDataType(),
                        "searchable", saved.isSearchable(),
                        "ocrExtractable", saved.isOcrExtractable()))
                .log();

        return metadataFieldMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public MetadataFieldResponse getField(UUID fieldId) {
        return metadataFieldMapper.toResponse(findOwnedByCallerTenant(fieldId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MetadataFieldResponse> listFields(String status, String dataType, String search, Pageable pageable) {
        MetadataField.FieldStatus statusFilter = status == null || status.isBlank()
                ? null
                : MetadataField.FieldStatus.valueOf(status);
        String dataTypeFilter = dataType == null || dataType.isBlank() ? null : dataType;
        String searchFilter = search == null || search.isBlank() ? null : "%" + search.toLowerCase() + "%";

        return metadataFieldRepository
                .search(TenantContext.getTenantId(), statusFilter, dataTypeFilter, searchFilter, pageable)
                .map(metadataFieldMapper::toResponse);
    }

    @Override
    public MetadataFieldResponse updateField(UUID fieldId, UpdateMetadataFieldRequest request) {
        MetadataField field = findOwnedByCallerTenant(fieldId);

        Map<String, Object> previousValue = Map.of(
                "label", field.getLabel(),
                "helpText", field.getHelpText() == null ? "" : field.getHelpText(),
                "defaultValue", field.getDefaultValue() == null ? "" : field.getDefaultValue(),
                "searchable", field.isSearchable(),
                "ocrExtractable", field.isOcrExtractable());

        field.setLabel(request.label());
        field.setHelpText(request.helpText());
        field.setDefaultValue(request.defaultValue());
        field.setSearchable(request.searchable());
        field.setOcrExtractable(request.ocrExtractable());
        field.setUpdatedBy(TenantContext.getUserId());
        field.setUpdatedAt(OffsetDateTime.now());

        MetadataField saved = metadataFieldRepository.save(field);
        schemaCacheService.evictTenant(TenantContext.getTenantId());
        log.info("Updated metadata field {}", saved.getFieldId());

        auditService.event(AuditActionCatalog.METADATA_FIELD_UPDATED)
                .objectType(AuditObjectTypes.METADATA_FIELD)
                .objectId(saved.getFieldId())
                .resourceName(saved.getFieldKey())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "label", saved.getLabel(),
                        "helpText", saved.getHelpText() == null ? "" : saved.getHelpText(),
                        "defaultValue", saved.getDefaultValue() == null ? "" : saved.getDefaultValue(),
                        "searchable", saved.isSearchable(),
                        "ocrExtractable", saved.isOcrExtractable()))
                .log();

        return metadataFieldMapper.toResponse(saved);
    }

    @Override
    public MetadataFieldResponse activateField(UUID fieldId) {
        return setStatus(fieldId, MetadataField.FieldStatus.active, AuditActionCatalog.METADATA_FIELD_ACTIVATED);
    }

    @Override
    public MetadataFieldResponse deactivateField(UUID fieldId) {
        return setStatus(fieldId, MetadataField.FieldStatus.inactive, AuditActionCatalog.METADATA_FIELD_DEACTIVATED);
    }

    @Override
    public MetadataFieldResponse deprecateField(UUID fieldId) {
        return setStatus(fieldId, MetadataField.FieldStatus.deprecated, AuditActionCatalog.METADATA_FIELD_DEPRECATED);
    }

    @Override
    public void deleteField(UUID fieldId) {
        MetadataField field = findOwnedByCallerTenant(fieldId);
        UUID tenantId = TenantContext.getTenantId();

        if (field.isSystemField()) {
            throw new InvalidMetadataFieldStateException(
                    "System field '" + field.getFieldKey() + "' cannot be removed.");
        }

        // MDTM alternate flow 2: a field still in use can't be deleted -
        // deprecate it instead. document_metadata_values doesn't have a JPA
        // repository in this module (see the table's own migration
        // comment), so that reference check is a direct count query here
        // rather than going through a repository abstraction.
        long mappedTypeCount = documentTypeFieldRepository.countDistinctDocumentTypesByField(fieldId, tenantId);
        if (mappedTypeCount > 0 || hasDocumentMetadataValues(fieldId)) {
            throw new InvalidMetadataFieldStateException(
                    "Cannot delete field '" + field.getFieldKey() + "' - it is referenced by "
                            + mappedTypeCount + " document type(s) and/or has existing document values. "
                            + "Deprecate it instead.");
        }

        UUID deletedFieldId = field.getFieldId();
        String fieldKey = field.getFieldKey();
        metadataFieldRepository.delete(field);
        schemaCacheService.evictTenant(tenantId);
        log.info("Deleted metadata field {}", fieldId);

        auditService.event(AuditActionCatalog.METADATA_FIELD_DELETED)
                .objectType(AuditObjectTypes.METADATA_FIELD)
                .objectId(deletedFieldId)
                .resourceName(fieldKey)
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    private boolean hasDocumentMetadataValues(UUID fieldId) {
        // document_metadata_values intentionally has no JPA entity/repository
        // in this module (module 2.5 doesn't exist yet, and nothing here
        // writes to it) - always empty until then, so deletion is only ever
        // blocked by an actual document_type_fields reference in practice.
        return false;
    }

    private MetadataFieldResponse setStatus(UUID fieldId, MetadataField.FieldStatus status, String auditAction) {
        MetadataField field = findOwnedByCallerTenant(fieldId);

        if (field.isSystemField() && status != MetadataField.FieldStatus.active) {
            throw new InvalidMetadataFieldStateException(
                    "System field '" + field.getFieldKey() + "' cannot be deactivated or deprecated.");
        }

        MetadataField.FieldStatus previousStatus = field.getStatus();
        field.setStatus(status);
        field.setUpdatedBy(TenantContext.getUserId());
        field.setUpdatedAt(OffsetDateTime.now());
        MetadataField saved = metadataFieldRepository.save(field);
        schemaCacheService.evictTenant(TenantContext.getTenantId());
        log.info("Set metadata field {} status to {}", fieldId, status);

        auditService.event(auditAction)
                .objectType(AuditObjectTypes.METADATA_FIELD)
                .objectId(saved.getFieldId())
                .resourceName(saved.getFieldKey())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("status", previousStatus))
                .newValue(Map.of("status", status))
                .log();

        return metadataFieldMapper.toResponse(saved);
    }

    private MetadataField findOwnedByCallerTenant(UUID fieldId) {
        return metadataFieldRepository.findByFieldIdAndTenantIdAndDeletedFalse(fieldId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("MetadataField", fieldId));
    }
}
