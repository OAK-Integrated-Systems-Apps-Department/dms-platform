package com.oak.dms.department.dto;

import java.util.UUID;

public record UserDepartmentResponse(UUID userId, UUID departmentId, String departmentName) {
}
