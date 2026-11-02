package com.oak.dms.ownership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Offboard a user: move everything they own (to a named user, or to the department's holding owner) and then
 * deactivate the account. {@code acknowledgedStuckTasks} must equal the number of open tasks that will stay
 * stuck, as the preview showed it.
 */
public record OffboardRequest(
        @NotBlank @Size(max = 1000) String reason,
        @NotNull String mode,
        UUID newOwnerUserId,
        UUID newOwnerDepartmentId,
        Boolean grantAccess,
        Integer acknowledgedStuckTasks,
        @Size(max = 1000) String note) {
}
