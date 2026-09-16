package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MetadataFieldOptionMapper {
    MetadataFieldOptionResponse toResponse(MetadataFieldOption option);
}
