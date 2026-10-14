package com.oak.dms.workflow.service;

/**
 * Reminders and escalation driven by a stage's SLA (Phase 2 plan §6).
 * {@link #runOnce()} is what the scheduler calls; tests call it directly.
 */
public interface WorkflowSlaService {

    /** Handles up to one batch of due tasks; returns how many reminders and escalations were recorded. */
    int runOnce();
}
