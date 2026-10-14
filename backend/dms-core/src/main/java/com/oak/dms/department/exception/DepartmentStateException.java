package com.oak.dms.department.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A department change that conflicts with the current state - a duplicate
 * code, a parent cycle, or deleting a department that still has members,
 * children, or a workflow stage routing to it. Maps to 409.
 */
public class DepartmentStateException extends ApiException {
    public DepartmentStateException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
