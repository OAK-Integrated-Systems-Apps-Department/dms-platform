package com.oak.dms.department.dto;

import java.util.UUID;

public record DepartmentMemberResponse(UUID userId, String displayName, String email, String status) {
}
