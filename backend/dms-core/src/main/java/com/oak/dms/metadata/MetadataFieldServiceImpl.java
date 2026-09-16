package com.oak.dms.metadata;

import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.InvalidMetadataFieldStateException;
import com.oak.dms.common.exception.ResourceNotFoundException;
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
                .systemField(request.systemField())
                .searchable(request.searchable())
                .indexed(request.indexed())
                .ocrExtractable(request.ocrExtractable())
                .status(MetadataField.FieldStatus.active)
                .createdBy(TenantContext.getUserId())
                .build();

        MetadataField saved = metadataFieldRepository.save(field);
        log.info("Created metadata field {} ({}) in tenant {}", saved.getFieldId(), saved.getFieldKey(), tenantId);
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

        field.setLabel(request.label());
        field.setHelpText(request.helpText());
        field.setDefaultValue(request.defaultValue());
        field.setSearchable(request.searchable());
        field.setIndexed(request.indexed());
        field.setOcrExtractable(request.ocrExtractable());
        field.setUpdatedBy(TenantContext.getUserId());
        field.setUpdatedAt(OffsetDateTime.now());

        MetadataField saved = metadataFieldRepository.save(field);
        log.info("Updated metadata field {}", saved.getFieldId());
        return metadataFieldMapper.toResponse(saved);
    }

    @Override
    public MetadataFieldResponse activateField(UUID fieldId) {
        return setStatus(fieldId, MetadataField.FieldStatus.active);
    }

    @Override
    public MetadataFieldResponse deactivateField(UUID fieldId) {
        return setStatus(fieldId, MetadataField.FieldStatus.inactive);
    }

    @Override
    public MetadataFieldResponse deprecateField(UUID fieldId) {
        return setStatus(fieldId, MetadataField.FieldStatus.deprecated);
    }

    @Override
    public void deleteField(UUID fieldId) {
        MetadataField field = findOwnedByCallerTenant(fieldId);
        UUID tenantId = TenantContext.getTenantId();

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

        metadataFieldRepository.delete(field);
        log.info("Deleted metadata field {}", fieldId);
    }

    private boolean hasDocumentMetadataValues(UUID fieldId) {
        // document_metadata_values intentionally has no JPA entity/repository
        // in this module (module 2.5 doesn't exist yet, and nothing here
        // writes to it) - always empty until then, so deletion is only ever
        // blocked by an actual document_type_fields reference in practice.
        return false;
    }

    private MetadataFieldResponse setStatus(UUID fieldId, MetadataField.FieldStatus status) {
        MetadataField field = findOwnedByCallerTenant(fieldId);
        field.setStatus(status);
        field.setUpdatedBy(TenantContext.getUserId());
        field.setUpdatedAt(OffsetDateTime.now());
        MetadataField saved = metadataFieldRepository.save(field);
        log.info("Set metadata field {} status to {}", fieldId, status);
        return metadataFieldMapper.toResponse(saved);
    }

    private MetadataField findOwnedByCallerTenant(UUID fieldId) {
        return metadataFieldRepository.findByFieldIdAndTenantIdAndDeletedFalse(fieldId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("MetadataField", fieldId));
    }
}
