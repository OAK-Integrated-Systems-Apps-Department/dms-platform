package com.oak.dms.workflow.dto;

import com.oak.dms.workflow.enums.EscalationTarget;
import com.oak.dms.workflow.enums.OnDocumentChange;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Drafts only. documentTypeId can only change on version 1 - later versions keep their family's type.
 * The reminder/escalation defaults are replaced as a whole: leaving one out clears it.
 */
public record UpdateWorkflowTemplateRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 1000) String description,
        @NotNull UUID documentTypeId,
        @NotNull OnDocumentChange onDocumentChange,
        /** Template-wide reminder/escalation defaults (3.2); a stage's own value, when set, overrides them. */
        @Min(1) @Max(8759) Integer defaultReminderBeforeHours,
        @Min(1) @Max(8760) Integer defaultInactivityReminderHours,
        @Min(1) @Max(8760) Integer defaultEscalateAfterReminderHours,
        EscalationTarget defaultEscalationTarget,
        UUID defaultEscalationUserId
) {
}
