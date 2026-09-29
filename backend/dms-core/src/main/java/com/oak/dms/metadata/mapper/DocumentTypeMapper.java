package com.oak.dms.metadata.mapper;

import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.entity.DocumentType;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DocumentTypeMapper {
    DocumentTypeResponse toResponse(DocumentType documentType);
}
