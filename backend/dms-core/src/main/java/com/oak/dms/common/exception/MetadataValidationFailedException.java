package com.oak.dms.common.exception;

import com.oak.dms.metadata.dto.ValidationError;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a document upload's submitted metadata values fail
 * {@link com.oak.dms.metadata.MetadataValidationService#validate} (mandatory
 * fields missing, etc). The frontend is expected to call {@code POST
 * /document-types/{id}/validate} itself for live, per-field feedback as the
 * user fills the form -- this is the server-side backstop for anyone who
 * bypasses that, so the message is a plain summary rather than the full
 * structured error list.
 */
public class MetadataValidationFailedException extends ApiException {
    public MetadataValidationFailedException(List<ValidationError> errors) {
        super(HttpStatus.BAD_REQUEST, "METADATA_VALIDATION_FAILED", errors.stream()
                .map(e -> e.fieldKey() + ": " + e.message())
                .collect(Collectors.joining("; ")));
    }
}
