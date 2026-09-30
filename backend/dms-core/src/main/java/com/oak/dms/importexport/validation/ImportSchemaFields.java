package com.oak.dms.importexport.validation;

import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.entity.DocumentTypeSchema.FieldMapping;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads a document type's fields at one specific schema version - the
 * version a batch's template was generated from, which may no longer be
 * current. {@code DocumentTypeFieldService#getSchema} only covers the
 * current version, so this reads the same two repositories and joins them
 * the same way: the version's {@link FieldMapping}s in displayOrder order,
 * each matched to its (non-deleted) catalogue {@link MetadataField};
 * mappings whose field is gone are skipped.
 */
@Component
@RequiredArgsConstructor
public class ImportSchemaFields {

    private final DocumentTypeSchemaRepository documentTypeSchemaRepository;
    private final MetadataFieldRepository metadataFieldRepository;

    /**
     * @return the fields keyed by field_key, in displayOrder order
     * @throws IllegalStateException if the version doesn't exist - a batch's
     *                               (type, version) is a foreign key to it,
     *                               so this means corrupt data, not bad input
     */
    public Map<String, ImportField> load(UUID tenantId, UUID documentTypeId, int schemaVersion) {
        DocumentTypeSchema schema = documentTypeSchemaRepository
                .findByDocumentTypeIdAndTenantIdAndSchemaVersion(documentTypeId, tenantId, schemaVersion)
                .orElseThrow(() -> new IllegalStateException(
                        "No schema version " + schemaVersion + " for document type " + documentTypeId));

        List<FieldMapping> mapping = schema.getFields().stream()
                .sorted(Comparator.comparingInt(FieldMapping::displayOrder))
                .toList();
        List<UUID> fieldIds = mapping.stream().map(FieldMapping::fieldId).distinct().toList();
        Map<UUID, MetadataField> fieldsById = metadataFieldRepository
                .findAllByFieldIdInAndTenantIdAndDeletedFalse(fieldIds, tenantId).stream()
                .collect(Collectors.toMap(MetadataField::getFieldId, Function.identity()));

        Map<String, ImportField> fields = new LinkedHashMap<>();
        for (FieldMapping m : mapping) {
            MetadataField field = fieldsById.get(m.fieldId());
            if (field != null) {
                fields.put(field.getFieldKey(), new ImportField(field.getFieldKey(), field.getLabel(),
                        field.getDataType(), m.mandatory(), m.hidden()));
            }
        }
        return fields;
    }
}
