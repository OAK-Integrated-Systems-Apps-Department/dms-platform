package com.oak.dms.workflow.service;

import com.oak.dms.workflow.entity.WorkflowStageParticipant;
import com.oak.dms.workflow.entity.WorkflowTask;

import java.util.Set;
import java.util.UUID;

/**
 * Turns a participant entry into people (decision 2: at stage activation,
 * never when the template is written) and answers "may this user act on this
 * task right now" against LIVE membership (Q2).
 */
public interface ApproverResolver {

    /**
     * Active users of the entry, minus the initiator (segregation of duties),
     * who also hold an approve or reject permission through a role. Empty
     * means the entry has nobody eligible.
     */
    Set<UUID> resolve(UUID tenantId, WorkflowStageParticipant participant, UUID initiatorId);

    /**
     * Live pool membership only (role grant or primary department), ignoring
     * claims, delegation and escalation - see {@code TaskRouting} for who may act.
     */
    boolean isPoolMember(UUID tenantId, WorkflowTask task, UUID userId);
}
