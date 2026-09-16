package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.DocumentTypeResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DocumentTypeMapper {
    DocumentTypeResponse toResponse(DocumentType documentType);
}
