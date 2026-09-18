package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import org.mapstruct.Mapper;

/**
 * Hand-written rather than MapStruct-generated: {@link DocumentTypeFieldResponse}
 * needs fieldKey/label from the joined {@link MetadataField}, which
 * {@link DocumentTypeField} doesn't carry itself, and both source entities
 * share several property names (tenantId, fieldId, ...) that would make
 * MapStruct's multi-source auto-matching ambiguous - same reasoning as
 * {@code UserMapper}'s manual overloads for fields the entity alone can't
 * supply.
 */
@Mapper(componentModel = "spring")
public interface DocumentTypeFieldMapper {

    default DocumentTypeFieldResponse toResponse(DocumentTypeField mapping, MetadataField field) {
        return new DocumentTypeFieldResponse(
                mapping.getDocumentTypeFieldId(),
                mapping.getDocumentTypeId(),
                mapping.getFieldId(),
                field.getFieldKey(),
                field.getLabel(),
                mapping.getSchemaVersion(),
                mapping.getStatus().name(),
                mapping.isMandatory(),
                mapping.isHidden(),
                mapping.isRequiresBackfill(),
                mapping.getDisplayOrder(),
                mapping.getFieldGroup(),
                mapping.getDefaultValueOverride(),
                mapping.getArchivedAt()
        );
    }
}
