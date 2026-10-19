package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.service.WorkflowSlaService;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N11: the inactivity reminder (N-FR-003) and the escalation that follows it
 * (N-FR-004) - each fires at most once, template defaults apply and a stage
 * value overrides them, and a task awaiting information is skipped (SLA pause).
 * The SLA scan is driven directly; thresholds are forced via the task's stamps.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowInactivityReminderIT extends WorkflowApiSupport {

    @Autowired
    private WorkflowSlaService slaService;

    private ContractWorld w;
    private User manager;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
        manager = fx.user(w.tenantId(), "Manager");
        fx.role(w.tenantId(), "Managers " + UUID.randomUUID(), WorkflowFixtures.APPROVER_PERMISSIONS, manager);
    }

    /** A one-stage template whose only approver is Nimal, escalating to Manager; no due date (inactivity only). */
    private UUID submitInactivityWorkflow() throws Exception {
        Map<String, Object> stage = stageDef("Sign-off", "any", "halt_immediately", null,
                entry("user", w.nimal().getUserId()));
        stage.put("escalationTarget", "named_user");
        stage.put("escalationUserId", manager.getUserId());
        activeTemplate(w.admin(), "INACT", w.contractTypeId(), "flag_only", List.of(stage));
        JsonNode instance = submit(w.chamath(), w.contractDocumentId(), 201);
        return id(instance, "instanceId");
    }

    private UUID taskOf(UUID instanceId) {
        return fx.jdbc().queryForObject("select t.task_id from workflow_tasks t join workflow_stage_instances si"
                + " on si.stage_instance_id = t.stage_instance_id where si.instance_id = ?", UUID.class, instanceId);
    }

    private void setTemplateDefaults(UUID instanceId, int inactivityHours, int escalateAfterHours) {
        fx.jdbc().update("update workflow_templates set default_inactivity_reminder_hours = ?,"
                + " default_escalate_after_reminder_hours = ? where template_id ="
                + " (select template_id from workflow_instances where instance_id = ?)",
                inactivityHours, escalateAfterHours, instanceId);
    }

    private long reminders(UUID instanceId) {
        return fx.count("select count(*) from notifications n where n.recipient_user_id = ?"
                + " and n.category_code = 'workflow.task.reminder'", w.nimal().getUserId());
    }

    private long stamped(UUID task, String column) {
        return fx.count("select count(*) from workflow_tasks where task_id = ? and " + column + " is not null", task);
    }

    /** The suite shares one DB, so findDue's batch may be full of other tests' due tasks - drain until ours fires. */
    private void runSlaUntil(UUID task, String column) {
        for (int i = 0; i < 40 && stamped(task, column) == 0; i++) {
            slaService.runOnce();
        }
    }

    @Test
    void inactivityReminder_firesOnce() throws Exception {
        UUID instanceId = submitInactivityWorkflow();
        UUID task = taskOf(instanceId);
        setTemplateDefaults(instanceId, 48, 24);

        // No activity for 49h -> the inactivity reminder is due.
        assertThat(fx.jdbc().update("update workflow_tasks set last_activity_at = now() - interval '49 hours'"
                + " where task_id = ?", task)).isEqualTo(1);
        runSlaUntil(task, "inactivity_reminder_sent_at");
        assertThat(stamped(task, "inactivity_reminder_sent_at")).isEqualTo(1);
        long afterFirst = reminders(instanceId);
        assertThat(afterFirst).isEqualTo(1);

        // Running again does not send a second inactivity reminder (fires once).
        slaService.runOnce();
        assertThat(reminders(instanceId)).isEqualTo(afterFirst);
    }

    @Test
    void inactivityEscalation_firesOnce_afterItsReminder() throws Exception {
        UUID instanceId = submitInactivityWorkflow();
        UUID task = taskOf(instanceId);
        setTemplateDefaults(instanceId, 48, 24);

        // Simulate the inactivity reminder already sent 25h ago (escalate_after = 24h) -> escalation is due.
        assertThat(fx.jdbc().update("update workflow_tasks set inactivity_reminder_sent_at = now() - interval '25 hours'"
                + " where task_id = ?", task)).isEqualTo(1);
        runSlaUntil(task, "escalated_at");
        assertThat(stamped(task, "escalated_at")).isEqualTo(1);
        assertThat(fx.count("select count(*) from notifications where recipient_user_id = ?"
                + " and category_code = 'workflow.task.escalated'", manager.getUserId())).isEqualTo(1);

        // No second escalation.
        slaService.runOnce();
        assertThat(stamped(task, "escalated_at")).isEqualTo(1);
    }

    @Test
    void stageValue_overridesTemplateDefault() throws Exception {
        UUID instanceId = submitInactivityWorkflow();
        UUID task = taskOf(instanceId);
        // Template says 100h; the stage overrides to 1h, so 2h of inactivity triggers.
        setTemplateDefaults(instanceId, 100, 100);
        fx.jdbc().update("update workflow_stages set inactivity_reminder_hours = 1 where stage_id ="
                + " (select s.stage_id from workflow_stage_instances si join workflow_stages s"
                + " on s.stage_id = si.stage_id where si.instance_id = ?)", instanceId);
        fx.jdbc().update("update workflow_tasks set last_activity_at = now() - interval '2 hours' where task_id = ?", task);

        runSlaUntil(task, "inactivity_reminder_sent_at");
        assertThat(stamped(task, "inactivity_reminder_sent_at")).isEqualTo(1);
    }

    @Test
    void awaitingInfoTask_isSkipped() throws Exception {
        UUID instanceId = submitInactivityWorkflow();
        UUID task = taskOf(instanceId);
        setTemplateDefaults(instanceId, 1, 1);
        // Pause the task for information and age it well past the threshold.
        fx.jdbc().update("update workflow_tasks set status = 'awaiting_info', claimed_by = ?, sla_paused_at = now(),"
                + " last_activity_at = now() - interval '100 hours' where task_id = ?", w.nimal().getUserId(), task);

        slaService.runOnce();
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and inactivity_reminder_sent_at is null",
                task)).isEqualTo(1);
    }
}
