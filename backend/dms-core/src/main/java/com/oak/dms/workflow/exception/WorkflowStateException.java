package com.oak.dms.workflow.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The workflow object isn't in a state that allows the request - e.g. the document is already in a workflow, the stage isn't active, the entry is already satisfied, or the caller already acted on this stage. Maps to 409 with a WORKFLOW_* code.
 */
public class WorkflowStateException extends ApiException {
    public WorkflowStateException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
