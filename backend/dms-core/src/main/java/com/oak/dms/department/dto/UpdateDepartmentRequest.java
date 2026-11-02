package com.oak.dms.department.dto;

import com.oak.dms.department.entity.Department;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Full replacement of the editable fields; {@code code} is immutable. The holding owner (V113) is the exception:
 * leaving {@code holdingOwnerUserId} out keeps it, naming a user sets it, and {@code clearHoldingOwner: true}
 * removes it - so callers that predate it can't wipe it by accident.
 */
public record UpdateDepartmentRequest(
        @NotBlank @Size(max = 150) String name,
        UUID parentDepartmentId,
        UUID headUserId,
        @NotNull Department.DepartmentStatus status,
        UUID holdingOwnerUserId,
        Boolean clearHoldingOwner
) {

    public UpdateDepartmentRequest(String name, UUID parentDepartmentId, UUID headUserId, Department.DepartmentStatus status) {
        this(name, parentDepartmentId, headUserId, status, null, null);
    }
}
