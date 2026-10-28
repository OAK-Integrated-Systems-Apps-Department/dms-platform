package com.oak.dms.workflow.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The request is well-formed but the configuration it depends on makes it impossible - no active template for the document type, an approver pool with nobody eligible, an invalid stage definition. Maps to 422 with a WORKFLOW_* code; nothing is written.
 */
public class WorkflowValidationException extends ApiException {
    public WorkflowValidationException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
