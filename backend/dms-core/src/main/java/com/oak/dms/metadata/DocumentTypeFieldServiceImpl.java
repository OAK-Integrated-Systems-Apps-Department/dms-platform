package com.oak.dms.metadata;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.DeprecatedFieldMappingException;
import com.oak.dms.common.exception.DuplicateDisplayOrderException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * See {@link DocumentTypeFieldService}. {@link #replaceMapping} is the only
 * mutating method - everything else is a read of an already-published
 * version.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentTypeFieldServiceImpl implements DocumentTypeFieldService {

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldRepository documentTypeFieldRepository;
    private final MetadataFieldRepository metadataFieldRepository;
    private final MetadataFieldOptionRepository metadataFieldOptionRepository;
    private final MetadataFieldValidationRepository metadataFieldValidationRepository;
    private final DocumentTypeFieldMapper documentTypeFieldMapper;
    private final SchemaCacheService schemaCacheService;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public List<DocumentTypeFieldResponse> getCurrentFields(UUID documentTypeId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentType documentType = findDocumentType(documentTypeId, tenantId);
        return toResponses(documentTypeFieldRepository
                .findByDocumentTypeAndVersion(documentTypeId, tenantId, documentType.getCurrentSchemaVersion()), tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentTypeFieldResponse> getFieldsAtVersion(UUID documentTypeId, int version) {
        UUID tenantId = TenantContext.getTenantId();
        findDocumentType(documentTypeId, tenantId);
        return toResponses(documentTypeFieldRepository.findByDocumentTypeAndVersion(documentTypeId, tenantId, version), tenantId);
    }

    @Override
    public List<DocumentTypeFieldResponse> replaceMapping(UUID documentTypeId, ReplaceDocumentTypeFieldsRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        // Pessimistic row lock: two admins publishing a new version for the
        // same document type at once must not both compute
        // currentSchemaVersion + 1 and collide - the second call blocks
        // here until the first transaction commits or rolls back.
        DocumentType documentType = documentTypeRepository
                .lockByDocumentTypeIdAndTenantId(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));

        int oldVersion = documentType.getCurrentSchemaVersion();
        int newVersion = oldVersion + 1;

        List<DocumentTypeField> oldMapping =
                documentTypeFieldRepository.findByDocumentTypeAndVersion(documentTypeId, tenantId, oldVersion);
        Map<UUID, DocumentTypeField> oldMappingByField = oldMapping.stream()
                .collect(Collectors.toMap(DocumentTypeField::getFieldId, m -> m));

        List<UUID> requestedFieldIds = request.fields().stream().map(DocumentTypeFieldMappingItem::fieldId).toList();

        List<Integer> requestedOrders = request.fields().stream()
                .map(DocumentTypeFieldMappingItem::displayOrder).toList();
        if (requestedOrders.size() != requestedOrders.stream().distinct().count()) {
            throw new DuplicateDisplayOrderException("Duplicate displayOrder values in the field mapping request");
        }

        Map<UUID, MetadataField> fieldsById = metadataFieldRepository
                .findAllByFieldIdInAndTenantIdAndDeletedFalse(requestedFieldIds, tenantId).stream()
                .collect(Collectors.toMap(MetadataField::getFieldId, f -> f));

        List<String> deprecatedFieldKeys = fieldsById.values().stream()
                .filter(f -> f.getStatus() == MetadataField.FieldStatus.deprecated)
                .map(MetadataField::getFieldKey)
                .sorted()
                .toList();
        if (!deprecatedFieldKeys.isEmpty()) {
            throw new DeprecatedFieldMappingException(
                    "Cannot map deprecated field(s): " + String.join(", ", deprecatedFieldKeys));
        }

        // Sort by the client's submitted displayOrder, then re-stamp as a
        // canonical (index+1)*10 sequence so the stored values are always
        // gap-free regardless of what the client sent - duplicates were
        // already rejected above, so this sort defines a total order.
        List<DocumentTypeFieldMappingItem> orderedItems = request.fields().stream()
                .sorted(Comparator.comparingInt(DocumentTypeFieldMappingItem::displayOrder))
                .toList();

        List<DocumentTypeField> newMapping = IntStream.range(0, orderedItems.size())
                .mapToObj(index -> {
                    DocumentTypeFieldMappingItem item = orderedItems.get(index);
                    MetadataField field = fieldsById.get(item.fieldId());
                    if (field == null) {
                        throw new ResourceNotFoundException("MetadataField", item.fieldId());
                    }
                    DocumentTypeField previous = oldMappingByField.get(item.fieldId());
                    boolean wasMandatoryBefore = previous != null && previous.isMandatory();
                    // MDTM workflow step 7 / alternate flow 3: a field that becomes
                    // newly mandatory on this publish is flagged so legacy documents
                    // can be flagged for optional backfill rather than rejected.
                    boolean requiresBackfill = item.mandatory() && !wasMandatoryBefore;

                    return DocumentTypeField.builder()
                            .tenantId(tenantId)
                            .documentTypeId(documentTypeId)
                            .fieldId(item.fieldId())
                            .schemaVersion(newVersion)
                            .status(DocumentTypeField.MappingStatus.active)
                            .mandatory(item.mandatory())
                            .hidden(item.hidden())
                            .requiresBackfill(requiresBackfill)
                            .displayOrder((index + 1) * 10)
                            .fieldGroup(item.fieldGroup())
                            .defaultValueOverride(item.defaultValueOverride())
                            .createdBy(TenantContext.getUserId())
                            .build();
                }).toList();

        OffsetDateTime now = OffsetDateTime.now();
        oldMapping.forEach(m -> {
            m.setStatus(DocumentTypeField.MappingStatus.archived);
            m.setArchivedAt(now);
            m.setUpdatedBy(TenantContext.getUserId());
            m.setUpdatedAt(now);
        });
        documentTypeFieldRepository.saveAll(oldMapping);
        List<DocumentTypeField> savedNewMapping = documentTypeFieldRepository.saveAll(newMapping);

        documentType.setCurrentSchemaVersion(newVersion);
        documentType.setUpdatedBy(TenantContext.getUserId());
        documentType.setUpdatedAt(now);
        documentTypeRepository.save(documentType);
        schemaCacheService.evictTenant(tenantId);

        log.info("Published schema version {} for document type {} ({} fields)",
                newVersion, documentTypeId, savedNewMapping.size());

        List<Map<String, Object>> newFieldSummaries = savedNewMapping.stream()
                .map(m -> {
                    MetadataField field = fieldsById.get(m.getFieldId());
                    return Map.<String, Object>of(
                            "fieldId", m.getFieldId(),
                            "fieldKey", field != null ? field.getFieldKey() : "",
                            "mandatory", m.isMandatory(),
                            "hidden", m.isHidden(),
                            "displayOrder", m.getDisplayOrder());
                })
                .toList();

        auditService.event(AuditActionCatalog.DOCUMENT_TYPE_SCHEMA_PUBLISHED)
                .objectType(AuditObjectTypes.DOCUMENT_TYPE_SCHEMA)
                .objectId(documentTypeId)
                .resourceName(documentType.getTypeCode())
                .objectVersion(newVersion)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of(
                        "schemaVersion", oldVersion,
                        "fieldIds", oldMapping.stream().map(DocumentTypeField::getFieldId).toList()))
                .newValue(Map.of(
                        "schemaVersion", newVersion,
                        "fields", newFieldSummaries))
                .log();

        return toResponses(savedNewMapping, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentTypeSchemaResponse getSchema(UUID documentTypeId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentType documentType = findDocumentType(documentTypeId, tenantId);

        List<DocumentTypeField> mapping = documentTypeFieldRepository
                .findByDocumentTypeAndVersion(documentTypeId, tenantId, documentType.getCurrentSchemaVersion());
        Map<UUID, MetadataField> fieldsById = fetchFieldsByIds(mapping, tenantId);

        List<DocumentTypeSchemaResponse.SchemaField> schemaFields = mapping.stream()
                .filter(m -> fieldsById.containsKey(m.getFieldId()))
                .map(m -> toSchemaField(m, fieldsById.get(m.getFieldId()), tenantId))
                .toList();

        return new DocumentTypeSchemaResponse(
                documentType.getDocumentTypeId(),
                documentType.getTypeCode(),
                documentType.getTypeName(),
                documentType.getCurrentSchemaVersion(),
                schemaFields);
    }

    private DocumentTypeSchemaResponse.SchemaField toSchemaField(DocumentTypeField mapping, MetadataField field, UUID tenantId) {
        List<MetadataFieldOptionResponse> options = MetadataField.OPTION_BACKED_DATA_TYPES.contains(field.getDataType())
                ? metadataFieldOptionRepository.findAllActiveByFieldOrdered(field.getFieldId(), tenantId).stream()
                    .map(o -> new MetadataFieldOptionResponse(o.getOptionId(), o.getFieldId(), o.getParentOptionId(),
                            o.getOptionValue(), o.getOptionLabel(), o.getDisplayOrder(), o.getStatus().name()))
                    .toList()
                : List.of();

        List<MetadataFieldValidationResponse> validations = metadataFieldValidationRepository
                .findAllByField(field.getFieldId(), tenantId).stream()
                .map(v -> new MetadataFieldValidationResponse(v.getValidationId(), v.getFieldId(),
                        v.getRuleType().name(), v.getRuleValue(), v.getErrorMessage(), v.getSeverity().name()))
                .toList();

        String effectiveDefault = mapping.getDefaultValueOverride() != null
                ? mapping.getDefaultValueOverride()
                : field.getDefaultValue();

        return new DocumentTypeSchemaResponse.SchemaField(
                field.getFieldId(), field.getFieldKey(), field.getLabel(), field.getHelpText(), field.getDataType(),
                field.getStatus().name(), mapping.isMandatory(), mapping.isHidden(), mapping.getDisplayOrder(),
                mapping.getFieldGroup(), effectiveDefault, options, validations);
    }

    private List<DocumentTypeFieldResponse> toResponses(List<DocumentTypeField> mapping, UUID tenantId) {
        Map<UUID, MetadataField> fieldsById = fetchFieldsByIds(mapping, tenantId);
        return mapping.stream()
                .filter(m -> fieldsById.containsKey(m.getFieldId()))
                .map(m -> documentTypeFieldMapper.toResponse(m, fieldsById.get(m.getFieldId())))
                .toList();
    }

    private Map<UUID, MetadataField> fetchFieldsByIds(List<DocumentTypeField> mapping, UUID tenantId) {
        List<UUID> fieldIds = mapping.stream().map(DocumentTypeField::getFieldId).distinct().toList();
        return metadataFieldRepository.findAllByFieldIdInAndTenantIdAndDeletedFalse(fieldIds, tenantId).stream()
                .collect(Collectors.toMap(MetadataField::getFieldId, f -> f));
    }

    private DocumentType findDocumentType(UUID documentTypeId, UUID tenantId) {
        return documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
    }
}
