package com.oak.dms.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Answer a request with a note, supporting documents, or both (at least one). */
public record RespondInfoRequest(@Size(max = 2000) String message,
                                 @Size(max = 10) List<@Valid SupportingDocumentInput> supportingDocuments) {
}
