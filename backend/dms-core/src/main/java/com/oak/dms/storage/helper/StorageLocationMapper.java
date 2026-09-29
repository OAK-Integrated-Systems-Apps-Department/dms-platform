package com.oak.dms.storage.helper;

import com.oak.dms.storage.dto.StorageLocationResponse;
import com.oak.dms.storage.entity.StorageLocation;
import org.mapstruct.Mapper;

/** {@code credentialRef}/{@code configuration} have no corresponding response field - never exposed to a caller. */
@Mapper(componentModel = "spring")
public interface StorageLocationMapper {
    StorageLocationResponse toResponse(StorageLocation location);
}
