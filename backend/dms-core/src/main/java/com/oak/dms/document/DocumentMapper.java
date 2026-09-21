package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DocumentVersionResponse;
import org.mapstruct.Mapper;

/** {@code storageLocationId}/{@code storageObjectKey} have no corresponding response field - see {@link DocumentVersionResponse}'s own doc comment. */
@Mapper(componentModel = "spring")
public interface DocumentMapper {

    DocumentResponse toResponse(Document document);

    DocumentVersionResponse toResponse(DocumentVersion version);
}
