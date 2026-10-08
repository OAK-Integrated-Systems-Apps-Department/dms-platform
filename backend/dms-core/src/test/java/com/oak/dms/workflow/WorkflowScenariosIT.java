package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.service.WorkflowSlaService;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The Phase 1 end-to-end scenarios (docs/workflow-phase1-plan.md W20a),
 * driven through the real HTTP API. Contract template:
 * stage 1 "Department Review" = Legal (department) + Dept Head (role), all,
 * halt_immediately, 48h; stage 2 "Final Sign-off" = Nimal, any, 24h.
 * Phase 2 adds C (reminder and escalation), D (delegation), I (mention
 * without document access) and J (information request) - plan §12. Each
 * test builds its own tenant.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowScenariosIT extends WorkflowApiSupport {

    @Autowired
    private WorkflowSlaService slaService;

    private ContractWorld w;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
    }

    // ---------------------------------------------------------------- set-up

    private UUID template(String code, UUID documentTypeId, String onDocumentChange, List<Map<String, Object>> stages)
            throws Exception {
        return activeTemplate(w.admin(), code, documentTypeId, onDocumentChange, stages);
    }

    private UUID contractTemplate(String onDocumentChange) throws Exception {
        return template("CONTRACT", w.contractTypeId(), onDocumentChange, List.of(
                stageDef("Department Review", "all", "halt_immediately", 48,
                        entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId())),
                stageDef("Final Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
    }

    private String deptHeadName() {
        return w.deptHead().getRoleName();
    }

    private List<String> userActionTypes(UUID instanceId) throws Exception {
        List<String> types = new ArrayList<>();
        get200("/api/v1/workflow/instances/" + instanceId + "/actions?size=200", w.chamath()).get("content")
                .forEach(a -> {
                    if (!a.get("actor").isNull()) {
                        types.add(a.get("actionType").asString());
                    }
                });
        return types;
    }

    private long count(String sql, Object... args) {
        return fx.count(sql, args);
    }

    // ================================================================ A
    @Nested
    class A_HappyPath {

        @Test
        void contractIsApprovedThroughBothStages_andChamathIsMailedTheOutcome() throws Exception {
            contractTemplate("flag_only");

            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            UUID instanceId = id(submitted, "instanceId");
            assertThat(submitted.get("status").asString()).isEqualTo("in_progress");
            JsonNode s1 = stage(submitted, 1);
            assertThat(s1.get("status").asString()).isEqualTo("active");
            assertThat(s1.get("entryCount").asInt()).isEqualTo(2);
            assertThat(s1.get("requiredCount").asInt()).isEqualTo(2);
            assertThat(s1.get("tasks")).hasSize(2);
            assertThat(stage(submitted, 2).get("status").asString()).isEqualTo("not_started");
            assertThat(stage(submitted, 2).get("tasks")).isEmpty();
            UUID legalTask = taskId(submitted, 1, "Legal");
            UUID deptHeadTask = taskId(submitted, 1, deptHeadName());
            assertThat(myTaskIds(w.dilani())).contains(legalTask);
            assertThat(myTaskIds(w.ruwan())).contains(legalTask);

            // Saman: 1 of 2 - nothing advances; the Legal entry is satisfied for Dilani and Ruwan too.
            approve(w.saman(), legalTask, 200);
            JsonNode afterSaman = instance(instanceId, w.chamath());
            assertThat(stage(afterSaman, 1).get("approvedCount").asInt()).isEqualTo(1);
            assertThat(stage(afterSaman, 1).get("status").asString()).isEqualTo("active");
            assertThat(stage(afterSaman, 2).get("tasks")).isEmpty();
            assertThat(myTaskIds(w.dilani())).doesNotContain(legalTask);
            assertThat(myTaskIds(w.ruwan())).doesNotContain(legalTask);

            // Kumari: 2 of 2 - stage 1 closes, stage 2 opens with a task for Nimal.
            approve(w.kumari(), deptHeadTask, 200);
            JsonNode afterKumari = instance(instanceId, w.chamath());
            assertThat(stage(afterKumari, 1).get("status").asString()).isEqualTo("approved");
            JsonNode s2 = stage(afterKumari, 2);
            assertThat(s2.get("status").asString()).isEqualTo("active");
            assertThat(s2.get("tasks")).hasSize(1);
            UUID nimalTask = taskId(afterKumari, 2, "Nimal");
            assertThat(myTaskIds(w.nimal())).containsExactly(nimalTask);
            assertThat(myTaskIds(w.asanka())).doesNotContain(deptHeadTask);

            // SLA due dates: 48h for stage 1, 24h for stage 2.
            assertThat(count("select count(*) from workflow_stage_instances where instance_id = ? and stage_order = 1"
                    + " and due_at between activated_at + interval '47 hours 59 minutes' and activated_at + interval '48 hours 1 minute'",
                    instanceId)).isEqualTo(1);
            assertThat(count("select count(*) from workflow_stage_instances where instance_id = ? and stage_order = 2"
                    + " and due_at between activated_at + interval '23 hours 59 minutes' and activated_at + interval '24 hours 1 minute'",
                    instanceId)).isEqualTo(1);

            // Nimal approves - the instance is approved and Chamath is told.
            approve(w.nimal(), nimalTask, 200);
            JsonNode done = instance(instanceId, w.chamath());
            assertThat(done.get("status").asString()).isEqualTo("approved");
            assertThat(done.get("completedAt").isNull()).isFalse();
            SimpleMailMessage outcome = awaitMail(to(w.chamath()).and(m -> m.getSubject().contains("approved")));
            assertThat(outcome.getText()).contains("Supplier contract 2026-114");
            assertThat(sentMails().stream().filter(to(w.chamath()))).hasSize(1);

            assertThat(userActionTypes(instanceId)).containsExactly("submitted", "approved", "approved", "approved");
        }
    }

    // ================================================================ B
    @Nested
    class B_Rejection {

        @Test
        void reasonIsMandatory_andARejectionEndsTheWorkflowAtOnce() throws Exception {
            contractTemplate("flag_only");
            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            UUID instanceId = id(submitted, "instanceId");
            UUID deptHeadTask = taskId(submitted, 1, deptHeadName());
            UUID legalTask = taskId(submitted, 1, "Legal");
            long actionsBefore = count("select count(*) from workflow_actions where instance_id = ?", instanceId);

            reject(w.asanka(), deptHeadTask, null, 400);
            reject(w.asanka(), deptHeadTask, "   ", 400);
            JsonNode unchanged = instance(instanceId, w.chamath());
            assertThat(stage(unchanged, 1).get("rejectedCount").asInt()).isZero();
            assertThat(task(unchanged, deptHeadTask).get("status").asString()).isEqualTo("pending");
            assertThat(count("select count(*) from workflow_actions where instance_id = ?", instanceId))
                    .isEqualTo(actionsBefore);

            String reason = "Payment terms exceed policy";
            reject(w.asanka(), deptHeadTask, reason, 200);
            JsonNode rejected = instance(instanceId, w.chamath());
            assertThat(rejected.get("status").asString()).isEqualTo("rejected");
            assertThat(rejected.get("outcomeReason").asString()).isEqualTo(reason);
            assertThat(stage(rejected, 1).get("status").asString()).isEqualTo("rejected");
            assertThat(task(rejected, legalTask).get("status").asString()).isEqualTo("cancelled");
            assertThat(stage(rejected, 2).get("status").asString()).isEqualTo("skipped");
            assertThat(stage(rejected, 2).get("tasks")).isEmpty();
            // never activated: the entry count comes from the template stage (one entry, Nimal), not 0
            assertThat(stage(rejected, 2).get("entryCount").asInt()).isEqualTo(1);
            assertThat(myTaskIds(w.nimal())).isEmpty();

            SimpleMailMessage mail = awaitMail(to(w.chamath()).and(m -> m.getSubject().contains("rejected")));
            assertThat(mail.getText()).contains(reason);
            assertThat(sentMails().stream().filter(to(w.nimal()))).isEmpty();
            assertThat(count("select count(*) from audit_events where tenant_id = ? and action = 'WORKFLOW_REJECTED'"
                    + " and object_id = ? and reason = ?", w.tenantId(), instanceId, reason)).isEqualTo(1);
        }
    }

    // ================================================================ E
    @Nested
    class E_OutOfTurn {

        @Test
        void e1_nimalCannotActBeforeStage2IsReached() throws Exception {
            contractTemplate("flag_only");
            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            UUID instanceId = id(submitted, "instanceId");

            assertThat(myTaskIds(w.nimal())).isEmpty();
            assertThat(stage(submitted, 2).get("status").asString()).isEqualTo("not_started");
            JsonNode refused = approve(w.nimal(), taskId(submitted, 1, "Legal"), 403);
            assertThat(refused.get("code").asString()).isEqualTo("WORKFLOW_NOT_ASSIGNEE");
            approve(w.nimal(), taskId(submitted, 1, deptHeadName()), 403);

            JsonNode after = instance(instanceId, w.chamath());
            assertThat(stage(after, 1).get("approvedCount").asInt()).isZero();
        }

        @Test
        void e2_kumariCannotApproveTwice() throws Exception {
            contractTemplate("flag_only");
            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            UUID instanceId = id(submitted, "instanceId");
            UUID deptHeadTask = taskId(submitted, 1, deptHeadName());

            approve(w.kumari(), deptHeadTask, 200);
            long actions = count("select count(*) from workflow_actions where instance_id = ?", instanceId);
            JsonNode refused = approve(w.kumari(), deptHeadTask, 409);
            assertThat(refused.get("code").asString()).isEqualTo("WORKFLOW_ALREADY_ACTED_IN_STAGE");
            assertThat(stage(instance(instanceId, w.chamath()), 1).get("approvedCount").asInt()).isEqualTo(1);
            assertThat(count("select count(*) from workflow_actions where instance_id = ?", instanceId)).isEqualTo(actions);
        }

        @Test
        void e3_dilaniCannotActOnTheLegalEntrySamanAlreadySatisfied() throws Exception {
            contractTemplate("flag_only");
            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            UUID instanceId = id(submitted, "instanceId");
            UUID legalTask = taskId(submitted, 1, "Legal");

            approve(w.saman(), legalTask, 200);
            JsonNode refused = approve(w.dilani(), legalTask, 409);
            assertThat(refused.get("code").asString()).isEqualTo("WORKFLOW_ENTRY_SATISFIED");
            JsonNode after = instance(instanceId, w.chamath());
            assertThat(stage(after, 1).get("approvedCount").asInt()).isEqualTo(1);
            assertThat(task(after, legalTask).get("actedBy").get("displayName").asString()).isEqualTo("Saman");
        }
    }

    // ================================================================ F
    @Nested
    class F_DocumentEditedMidApproval {

        private UUID toStage2(UUID[] taskOut) throws Exception {
            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            UUID instanceId = id(submitted, "instanceId");
            approve(w.saman(), taskId(submitted, 1, "Legal"), 200);
            approve(w.kumari(), taskId(submitted, 1, deptHeadName()), 200);
            JsonNode atStage2 = instance(instanceId, w.chamath());
            assertThat(stage(atStage2, 2).get("status").asString()).isEqualTo("active");
            taskOut[0] = taskId(atStage2, 2, "Nimal");
            return instanceId;
        }

        @Test
        void f1_flagOnly_flagsAndTheWorkflowContinues() throws Exception {
            contractTemplate("flag_only");
            UUID[] nimalTask = new UUID[1];
            UUID instanceId = toStage2(nimalTask);
            UUID originalVersion = id(instance(instanceId, w.chamath()), "documentVersionId");

            UUID newVersion = fx.newVersion(w.tenantId(), w.contractDocumentId(), w.chamath());

            JsonNode flagged = instance(instanceId, w.chamath());
            assertThat(flagged.get("versionChangedFlag").asBoolean()).isTrue();
            assertThat(flagged.get("status").asString()).isEqualTo("in_progress");
            assertThat(id(flagged, "documentVersionId")).isEqualTo(originalVersion);
            assertThat(id(flagged, "latestDocumentVersionId")).isEqualTo(newVersion);
            assertThat(count("select count(*) from workflow_actions where instance_id = ? and action_type = 'version_changed'"
                    + " and detail ->> 'toVersionId' = ?", instanceId, newVersion.toString())).isEqualTo(1);

            approve(w.nimal(), nimalTask[0], 200);
            assertThat(instance(instanceId, w.chamath()).get("status").asString()).isEqualTo("approved");
        }

        @Test
        void f2_restart_cancelsTheOldInstanceAndStartsANewOneOnTheNewVersion() throws Exception {
            contractTemplate("restart");
            UUID[] nimalTask = new UUID[1];
            UUID oldInstance = toStage2(nimalTask);

            UUID newVersion = fx.newVersion(w.tenantId(), w.contractDocumentId(), w.chamath());

            JsonNode old = instance(oldInstance, w.chamath());
            assertThat(old.get("status").asString()).isEqualTo("cancelled");
            assertThat(old.get("outcomeReason").asString()).contains("new version");
            assertThat(task(old, nimalTask[0]).get("status").asString()).isEqualTo("cancelled");
            assertThat(stage(old, 2).get("status").asString()).isEqualTo("skipped");
            assertThat(count("select count(*) from workflow_actions where instance_id = ? and action_type = 'restarted'",
                    oldInstance)).isEqualTo(1);

            assertThat(count("select count(*) from workflow_instances where document_id = ? and status = 'in_progress'",
                    w.contractDocumentId())).isEqualTo(1);
            UUID freshId = fx.jdbc().queryForObject("select instance_id from workflow_instances where document_id = ?"
                    + " and status = 'in_progress'", UUID.class, w.contractDocumentId());
            JsonNode fresh = instance(freshId, w.chamath());
            assertThat(id(fresh, "documentVersionId")).isEqualTo(newVersion);
            assertThat(fresh.get("initiatedBy").get("displayName").asString()).isEqualTo("Chamath");
            assertThat(stage(fresh, 1).get("status").asString()).isEqualTo("active");
            assertThat(stage(fresh, 1).get("tasks")).hasSize(2);
            assertThat(myTaskIds(w.nimal())).isEmpty();
        }
    }

    // ================================================================ G
    @Nested
    class G_NoChain {

        @Test
        void invoiceWithNoActiveTemplate_isRefusedAndNothingIsWritten() throws Exception {
            contractTemplate("flag_only"); // a Contract chain exists - Invoice has none
            UUID invoice = fx.document(w.tenantId(), w.invoiceTypeId(), w.chamath(), "Invoice INV-2207");
            String[] tables = {"workflow_instances", "workflow_stage_instances", "workflow_tasks", "workflow_actions"};
            long[] before = new long[tables.length];
            for (int i = 0; i < tables.length; i++) {
                before[i] = count("select count(*) from " + tables[i] + " where tenant_id = ?", w.tenantId());
            }
            String runtimeAudit = "select count(*) from audit_events where tenant_id = ? and module = 'WA'"
                    + " and action not like 'WORKFLOW_TEMPLATE_%'";
            long auditBefore = count(runtimeAudit, w.tenantId());

            JsonNode refused = submit(w.chamath(), invoice, 422);
            assertThat(refused.get("code").asString()).isEqualTo("WORKFLOW_NO_ACTIVE_TEMPLATE");

            for (int i = 0; i < tables.length; i++) {
                assertThat(count("select count(*) from " + tables[i] + " where tenant_id = ?", w.tenantId()))
                        .as(tables[i]).isEqualTo(before[i]).isZero();
            }
            assertThat(count(runtimeAudit, w.tenantId())).isEqualTo(auditBefore).isZero();
            Thread.sleep(500);
            assertThat(sentMails()).isEmpty();
        }
    }

    // ================================================================ H
    @Nested
    class H_ParallelWithRejection {

        private UUID submitH(String rejectionRule) throws Exception {
            template("CONTRACT_H", w.contractTypeId(), "flag_only", List.of(
                    stageDef("Department Review", "all", rejectionRule, 48,
                            entry("department", w.legal().getDepartmentId()),
                            entry("department", w.finance().getDepartmentId()),
                            entry("role", w.deptHead().getRoleId())),
                    stageDef("Final Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
            return id(submit(w.chamath(), w.contractDocumentId(), 201), "instanceId");
        }

        private void legalApprovesFinanceRejects(UUID instanceId) throws Exception {
            JsonNode i = instance(instanceId, w.chamath());
            approve(w.saman(), taskId(i, 1, "Legal"), 200);
            reject(w.finance1(), taskId(i, 1, "Finance"), "Budget line not approved", 200);
        }

        @Test
        void h1_waitForAll_deptHeadStillActs_thenApprovalStillRejectsBecauseAllWereRequired() throws Exception {
            UUID instanceId = submitH("wait_for_all");
            legalApprovesFinanceRejects(instanceId);

            JsonNode open = instance(instanceId, w.chamath());
            assertThat(open.get("status").asString()).isEqualTo("in_progress");
            assertThat(stage(open, 1).get("status").asString()).isEqualTo("active");
            assertThat(stage(open, 1).get("approvedCount").asInt()).isEqualTo(1);
            assertThat(stage(open, 1).get("rejectedCount").asInt()).isEqualTo(1);
            UUID deptHeadTask = taskId(open, 1, deptHeadName());
            assertThat(task(open, deptHeadTask).get("status").asString()).isEqualTo("pending");
            assertThat(myTaskIds(w.kumari())).contains(deptHeadTask);

            approve(w.kumari(), deptHeadTask, 200);
            JsonNode done = instance(instanceId, w.chamath());
            assertThat(stage(done, 1).get("status").asString()).isEqualTo("rejected");
            assertThat(done.get("status").asString()).isEqualTo("rejected");
            assertThat(stage(done, 2).get("status").asString()).isEqualTo("skipped");
            assertThat(done.get("outcomeReason").asString()).isEqualTo("Budget line not approved");
        }

        @Test
        void h1_waitForAll_deptHeadRejectsToo_bothReasonsAreKept() throws Exception {
            UUID instanceId = submitH("wait_for_all");
            legalApprovesFinanceRejects(instanceId);
            UUID deptHeadTask = taskId(instance(instanceId, w.chamath()), 1, deptHeadName());

            reject(w.kumari(), deptHeadTask, "Vendor not on the approved list", 200);
            JsonNode done = instance(instanceId, w.chamath());
            assertThat(done.get("status").asString()).isEqualTo("rejected");
            assertThat(done.get("outcomeReason").asString())
                    .contains("Budget line not approved").contains("Vendor not on the approved list");
        }

        @Test
        void h2_haltImmediately_stopsAtOnceAndCancelsTheDeptHeadTask() throws Exception {
            UUID instanceId = submitH("halt_immediately");
            legalApprovesFinanceRejects(instanceId);

            JsonNode done = instance(instanceId, w.chamath());
            assertThat(done.get("status").asString()).isEqualTo("rejected");
            assertThat(stage(done, 1).get("status").asString()).isEqualTo("rejected");
            UUID deptHeadTask = taskId(done, 1, deptHeadName());
            assertThat(task(done, deptHeadTask).get("status").asString()).isEqualTo("cancelled");
            assertThat(myTaskIds(w.kumari())).doesNotContain(deptHeadTask);

            JsonNode refused = approve(w.kumari(), deptHeadTask, 409);
            assertThat(refused.get("code").asString()).isEqualTo("WORKFLOW_STAGE_NOT_ACTIVE");
        }
    }

    // ================================================================ Phase 2 helpers

    private static final String REQUEST_INFO = "wa.request-workflow-information.create";

    private long actionsOf(UUID instanceId, String type) {
        return count("select count(*) from workflow_actions where instance_id = ? and action_type = ?", instanceId, type);
    }

    private JsonNode timelineEntry(UUID instanceId, String type) throws Exception {
        for (JsonNode a : get200("/api/v1/workflow/instances/" + instanceId + "/actions?size=200", w.chamath())
                .get("content")) {
            if (type.equals(a.get("actionType").asString())) {
                return a;
            }
        }
        throw new AssertionError("No " + type + " action");
    }

    private static java.util.function.Predicate<SimpleMailMessage> subject(String part) {
        return m -> m.getSubject() != null && m.getSubject().contains(part);
    }

    private User approver(String name) {
        User u = fx.user(w.tenantId(), name);
        fx.role(w.tenantId(), name + " approvers " + UUID.randomUUID(), WorkflowFixtures.APPROVER_PERMISSIONS, u);
        return u;
    }

    // ================================================================ C
    /** Nobody acts in time: a 48 h SLA, a reminder 24 h before due, escalation to the department head. */
    @Nested
    class C_NobodyActsInTime {

        private UUID instanceId;
        private UUID legal;
        private UUID heads;

        @BeforeEach
        void submitUnderSla() throws Exception {
            // Legal's head is Kumari; Chamath (the initiator) is in Legal, so the
            // initiator's department head is Kumari too (A1).
            fx.jdbc().update("update departments set head_user_id = ? where department_id = ?", w.kumari().getUserId(),
                    w.legal().getDepartmentId());
            fx.jdbc().update("update users set department_id = ? where user_id = ?", w.legal().getDepartmentId(),
                    w.chamath().getUserId());
            Map<String, Object> stage = stageDef("Department Review", "all", "halt_immediately", 48,
                    entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId()));
            stage.put("reminderBeforeHours", 24);
            stage.put("escalationTarget", "department_head");
            template("CONTRACT", w.contractTypeId(), "flag_only", List.of(stage));
            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            instanceId = id(submitted, "instanceId");
            legal = taskId(submitted, 1, "Legal");
            heads = taskId(submitted, 1, deptHeadName());
        }

        private void dueIn(String interval) {
            fx.jdbc().update("update workflow_tasks set due_at = now() + cast(? as interval) where stage_instance_id in"
                    + " (select stage_instance_id from workflow_stage_instances where instance_id = ?)", interval, instanceId);
        }

        private void remindThenEscalate() throws Exception {
            dueIn("23 hours");
            slaService.runOnce();
            assertThat(actionsOf(instanceId, "reminded")).isEqualTo(2);
            assertThat(count("select count(*) from workflow_tasks where task_id in (?, ?) and reminder_sent_at is not null",
                    legal, heads)).isEqualTo(2);
            // Everyone holding a task: the unclaimed Legal pool (A6) and the Dept Head pool.
            for (User holder : List.of(w.saman(), w.dilani(), w.ruwan(), w.kumari(), w.asanka())) {
                awaitMail(to(holder).and(subject("Reminder")));
            }
            slaService.runOnce();
            assertThat(actionsOf(instanceId, "reminded")).as("fires once").isEqualTo(2);

            dueIn("-1 minute");
            slaService.runOnce();
            // Legal (a department pool) -> that department's head, as an added route; the task stays open.
            assertThat(count("select count(*) from workflow_tasks where task_id = ? and status = 'pending'"
                    + " and escalated_at is not null and escalated_to = ?", legal, w.kumari().getUserId())).isEqualTo(1);
            // Dept Head (a role) -> the initiator's department head, Kumari, who already holds that pool (A2).
            assertThat(count("select count(*) from workflow_tasks where task_id = ? and escalated_at is not null"
                    + " and escalated_to is null", heads)).isEqualTo(1);
            assertThat(count("select count(*) from workflow_actions where task_id = ? and action_type = 'escalated'"
                    + " and detail ->> 'skipped' = 'target_is_holder'", heads)).isEqualTo(1);
            assertThat(actionsOf(instanceId, "escalated")).isEqualTo(2);
            assertThat(count("select count(*) from workflow_tasks where status = 'expired'")).isZero();
            assertThat(myTasks(w.kumari(), "all")).anySatisfy(row -> {
                assertThat(row.get("taskId").asString()).isEqualTo(legal.toString());
                assertThat(row.get("route").asString()).isEqualTo("escalated");
            });
            assertThat(myTaskIds(w.saman())).as("the original holders keep theirs").contains(legal);
            awaitMail(to(w.kumari()).and(subject("Escalated")));
            assertThat(count("select count(*) from audit_events where tenant_id = ? and action in"
                    + " ('WORKFLOW_TASK_REMINDER_SENT', 'WORKFLOW_TASK_ESCALATED')", w.tenantId())).isEqualTo(4);
        }

        @Test
        void c1_theEscalationTargetApproves() throws Exception {
            remindThenEscalate();
            approve(w.kumari(), legal, 200);
            assertThat(timelineEntry(instanceId, "approved").get("detail").get("route").asString()).isEqualTo("escalated");
            assertThat(approve(w.saman(), legal, 409).get("code").asString()).isEqualTo("WORKFLOW_ENTRY_SATISFIED");
        }

        @Test
        void c2_anOriginalHolderApproves() throws Exception {
            remindThenEscalate();
            approve(w.saman(), legal, 200);
            assertThat(approve(w.kumari(), legal, 409).get("code").asString()).isEqualTo("WORKFLOW_ENTRY_SATISFIED");
        }
    }

    // ================================================================ D
    /** Delegation: the CFO delegates their stage task before leaving. */
    @Nested
    class D_Delegation {

        private UUID instanceId;
        private UUID cfoTask;
        private User delegate1;

        @BeforeEach
        void reachTheCfo() throws Exception {
            delegate1 = approver("Delegate1");
            contractTemplate("flag_only");
            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            instanceId = id(submitted, "instanceId");
            approve(w.saman(), taskId(submitted, 1, "Legal"), 200);
            approve(w.kumari(), taskId(submitted, 1, deptHeadName()), 200);
            cfoTask = taskId(instance(instanceId, w.chamath()), 2, w.nimal().getDisplayName());
        }

        @Test
        void theDelegateApproves_andTheTimelineSaysDelegatedFromTheCfo() throws Exception {
            assertThat(delegate(w.nimal(), cfoTask, w.chamath(), 422).get("code").asString())
                    .isEqualTo("WORKFLOW_DELEGATE_IS_INITIATOR");
            delegate(w.nimal(), cfoTask, delegate1, 200);

            assertThat(count("select count(*) from workflow_tasks where task_id = ? and status = 'pending'"
                    + " and assignee_id = ? and delegated_from = ? and delegated_to = ?", cfoTask, w.nimal().getUserId(),
                    w.nimal().getUserId(), delegate1.getUserId())).isEqualTo(1);
            assertThat(myTaskIds(w.nimal())).doesNotContain(cfoTask);
            assertThat(myTasks(delegate1, "all").get(0).get("route").asString()).isEqualTo("delegated");
            awaitMail(to(delegate1).and(subject("Approval needed")));
            awaitMail(to(w.nimal()).and(subject("Task delegated")));

            assertThat(approve(w.nimal(), cfoTask, 403).get("code").asString()).isEqualTo("WORKFLOW_NOT_ASSIGNEE");
            approve(delegate1, cfoTask, 200);

            JsonNode done = instance(instanceId, w.chamath());
            assertThat(done.get("status").asString()).isEqualTo("approved");
            JsonNode task = task(done, cfoTask);
            assertThat(task.get("actedBy").get("userId").asString()).isEqualTo(delegate1.getUserId().toString());
            assertThat(task.get("delegatedFrom").get("userId").asString()).isEqualTo(w.nimal().getUserId().toString());
            JsonNode approved = null;
            for (JsonNode a : get200("/api/v1/workflow/instances/" + instanceId + "/actions?size=200", w.chamath())
                    .get("content")) {
                if ("approved".equals(a.get("actionType").asString()) && cfoTask.toString().equals(a.get("taskId").asString())) {
                    approved = a;
                }
            }
            assertThat(approved).isNotNull();
            assertThat(approved.get("actor").get("userId").asString()).isEqualTo(delegate1.getUserId().toString());
            assertThat(approved.get("detail").get("delegatedFrom").asString()).isEqualTo(w.nimal().getUserId().toString());
            assertThat(approved.get("detail").get("delegatedFromName").asString()).isEqualTo(w.nimal().getDisplayName());
        }

        @Test
        void reDelegationKeepsTheCfoAsTheOriginal_andHandingBackClearsIt() throws Exception {
            delegate(w.nimal(), cfoTask, delegate1, 200);
            delegate(delegate1, cfoTask, w.asanka(), 200);
            assertThat(count("select count(*) from workflow_tasks where task_id = ? and delegated_from = ?"
                    + " and delegated_to = ?", cfoTask, w.nimal().getUserId(), w.asanka().getUserId())).isEqualTo(1);
            delegate(w.asanka(), cfoTask, w.nimal(), 200);
            assertThat(count("select count(*) from workflow_tasks where task_id = ? and delegated_to is null",
                    cfoTask)).isEqualTo(1);
            approve(w.nimal(), cfoTask, 200);
        }
    }

    // ================================================================ I
    /** A user without docm.documents.view is mentioned in a comment. */
    @Nested
    class I_MentionWithoutAccess {

        @Test
        void theyAreNotifiedAndReadTheComment_butCantOpenTheDocument() throws Exception {
            fx.role(w.tenantId(), "Commenters " + UUID.randomUUID(),
                    new String[]{"docm.documents.view", "docm.document-comments.create"}, w.chamath());
            fx.role(w.tenantId(), "Readers " + UUID.randomUUID(), new String[]{"docm.documents.view"}, w.saman());
            User outsider = fx.user(w.tenantId(), "Outsider");
            String body = "Please check @[Outsider](user:" + outsider.getUserId() + ") and @[Saman](user:"
                    + w.saman().getUserId() + ")";
            UUID comment = id(postAs("/api/v1/documents/" + w.contractDocumentId() + "/comments", w.chamath(),
                    Map.of("body", body), 201), "commentId");

            assertThat(count("select count(*) from comment_mentions where comment_id = ? and mentioned_user_id = ?"
                    + " and had_document_access = false", comment, outsider.getUserId())).isEqualTo(1);
            SimpleMailMessage mail = awaitMail(to(outsider).and(subject("mentioned you")));
            assertThat(mail.getText()).contains("Please check @Outsider").doesNotContain("/documents/");

            JsonNode mention = get200("/api/v1/mentions/my", outsider).get("content").get(0);
            assertThat(mention.get("canOpenDocument").asBoolean()).isFalse();
            assertThat(mention.get("comment").get("body").asString()).isEqualTo(body);
            assertThat(get200("/api/v1/comments/" + comment, outsider).get("root").get("commentId").asString())
                    .isEqualTo(comment.toString());
            call(get("/api/v1/documents/" + w.contractDocumentId()), fx.jwtFor(outsider), null, 403);
            call(get("/api/v1/documents/" + w.contractDocumentId() + "/comments"), fx.jwtFor(outsider), null, 403);

            // Control: Saman can open the document - recorded true, and his mail links to it.
            assertThat(count("select count(*) from comment_mentions where comment_id = ? and mentioned_user_id = ?"
                    + " and had_document_access = true", comment, w.saman().getUserId())).isEqualTo(1);
            assertThat(awaitMail(to(w.saman()).and(subject("mentioned you"))).getText())
                    .contains("/documents/" + w.contractDocumentId());
        }
    }

    // ================================================================ J
    /** An approver in a parallel stage asks the initiator for another document. */
    @Nested
    class J_InformationRequest {

        private UUID instanceId;
        private UUID legal;
        private UUID heads;
        private UUID annex;

        private void submitParallel(String completion, boolean allowInfoRequests) throws Exception {
            fx.role(w.tenantId(), "Requesters " + UUID.randomUUID(), new String[]{REQUEST_INFO}, w.saman());
            Map<String, Object> stage = stageDef("Department Review", completion, "halt_immediately", 48,
                    entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId()));
            stage.put("allowInfoRequests", allowInfoRequests);
            template("CONTRACT", w.contractTypeId(), "flag_only", List.of(stage));
            JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
            instanceId = id(submitted, "instanceId");
            legal = taskId(submitted, 1, "Legal");
            heads = taskId(submitted, 1, deptHeadName());
            annex = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Signed annex B");
        }

        private UUID ask(User from, User to, int expected) throws Exception {
            JsonNode r = postAs("/api/v1/workflow/tasks/" + legal + "/info-requests", from,
                    Map.of("requestedFromUserId", to.getUserId(), "message", "Please attach the signed annex"), expected);
            return expected == 201 ? id(r, "infoRequestId") : null;
        }

        private JsonNode answer(User as, UUID request, boolean withAnnex, int expected) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "Attached");
            if (withAnnex) {
                body.put("supportingDocuments", List.of(Map.of("documentId", annex, "source", "linked_existing")));
            }
            return postAs("/api/v1/workflow/info-requests/" + request + "/respond", as, body, expected);
        }

        private String statusOf(UUID task) {
            return fx.jdbc().queryForObject("select status from workflow_tasks where task_id = ?", String.class, task);
        }

        @Test
        void theTaskPauses_theOtherApproverActs_andTheDueTimeMovesForward() throws Exception {
            submitParallel("all", true);
            UUID request = ask(w.saman(), w.chamath(), 201);
            assertThat(statusOf(legal)).isEqualTo("awaiting_info");
            awaitMail(to(w.chamath()).and(subject("Information requested")));

            assertThat(approve(w.saman(), legal, 409).get("code").asString()).isEqualTo("WORKFLOW_TASK_AWAITING_INFO");
            approve(w.kumari(), heads, 200);
            assertThat(stage(instance(instanceId, w.chamath()), 1).get("status").asString()).isEqualTo("active");

            OffsetDateTime dueBefore = fx.jdbc().queryForObject("select due_at from workflow_tasks where task_id = ?",
                    OffsetDateTime.class, legal);
            fx.jdbc().update("update workflow_tasks set sla_paused_at = sla_paused_at - interval '2 hours' where task_id = ?",
                    legal);
            JsonNode answered = answer(w.chamath(), request, true, 200);
            assertThat(answered.get("supportingDocuments").get(0).get("documentId").asString()).isEqualTo(annex.toString());
            assertThat(answered.get("supportingDocuments").get(0).get("source").asString()).isEqualTo("linked_existing");
            awaitMail(to(w.saman()).and(subject("Information provided")));
            assertThat(statusOf(legal)).isEqualTo("claimed");
            Map<String, Object> row = fx.jdbc().queryForMap(
                    "select due_at, sla_paused_seconds from workflow_tasks where task_id = ?", legal);
            int paused = (Integer) row.get("sla_paused_seconds");
            assertThat(paused).isBetween(7200, 7260);
            OffsetDateTime dueAfter = fx.jdbc().queryForObject("select due_at from workflow_tasks where task_id = ?",
                    OffsetDateTime.class, legal);
            assertThat(Duration.between(dueBefore, dueAfter).getSeconds()).isEqualTo((long) paused);

            approve(w.saman(), legal, 200);
            assertThat(instance(instanceId, w.chamath()).get("status").asString()).isEqualTo("approved");
        }

        @Test
        void a_declineWithAReasonResumesTheTask() throws Exception {
            submitParallel("all", true);
            UUID request = ask(w.saman(), w.chamath(), 201);
            postAs("/api/v1/workflow/info-requests/" + request + "/decline", w.chamath(), Map.of(), 400);
            postAs("/api/v1/workflow/info-requests/" + request + "/decline", w.chamath(),
                    Map.of("reason", "The annex isn't signed yet"), 200);
            assertThat(statusOf(legal)).isEqualTo("claimed");
            assertThat(awaitMail(to(w.saman()).and(subject("Information declined"))).getText())
                    .contains("The annex isn't signed yet");
        }

        @Test
        void b_aSenderWithoutDocumentAccessCantAttach_butCanAnswerWithANote() throws Exception {
            submitParallel("all", true);
            UUID request = ask(w.saman(), w.ruwan(), 201);   // Ruwan has no docm.documents.view
            assertThat(answer(w.ruwan(), request, true, 403).get("code").asString())
                    .isEqualTo("WORKFLOW_DOCUMENT_ACCESS_REQUIRED");
            answer(w.ruwan(), request, false, 200);
            assertThat(statusOf(legal)).isEqualTo("claimed");
        }

        @Test
        void d_twoRequestsOnOneTask_resumeOnlyAfterBoth() throws Exception {
            submitParallel("all", true);
            UUID first = ask(w.saman(), w.chamath(), 201);
            UUID second = ask(w.saman(), w.ruwan(), 201);
            answer(w.chamath(), first, true, 200);
            assertThat(statusOf(legal)).isEqualTo("awaiting_info");
            approve(w.saman(), legal, 409);
            answer(w.ruwan(), second, false, 200);
            assertThat(statusOf(legal)).isEqualTo("claimed");
        }

        @Test
        void e_theStageClosingFirst_cancelsTheRequest() throws Exception {
            submitParallel("any", true);
            UUID request = ask(w.saman(), w.chamath(), 201);
            approve(w.kumari(), heads, 200);
            assertThat(statusOf(legal)).isEqualTo("cancelled");
            assertThat(fx.jdbc().queryForObject("select cancel_reason from workflow_info_requests where info_request_id = ?",
                    String.class, request)).isEqualTo("The stage was approved without this approver");
            assertThat(timelineEntry(instanceId, "info_cancelled").get("detail").get("automatic").asBoolean()).isTrue();
            awaitMail(to(w.chamath()).and(subject("Information request closed")));
        }

        @Test
        void f_switchedOffOnTheStage() throws Exception {
            submitParallel("all", false);
            assertThat(postAs("/api/v1/workflow/tasks/" + legal + "/info-requests", w.saman(),
                    Map.of("requestedFromUserId", w.chamath().getUserId(), "message", "?"), 422).get("code").asString())
                    .isEqualTo("WORKFLOW_INFO_REQUESTS_DISABLED");
        }
    }
}
