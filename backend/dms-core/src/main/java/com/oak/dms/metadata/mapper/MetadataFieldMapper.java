package com.oak.dms.metadata.mapper;

import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.entity.MetadataField;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MetadataFieldMapper {
    MetadataFieldResponse toResponse(MetadataField field);
}
