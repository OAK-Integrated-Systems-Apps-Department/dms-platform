package com.oak.dms.workflow.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Plain-JDBC queries of the SLA scheduler (Phase 2 plan §6), in the style of
 * {@code ScanResultRepositoryImpl}. Joins the caller's transaction.
 */
@Repository
@RequiredArgsConstructor
public class WorkflowSlaRepository {

    /** One task due for a reminder or an escalation. */
    public record DueTask(UUID taskId, UUID tenantId, UUID stageInstanceId, UUID instanceId) {
    }

    /**
     * Unlocked candidate scan (ix_workflow_tasks_sla_due). Tasks awaiting
     * information are excluded (decision 8); a stage without SLA, reminder
     * or escalation target never matches; a task already past due is only
     * ever escalated - the reminder branch needs due_at in the future.
     * Every condition is re-checked under the locks.
     */
    private static final String FIND_DUE_SQL = """
            select t.task_id, t.tenant_id, t.stage_instance_id, si.instance_id
              from workflow_tasks t
              join workflow_stage_instances si on si.stage_instance_id = t.stage_instance_id and si.tenant_id = t.tenant_id
              join workflow_instances i on i.instance_id = si.instance_id and i.tenant_id = si.tenant_id
              join workflow_stages s on s.stage_id = si.stage_id and s.tenant_id = si.tenant_id
             where t.status in ('pending', 'claimed') and t.is_deleted = false and t.due_at is not null
               and si.status = 'active' and i.status = 'in_progress'
               and (   (t.escalated_at is null and s.escalation_target <> 'none' and t.due_at <= now())
                    or (t.reminder_sent_at is null and s.reminder_before_hours is not null
                        and t.due_at > now()
                        and t.due_at - make_interval(hours => s.reminder_before_hours) <= now()))
             order by t.due_at
             limit ?
            """;

    /**
     * Claims the instance row for this transaction, or returns false at once
     * when another app instance - or a user's approve/cancel - holds it. The
     * caller skips the task; the next run tries again (decision 7).
     */
    private static final String LOCK_INSTANCE_SQL = """
            select instance_id from workflow_instances
             where instance_id = ? and tenant_id = ? and status = 'in_progress'
             for update skip locked
            """;

    private final JdbcTemplate jdbcTemplate;

    public List<DueTask> findDue(int limit) {
        return jdbcTemplate.query(FIND_DUE_SQL, (rs, n) -> new DueTask(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)), limit);
    }

    public boolean lockInstanceSkipLocked(UUID instanceId, UUID tenantId) {
        return !jdbcTemplate.queryForList(LOCK_INSTANCE_SQL, UUID.class, instanceId, tenantId).isEmpty();
    }
}
