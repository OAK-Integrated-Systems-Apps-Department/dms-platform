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
    TenantResponse toResponse(Tenant tenant);
}
