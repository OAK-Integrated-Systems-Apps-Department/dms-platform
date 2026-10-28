package com.oak.dms.department.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** {@code code} is stored upper-case and cannot be changed afterwards. */
public record CreateDepartmentRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,50}", message = "2-50 letters, digits, '_' or '-'") String code,
        UUID parentDepartmentId,
        UUID headUserId
) {
}
