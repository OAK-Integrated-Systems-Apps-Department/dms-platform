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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reminders and escalation (Phase 2 S4, plan §6). Time is simulated by
 * moving due_at; the scheduler is off in tests, so each run is an explicit
 * {@link WorkflowSlaService#runOnce()}.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowSlaIT extends WorkflowApiSupport {

    @Autowired
    private WorkflowSlaService slaService;
    @Autowired
    private DataSource dataSource;

    private ContractWorld w;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
        // Legal's head is Kumari; Chamath (the initiator) is in Legal, so for a
        // role/user task the initiator's department head is also Kumari (A1).
        fx.jdbc().update("update departments set head_user_id = ? where department_id = ?", w.kumari().getUserId(),
                w.legal().getDepartmentId());
        fx.jdbc().update("update users set department_id = ? where user_id = ?", w.legal().getDepartmentId(),
                w.chamath().getUserId());
    }

    private static Map<String, Object> slaStage(String name, String target, UUID namedUser,
                                                Map<String, Object>... participants) {
        Map<String, Object> stage = stageDef(name, "all", "halt_immediately", 48, participants);
        stage.put("reminderBeforeHours", 24);
        stage.put("escalationTarget", target);
        stage.put("escalationUserId", namedUser);
        return stage;
    }

    private JsonNode contractUnderSla(String target, UUID namedUser) throws Exception {
        activeTemplate(w.admin(), "SLA", w.contractTypeId(), "flag_only", List.of(
                slaStage("Department Review", target, namedUser,
                        entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId()))));
        return submit(w.chamath(), w.contractDocumentId(), 201);
    }

    private void dueIn(UUID instanceId, String interval) {
        fx.jdbc().update("update workflow_tasks set due_at = now() + cast(? as interval) where stage_instance_id in"
                + " (select stage_instance_id from workflow_stage_instances where instance_id = ?)", interval, instanceId);
    }

    private long actions(UUID instanceId, String type) {
        return fx.count("select count(*) from workflow_actions where instance_id = ? and action_type = ?", instanceId,
                type);
    }

    @Test
    void reminder_firesOnceForEveryOpenTask_atTheLeadTime() throws Exception {
        UUID instanceId = id(contractUnderSla("none", null), "instanceId");
        slaService.runOnce();
        assertThat(actions(instanceId, "reminded")).as("48 h out: nothing yet").isZero();

        dueIn(instanceId, "23 hours");
        slaService.runOnce();
        assertThat(actions(instanceId, "reminded")).isEqualTo(2);
        assertThat(fx.count("select count(*) from workflow_tasks t join workflow_stage_instances si"
                + " on si.stage_instance_id = t.stage_instance_id where si.instance_id = ? and t.reminder_sent_at is not null",
                instanceId)).isEqualTo(2);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'WORKFLOW_TASK_REMINDER_SENT'",
                w.tenantId())).isEqualTo(2);

        slaService.runOnce();
        assertThat(actions(instanceId, "reminded")).as("fires once per task").isEqualTo(2);

        // escalation_target = none: past due, nothing more happens and nothing is rescanned
        dueIn(instanceId, "-1 minute");
        slaService.runOnce();
        assertThat(actions(instanceId, "escalated")).isZero();
    }

    /** Scenario C's escalation step (the full scenario is in WorkflowScenariosIT). */
    @Test
    void escalation_addsTheDepartmentHeadAsARoute_andTheTaskStaysOpen() throws Exception {
        JsonNode submitted = contractUnderSla("department_head", null);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");
        UUID heads = taskId(submitted, 1, w.deptHead().getRoleName());

        dueIn(instanceId, "-1 minute");
        slaService.runOnce();
        assertThat(actions(instanceId, "escalated")).isEqualTo(2);
        assertThat(actions(instanceId, "reminded")).as("past due on first sight: no reminder").isZero();

        // Department pool -> that department's head (Kumari), a new route.
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and status = 'pending'"
                + " and escalated_at is not null and escalated_to = ?", legal, w.kumari().getUserId())).isEqualTo(1);
        // Role pool -> the initiator's department head, Kumari again - but she already holds that pool: unusable (A2).
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and escalated_at is not null"
                + " and escalated_to is null", heads)).isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_actions where task_id = ? and action_type = 'escalated'"
                + " and detail ->> 'skipped' = 'target_is_holder'", heads)).isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_tasks where status = 'expired'")).isZero();

        assertThat(myTasks(w.kumari(), "all")).anySatisfy(row -> {
            assertThat(row.get("taskId").asString()).isEqualTo(legal.toString());
            assertThat(row.get("route").asString()).isEqualTo("escalated");
        });
        assertThat(myTaskIds(w.saman())).contains(legal);
        assertThat(task(instance(instanceId, w.chamath()), legal).get("escalatedTo").get("userId").asString())
                .isEqualTo(w.kumari().getUserId().toString());

        slaService.runOnce();
        assertThat(actions(instanceId, "escalated")).as("fires once").isEqualTo(2);
        approve(w.kumari(), legal, 200);
        assertThat(timeline(instanceId, "approved").get("detail").get("route").asString()).isEqualTo("escalated");
    }

    @Test
    void namedUserTarget_unusableTargetsAreStampedOnce() throws Exception {
        User noPermission = fx.user(w.tenantId(), "NoPerm");
        UUID instanceId = id(contractUnderSla("named_user", noPermission.getUserId()), "instanceId");
        dueIn(instanceId, "-1 minute");
        slaService.runOnce();
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ? and action_type = 'escalated'"
                + " and detail ->> 'skipped' = 'target_lacks_permission'", instanceId)).isEqualTo(2);
        slaService.runOnce();
        assertThat(actions(instanceId, "escalated")).as("fires once").isEqualTo(2);

        // The initiator as the target, on a fresh instance.
        ContractWorld other = ContractWorld.create(fx);
        activeTemplate(other.admin(), "SLA2", other.contractTypeId(), "flag_only", List.of(
                slaStage("Review", "named_user", other.chamath().getUserId(),
                        entry("user", other.nimal().getUserId()))));
        UUID second = id(submit(other.chamath(), other.contractDocumentId(), 201), "instanceId");
        dueIn(second, "-1 minute");
        slaService.runOnce();
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ? and action_type = 'escalated'"
                + " and detail ->> 'skipped' = 'target_is_initiator'", second)).isEqualTo(1);
    }

    @Test
    void namedUser_becomesTheRoute() throws Exception {
        UUID instanceId = id(contractUnderSla("named_user", w.nimal().getUserId()), "instanceId");
        dueIn(instanceId, "-1 minute");
        slaService.runOnce();
        assertThat(fx.count("select count(*) from workflow_tasks t join workflow_stage_instances si"
                + " on si.stage_instance_id = t.stage_instance_id where si.instance_id = ? and t.escalated_to = ?",
                instanceId, w.nimal().getUserId())).isEqualTo(2);
        assertThat(myTasks(w.nimal(), "assigned")).hasSize(2);
    }

    @Test
    void tasksAwaitingInformation_andStagesWithoutSla_areSkipped() throws Exception {
        JsonNode submitted = contractUnderSla("department_head", null);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");
        fx.jdbc().update("update workflow_tasks set status = 'awaiting_info', claimed_by = ?, sla_paused_at = now()"
                + " where task_id = ?", w.saman().getUserId(), legal);
        dueIn(instanceId, "-1 minute");
        slaService.runOnce();
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and escalated_at is null", legal))
                .isEqualTo(1);
        assertThat(actions(instanceId, "escalated")).as("only the other task").isEqualTo(1);

        ContractWorld other = ContractWorld.create(fx);
        activeTemplate(other.admin(), "NOSLA", other.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "any", "halt_immediately", null, entry("user", other.nimal().getUserId()))));
        UUID noSla = id(submit(other.chamath(), other.contractDocumentId(), 201), "instanceId");
        slaService.runOnce();
        assertThat(actions(noSla, "reminded") + actions(noSla, "escalated")).isZero();
    }

    @Test
    void aBusyInstanceIsSkipped_andHandledOnTheNextRun() throws Exception {
        UUID instanceId = id(contractUnderSla("department_head", null), "instanceId");
        dueIn(instanceId, "-1 minute");
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "select instance_id from workflow_instances where instance_id = ? for update")) {
                lock.setObject(1, instanceId);
                lock.executeQuery();
            }
            long started = System.nanoTime();
            slaService.runOnce();
            assertThat((System.nanoTime() - started) / 1_000_000).as("never waits on the lock").isLessThan(4_000);
            assertThat(actions(instanceId, "escalated")).isZero();
            holder.rollback();
        }
        slaService.runOnce();
        assertThat(actions(instanceId, "escalated")).isEqualTo(2);
    }

    private JsonNode timeline(UUID instanceId, String actionType) throws Exception {
        for (JsonNode a : get200("/api/v1/workflow/instances/" + instanceId + "/actions?size=200", w.chamath())
                .get("content")) {
            if (actionType.equals(a.get("actionType").asString())) {
                return a;
            }
        }
        throw new AssertionError("No " + actionType + " action");
    }
}
