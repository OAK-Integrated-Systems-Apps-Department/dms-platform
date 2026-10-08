package com.oak.dms.metadata.mapper;

import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.entity.MetadataField;
import org.mapstruct.Mapper;

/**
 * Hand-written rather than MapStruct-generated: {@link DocumentTypeFieldResponse}
 * combines three sources - the version-level data (schemaVersion, status,
 * archivedAt) from the {@link DocumentTypeSchema} row, the per-field
 * placement from one of its {@link DocumentTypeSchema.FieldMapping}s, and
 * fieldKey/label from the joined {@link MetadataField} - and those sources
 * share several property names (fieldId, status, ...) that would make
 * MapStruct's multi-source auto-matching ambiguous - same reasoning as
 * {@code UserMapper}'s manual overloads for fields the entity alone can't
 * supply.
 */
@Mapper(componentModel = "spring")
public interface DocumentTypeFieldMapper {

    default DocumentTypeFieldResponse toResponse(DocumentTypeSchema schema, DocumentTypeSchema.FieldMapping mapping,
                                                 MetadataField field) {
        return new DocumentTypeFieldResponse(
                mapping.mappingId(),
                schema.getDocumentTypeId(),
                mapping.fieldId(),
                field.getFieldKey(),
                field.getLabel(),
                schema.getSchemaVersion(),
                schema.getStatus().name(),
                mapping.mandatory(),
                mapping.hidden(),
                mapping.requiresBackfill(),
                mapping.displayOrder(),
                mapping.fieldGroup(),
                mapping.defaultValueOverride(),
                schema.getArchivedAt()
        );
    }
}
