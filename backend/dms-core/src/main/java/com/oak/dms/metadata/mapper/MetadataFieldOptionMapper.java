package com.oak.dms.metadata.mapper;

import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.entity.MetadataFieldOption;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MetadataFieldOptionMapper {
    MetadataFieldOptionResponse toResponse(MetadataFieldOption option);
}
