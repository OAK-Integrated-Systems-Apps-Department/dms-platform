package com.oak.dms.department.dto;

import com.oak.dms.department.entity.Department;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Full replacement of the editable fields; {@code code} is immutable. */
public record UpdateDepartmentRequest(
        @NotBlank @Size(max = 150) String name,
        UUID parentDepartmentId,
        UUID headUserId,
        @NotNull Department.DepartmentStatus status
) {
}
