package com.oak.dms.workflow.enums;

import java.util.Set;

/**
 * workflow_tasks.status (V45). The CHECK already allows the Phase 2 values
 * ({@code awaiting_info}, {@code delegated}); Phase 1 never writes them, but
 * they are declared so reading a Phase 2 row never fails.
 */
public enum TaskStatus {
    pending, claimed, awaiting_info, approved, rejected, delegated, expired, cancelled;

    /**
     * Undecided tasks that still count toward their stage. The engine selects
     * open tasks by this set only, so Phase 2 extends it in one place.
     */
    public static final Set<TaskStatus> OPEN = Set.of(pending, claimed);
}
