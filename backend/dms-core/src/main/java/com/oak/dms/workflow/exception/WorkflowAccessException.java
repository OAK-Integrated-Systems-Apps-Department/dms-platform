package com.oak.dms.workflow.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The caller holds the endpoint's permission but may not act on this particular task or instance - not the assignee or pool member, or acting on their own submission (segregation of duties). Maps to 403 with a WORKFLOW_* code.
 */
public class WorkflowAccessException extends ApiException {
    public WorkflowAccessException(String code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }
}
