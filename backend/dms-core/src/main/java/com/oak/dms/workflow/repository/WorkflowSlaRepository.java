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

    /**
     * One task due for a reminder or an escalation, with every threshold already RESOLVED in SQL - the stage's
     * own value, else the template default it inherits (3.2) - read fresh, so a just-changed default is
     * honoured without a restart and a stale L2-cached template entity can never shadow it.
     * escalationTarget is "none" when neither the stage nor the template names one.
     */
    public record DueTask(UUID taskId, UUID tenantId, UUID stageInstanceId, UUID instanceId,
                          Integer reminderBeforeHours, Integer inactivityReminderHours,
                          Integer escalateAfterReminderHours, String escalationTarget, UUID escalationUserId) {
    }

    /**
     * Unlocked candidate scan (ix_workflow_tasks_sla_due). Tasks awaiting
     * information are excluded (decision 8). The inner query resolves the effective settings:
     * <ul>
     *   <li>due-date reminder: the stage's reminder_before_hours, else the template default - but only when it is
     *   shorter than the stage's SLA (a longer one would be "already due" the moment the task is created);</li>
     *   <li>escalation target/user: a stage with a target of its own keeps it (and its user); a stage left at
     *   'none' inherits the template's default target and user;</li>
     *   <li>inactivity reminder and escalate-after: the stage's value, else the template default.</li>
     * </ul>
     * A task already past due is only ever escalated - the reminder branch needs due_at in the future.
     * Every condition is re-checked under the locks.
     */
    private static final String FIND_DUE_SQL = """
            select q.task_id, q.tenant_id, q.stage_instance_id, q.instance_id,
                   q.eff_reminder_before, q.eff_inactivity, q.eff_escalate_after, q.eff_target, q.eff_user
              from (
                select t.task_id, t.tenant_id, t.stage_instance_id, si.instance_id,
                       t.status, t.due_at, t.escalated_at, t.reminder_sent_at, t.inactivity_reminder_sent_at,
                       t.last_activity_at, t.created_at,
                       coalesce(s.reminder_before_hours,
                                case when tpl.default_reminder_before_hours < s.sla_hours
                                     then tpl.default_reminder_before_hours end) as eff_reminder_before,
                       coalesce(s.inactivity_reminder_hours, tpl.default_inactivity_reminder_hours) as eff_inactivity,
                       coalesce(s.escalate_after_reminder_hours, tpl.default_escalate_after_reminder_hours)
                           as eff_escalate_after,
                       coalesce(nullif(s.escalation_target, 'none'), nullif(tpl.default_escalation_target, 'none'),
                                'none') as eff_target,
                       case when s.escalation_target <> 'none' then s.escalation_user_id
                            else tpl.default_escalation_user_id end as eff_user
                  from workflow_tasks t
                  join workflow_stage_instances si on si.stage_instance_id = t.stage_instance_id and si.tenant_id = t.tenant_id
                  join workflow_instances i on i.instance_id = si.instance_id and i.tenant_id = si.tenant_id
                  join workflow_stages s on s.stage_id = si.stage_id and s.tenant_id = si.tenant_id
                  join workflow_templates tpl on tpl.template_id = i.template_id and tpl.tenant_id = i.tenant_id
                 where t.status in ('pending', 'claimed') and t.is_deleted = false
                   and si.status = 'active' and i.status = 'in_progress'
              ) q
             where (   -- due-date escalation
                       (q.escalated_at is null and q.eff_target <> 'none'
                        and q.due_at is not null and q.due_at <= now())
                    -- due-date reminder
                    or (q.reminder_sent_at is null and q.eff_reminder_before is not null
                        and q.due_at is not null and q.due_at > now()
                        and q.due_at - make_interval(hours => q.eff_reminder_before) <= now())
                    -- inactivity reminder (N-FR-003)
                    or (q.inactivity_reminder_sent_at is null and q.eff_inactivity is not null
                        and coalesce(q.last_activity_at, q.created_at)
                            + make_interval(hours => q.eff_inactivity) <= now())
                    -- inactivity escalation (N-FR-004), after the inactivity reminder
                    or (q.escalated_at is null and q.eff_target <> 'none'
                        and q.inactivity_reminder_sent_at is not null and q.eff_escalate_after is not null
                        and q.inactivity_reminder_sent_at + make_interval(hours => q.eff_escalate_after) <= now()))
             order by coalesce(q.due_at, q.last_activity_at, q.created_at)
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
        return jdbcTemplate.query(FIND_DUE_SQL, (rs, n) -> new DueTask(rs.getObject("task_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("stage_instance_id", UUID.class),
                rs.getObject("instance_id", UUID.class), (Integer) rs.getObject("eff_reminder_before"),
                (Integer) rs.getObject("eff_inactivity"), (Integer) rs.getObject("eff_escalate_after"),
                rs.getString("eff_target"), rs.getObject("eff_user", UUID.class)), limit);
    }

    public boolean lockInstanceSkipLocked(UUID instanceId, UUID tenantId) {
        return !jdbcTemplate.queryForList(LOCK_INSTANCE_SQL, UUID.class, instanceId, tenantId).isEmpty();
    }
}
