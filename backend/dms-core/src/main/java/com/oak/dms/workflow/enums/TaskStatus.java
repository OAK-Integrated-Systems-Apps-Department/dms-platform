package com.oak.dms.workflow.enums;

import java.util.Set;

/**
 * workflow_tasks.status (V45). {@code delegated} and {@code expired} are
 * allowed by the CHECK but never written: delegation keeps the task open in
 * place and escalation adds a route instead of expiring it (Phase 2
 * decisions 1 and 6). They are declared so reading any row never fails.
 */
public enum TaskStatus {
    pending, claimed, awaiting_info, approved, rejected, delegated, expired, cancelled;

    /**
     * Undecided tasks that still count toward their stage (decision 20). The
     * engine selects open tasks by this set only - locking, cancelling and
     * My Tasks all use it.
     */
    public static final Set<TaskStatus> OPEN = Set.of(pending, claimed, awaiting_info);

    /** Open tasks someone holds: claimed, or paused by its claimer's information request. */
    public static final Set<TaskStatus> HELD = Set.of(claimed, awaiting_info);

    /** Open tasks a decision can be recorded on; a task awaiting information can't be decided. */
    public static final Set<TaskStatus> DECIDABLE = Set.of(pending, claimed);

    public static final Set<TaskStatus> DECIDED = Set.of(approved, rejected);
}
