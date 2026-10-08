package com.oak.dms.tenant;

import com.oak.dms.tenant.dto.TenantResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct entity/DTO mapping for {@link Tenant}. Fields with no DTO
 * counterpart (createdBy, updatedBy, deleted, deletedAt) are intentionally
 * left unmapped - internal audit metadata, not part of the public API shape.
 */
@Mapper(componentModel = "spring")
public interface TenantMapper {
    @org.mapstruct.Mapping(target = "deactivated", source = "deleted")
    @org.mapstruct.Mapping(target = "deactivatedAt", source = "deletedAt")
    TenantResponse toResponse(Tenant tenant);
}
