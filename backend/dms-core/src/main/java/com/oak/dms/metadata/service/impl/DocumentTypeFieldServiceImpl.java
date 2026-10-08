package com.oak.dms.metadata.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.DeprecatedFieldMappingException;
import com.oak.dms.common.exception.DuplicateDisplayOrderException;
import com.oak.dms.common.exception.DuplicateFieldMappingException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.cache.SchemaCacheService;
import com.oak.dms.metadata.mapper.DocumentTypeFieldMapper;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.entity.DocumentTypeSchema.FieldMapping;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.repository.MetadataFieldValidationRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * See {@link DocumentTypeFieldService}. {@link #replaceMapping} is the only
 * mutating method - everything else is a read of an already-published
 * version. Each version's mapping is one {@link DocumentTypeSchema} row; a
 * version with no row (a type created mid-deploy by a previous release, or
 * an explicit {@code ?version=N} that was never published) reads as an
 * empty mapping, never a 404.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentTypeFieldServiceImpl implements DocumentTypeFieldService {

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeSchemaRepository documentTypeSchemaRepository;
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
        return findSchema(documentTypeId, tenantId, documentType.getCurrentSchemaVersion())
                .map(schema -> toResponses(schema, tenantId))
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentTypeFieldResponse> getFieldsAtVersion(UUID documentTypeId, int version) {
        UUID tenantId = TenantContext.getTenantId();
        findDocumentType(documentTypeId, tenantId);
        return findSchema(documentTypeId, tenantId, version)
                .map(schema -> toResponses(schema, tenantId))
                .orElse(List.of());
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

        Optional<DocumentTypeSchema> oldSchema = findSchema(documentTypeId, tenantId, oldVersion);
        List<FieldMapping> oldMapping = oldSchema.map(DocumentTypeFieldServiceImpl::orderedFields).orElse(List.of());
        Map<UUID, FieldMapping> oldMappingByField = oldMapping.stream()
                .collect(Collectors.toMap(FieldMapping::fieldId, m -> m));

        List<UUID> requestedFieldIds = request.fields().stream().map(DocumentTypeFieldMappingItem::fieldId).toList();

        List<Integer> requestedOrders = request.fields().stream()
                .map(DocumentTypeFieldMappingItem::displayOrder).toList();
        if (requestedOrders.size() != requestedOrders.stream().distinct().count()) {
            throw new DuplicateDisplayOrderException("Duplicate displayOrder values in the field mapping request");
        }

        // A version's mapping is one JSONB array, so unlike the old
        // per-row table there's no unique constraint to catch a field
        // listed twice - reject it here instead.
        if (requestedFieldIds.size() != requestedFieldIds.stream().distinct().count()) {
            throw new DuplicateFieldMappingException("Duplicate fieldId values in the field mapping request");
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

        List<FieldMapping> newMapping = IntStream.range(0, orderedItems.size())
                .mapToObj(index -> {
                    DocumentTypeFieldMappingItem item = orderedItems.get(index);
                    MetadataField field = fieldsById.get(item.fieldId());
                    if (field == null) {
                        throw new ResourceNotFoundException("MetadataField", item.fieldId());
                    }
                    FieldMapping previous = oldMappingByField.get(item.fieldId());
                    boolean wasMandatoryBefore = previous != null && previous.mandatory();
                    // MDTM workflow step 7 / alternate flow 3: a field that becomes
                    // newly mandatory on this publish is flagged so legacy documents
                    // can be flagged for optional backfill rather than rejected.
                    boolean requiresBackfill = item.mandatory() && !wasMandatoryBefore;

                    return new FieldMapping(
                            UUID.randomUUID(),
                            item.fieldId(),
                            item.mandatory(),
                            item.hidden(),
                            requiresBackfill,
                            (index + 1) * 10,
                            item.fieldGroup(),
                            item.defaultValueOverride());
                }).toList();

        OffsetDateTime now = OffsetDateTime.now();
        oldSchema.ifPresent(schema -> {
            schema.setStatus(DocumentTypeSchema.SchemaStatus.archived);
            schema.setArchivedAt(now);
            documentTypeSchemaRepository.save(schema);
        });
        DocumentTypeSchema savedNewSchema = documentTypeSchemaRepository.save(DocumentTypeSchema.builder()
                .tenantId(tenantId)
                .documentTypeId(documentTypeId)
                .schemaVersion(newVersion)
                .status(DocumentTypeSchema.SchemaStatus.active)
                .fields(newMapping)
                .publishedBy(TenantContext.getUserId())
                .publishedAt(now)
                .build());

        documentType.setCurrentSchemaVersion(newVersion);
        documentType.setUpdatedBy(TenantContext.getUserId());
        documentType.setUpdatedAt(now);
        documentTypeRepository.save(documentType);
        schemaCacheService.evictTenant(tenantId);

        log.info("Published schema version {} for document type {} ({} fields)",
                newVersion, documentTypeId, newMapping.size());

        List<Map<String, Object>> newFieldSummaries = newMapping.stream()
                .map(m -> {
                    MetadataField field = fieldsById.get(m.fieldId());
                    return Map.<String, Object>of(
                            "fieldId", m.fieldId(),
                            "fieldKey", field != null ? field.getFieldKey() : "",
                            "mandatory", m.mandatory(),
                            "hidden", m.hidden(),
                            "displayOrder", m.displayOrder());
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
                        "fieldIds", oldMapping.stream().map(FieldMapping::fieldId).toList()))
                .newValue(Map.of(
                        "schemaVersion", newVersion,
                        "fields", newFieldSummaries))
                .log();

        return toResponses(savedNewSchema, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentTypeSchemaResponse getSchema(UUID documentTypeId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentType documentType = findDocumentType(documentTypeId, tenantId);

        List<FieldMapping> mapping = findSchema(documentTypeId, tenantId, documentType.getCurrentSchemaVersion())
                .map(DocumentTypeFieldServiceImpl::orderedFields)
                .orElse(List.of());
        Map<UUID, MetadataField> fieldsById = fetchFieldsByIds(mapping, tenantId);

        List<DocumentTypeSchemaResponse.SchemaField> schemaFields = mapping.stream()
                .filter(m -> fieldsById.containsKey(m.fieldId()))
                .map(m -> toSchemaField(m, fieldsById.get(m.fieldId()), tenantId))
                .toList();

        return new DocumentTypeSchemaResponse(
                documentType.getDocumentTypeId(),
                documentType.getTypeCode(),
                documentType.getTypeName(),
                documentType.getCurrentSchemaVersion(),
                schemaFields);
    }

    private DocumentTypeSchemaResponse.SchemaField toSchemaField(FieldMapping mapping, MetadataField field, UUID tenantId) {
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

        String effectiveDefault = mapping.defaultValueOverride() != null
                ? mapping.defaultValueOverride()
                : field.getDefaultValue();

        return new DocumentTypeSchemaResponse.SchemaField(
                field.getFieldId(), field.getFieldKey(), field.getLabel(), field.getHelpText(), field.getDataType(),
                // mapping is a record on this branch (accessors, not get*/is*);
                // ocrExtractable is the OCR branch's addition, re-applied here
                // because SchemaField declares it.
                field.getStatus().name(), mapping.mandatory(), mapping.hidden(), field.isOcrExtractable(),
                mapping.displayOrder(), mapping.fieldGroup(), effectiveDefault, options, validations);
    }

    private List<DocumentTypeFieldResponse> toResponses(DocumentTypeSchema schema, UUID tenantId) {
        List<FieldMapping> mapping = orderedFields(schema);
        Map<UUID, MetadataField> fieldsById = fetchFieldsByIds(mapping, tenantId);
        return mapping.stream()
                .filter(m -> fieldsById.containsKey(m.fieldId()))
                .map(m -> documentTypeFieldMapper.toResponse(schema, m, fieldsById.get(m.fieldId())))
                .toList();
    }

    private Map<UUID, MetadataField> fetchFieldsByIds(List<FieldMapping> mapping, UUID tenantId) {
        List<UUID> fieldIds = mapping.stream().map(FieldMapping::fieldId).distinct().toList();
        return metadataFieldRepository.findAllByFieldIdInAndTenantIdAndDeletedFalse(fieldIds, tenantId).stream()
                .collect(Collectors.toMap(MetadataField::getFieldId, f -> f));
    }

    /** Stored in displayOrder order already; sorted again so reads never depend on that. */
    private static List<FieldMapping> orderedFields(DocumentTypeSchema schema) {
        return schema.getFields().stream()
                .sorted(Comparator.comparingInt(FieldMapping::displayOrder))
                .toList();
    }

    private Optional<DocumentTypeSchema> findSchema(UUID documentTypeId, UUID tenantId, int version) {
        return documentTypeSchemaRepository.findByDocumentTypeIdAndTenantIdAndSchemaVersion(documentTypeId, tenantId, version);
    }

    private DocumentType findDocumentType(UUID documentTypeId, UUID tenantId) {
        return documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
    }
}
