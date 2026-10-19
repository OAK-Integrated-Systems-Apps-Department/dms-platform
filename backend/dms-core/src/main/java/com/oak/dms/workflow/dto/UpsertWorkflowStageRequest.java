package com.oak.dms.workflow.dto;

import com.oak.dms.workflow.enums.CompletionRule;
import com.oak.dms.workflow.enums.EscalationTarget;
import com.oak.dms.workflow.enums.RejectionRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Create or replace one stage of a draft template. The participant list is
 * replaced as a whole. {@code reminderBeforeHours} is the due-date reminder; the inactivity ones are the
 * inactivity rule (3.2). A null reminder/escalation value inherits the template's default.
 */
public record UpsertWorkflowStageRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull CompletionRule completionRule,
        @NotNull RejectionRule rejectionRule,
        @Min(1) @Max(8760) Integer slaHours,
        @Min(1) @Max(8759) Integer reminderBeforeHours,
        /** Inactivity reminder / escalate-after overrides (3.2); null = inherit the template default. */
        @Min(1) @Max(8760) Integer inactivityReminderHours,
        @Min(1) @Max(8760) Integer escalateAfterReminderHours,
        EscalationTarget escalationTarget,
        UUID escalationUserId,
        /** Whether approvers may pause their task to request information; null means true (V47 default). */
        Boolean allowInfoRequests,
        @NotEmpty @Size(max = 50) List<@Valid ParticipantRequest> participants
) {
}
