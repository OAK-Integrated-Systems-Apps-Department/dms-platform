package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DocumentVersionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** {@code storageLocationId}/{@code storageObjectKey} have no corresponding response field - see {@link DocumentVersionResponse}'s own doc comment. */
@Mapper(componentModel = "spring")
public interface DocumentMapper {

    @Mapping(target = "documentTypeName", source = "documentTypeName")
    DocumentResponse toResponse(Document document, String documentTypeName);

    DocumentVersionResponse toResponse(DocumentVersion version);
}
