package com.oak.dms.workflow.dto;

import com.oak.dms.workflow.enums.EscalationTarget;
import com.oak.dms.workflow.enums.OnDocumentChange;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Creates version 1 of a template, as a draft. {@code code} is stored upper-case and never changes. */
public record CreateWorkflowTemplateRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,50}", message = "2-50 letters, digits, '_' or '-'") String code,
        @Size(max = 1000) String description,
        @NotNull UUID documentTypeId,
        OnDocumentChange onDocumentChange,
        /** Template-wide reminder/escalation defaults (3.2); a stage's own value, when set, overrides them. */
        @Min(1) @Max(8759) Integer defaultReminderBeforeHours,
        @Min(1) @Max(8760) Integer defaultInactivityReminderHours,
        @Min(1) @Max(8760) Integer defaultEscalateAfterReminderHours,
        EscalationTarget defaultEscalationTarget,
        UUID defaultEscalationUserId
) {
}
