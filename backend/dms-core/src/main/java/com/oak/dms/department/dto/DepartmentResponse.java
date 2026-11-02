package com.oak.dms.department.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DepartmentResponse(
        UUID departmentId,
        String name,
        String code,
        UUID parentDepartmentId,
        String parentName,
        UUID headUserId,
        String headUserName,
        String status,
        long memberCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID holdingOwnerUserId,
        String holdingOwnerName
) {
}
