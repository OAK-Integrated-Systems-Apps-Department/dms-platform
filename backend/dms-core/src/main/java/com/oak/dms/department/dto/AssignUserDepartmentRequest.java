package com.oak.dms.department.dto;

import java.util.UUID;

/** {@code departmentId} null removes the user's department. */
public record AssignUserDepartmentRequest(UUID departmentId) {
}
