package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MetadataFieldValidationMapper {
    MetadataFieldValidationResponse toResponse(MetadataFieldValidation validation);
}
