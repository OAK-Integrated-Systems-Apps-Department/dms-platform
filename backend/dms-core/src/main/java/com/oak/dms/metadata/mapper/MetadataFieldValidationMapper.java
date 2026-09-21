package com.oak.dms.metadata.mapper;

import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import com.oak.dms.metadata.entity.MetadataFieldValidation;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MetadataFieldValidationMapper {
    MetadataFieldValidationResponse toResponse(MetadataFieldValidation validation);
}
