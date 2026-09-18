package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.MetadataFieldResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MetadataFieldMapper {
    MetadataFieldResponse toResponse(MetadataField field);
}
