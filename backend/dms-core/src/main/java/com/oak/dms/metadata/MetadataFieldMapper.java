package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.MetadataFieldResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * {@code labelAliases} (the field's global OCR label hints) lives in its own
 * table, not on {@link MetadataField}, so the caller loads it - batched, for
 * list endpoints - and passes it in alongside the entity.
 */
@Mapper(componentModel = "spring")
public interface MetadataFieldMapper {
    @Mapping(target = "labelAliases", source = "labelAliases")
    MetadataFieldResponse toResponse(MetadataField field, List<String> labelAliases);
}
