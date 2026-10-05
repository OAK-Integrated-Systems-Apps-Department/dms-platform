package com.oak.dms.workflow.dto;

import com.oak.dms.workflow.enums.OnDocumentChange;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Drafts only. documentTypeId can only change on version 1 - later versions keep their family's type. */
public record UpdateWorkflowTemplateRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 1000) String description,
        @NotNull UUID documentTypeId,
        @NotNull OnDocumentChange onDocumentChange
) {
}
