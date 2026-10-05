package com.oak.dms.workflow;

import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 1 end-to-end scenarios (docs/workflow-phase1-plan.md W20a),
 * driven through the real HTTP API. Contract template:
 * stage 1 "Department Review" = Legal (department) + Dept Head (role), all,
 * halt_immediately, 48h; stage 2 "Final Sign-off" = Nimal, any, 24h.
 * Scenarios C, D, I and J are Phase 2. Each test builds its own tenant.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowScenariosIT extends WorkflowApiSupport {

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
}
