package com.oak.dms.workflow.dto;

import com.oak.dms.workflow.enums.SupportingDocumentSource;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A document attached as evidence. documentVersionId pins a version and
 * defaults to the document's latest. source = uploaded_new for a document
 * the caller has just uploaded through POST /documents (A10).
 */
public record SupportingDocumentInput(@NotNull UUID documentId, UUID documentVersionId,
                                      @NotNull SupportingDocumentSource source, @Size(max = 2000) String note) {
}
