package com.oak.dms.workflow.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A malformed workflow request the DTO validation can't express on its
 * own - e.g. a blank rejection reason reaching the service. Maps to 400.
 */
public class WorkflowRequestException extends ApiException {
    public WorkflowRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
