package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tenant isolation, permissions, instance visibility, approver resolution
 * edge cases, claim/release, cancel, departments, the 5 s lock timeout, and
 * the after-commit task mails (W5/W10/W13a/W16a).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowAccessIT extends WorkflowApiSupport {

    @Autowired
    private DataSource dataSource;

    private ContractWorld w;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
    }

    private void contractTemplate() throws Exception {
        activeTemplate(w.admin(), "CONTRACT", w.contractTypeId(), "flag_only", List.of(
                stageDef("Department Review", "all", "halt_immediately", 48,
                        entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId())),
                stageDef("Final Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
    }

    // ------------------------------------------------------------ isolation

    @Test
    void anotherTenantSeesNothing_evenAsTenantAdmin() throws Exception {
        contractTemplate();
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID legalTask = taskId(submitted, 1, "Legal");
        UUID templateId = id(submitted, "templateId");

        ContractWorld other = ContractWorld.create(fx);
        var outsider = fx.tenantAdminJwtFor(other.admin());
        call(get("/api/v1/workflow/instances/" + instanceId), outsider, null, 404);
        call(get("/api/v1/workflow/instances/" + instanceId + "/actions"), outsider, null, 404);
        call(get("/api/v1/workflow/templates/" + templateId), outsider, null, 404);
        call(post("/api/v1/workflow/tasks/" + legalTask + "/approve"), outsider, Map.of(), 404);
        call(post("/api/v1/workflow/tasks/" + legalTask + "/claim"), outsider, null, 404);
        call(post("/api/v1/workflow/instances/" + instanceId + "/cancel"), outsider, Map.of("reason", "x"), 404);
        call(post("/api/v1/workflow/instances"), outsider, Map.of("documentId", w.contractDocumentId()), 404);
        call(get("/api/v1/documents/" + w.contractDocumentId() + "/workflow-instances"), outsider, null, 404);
        call(get("/api/v1/departments/" + w.legal().getDepartmentId()), outsider, null, 404);
        assertThat(call(get("/api/v1/workflow/templates"), outsider, null, 200).get("totalElements").asLong()).isZero();
        assertThat(call(get("/api/v1/workflow/tasks/my"), fx.jwtFor(other.saman()), null, 200)
                .get("totalElements").asLong()).isZero();
    }

    // ----------------------------------------------------------- permissions

    @Test
    void endpointsRequireTheirPermission() throws Exception {
        contractTemplate();
        User nobody = fx.user(w.tenantId(), "Nobody");
        UUID instanceId = id(submit(w.chamath(), w.contractDocumentId(), 201), "instanceId");
        UUID legalTask = taskId(instance(instanceId, w.chamath()), 1, "Legal");

        postAs("/api/v1/workflow/templates", nobody,
                Map.of("name", "x", "code", "XX", "documentTypeId", w.contractTypeId()), 403);
        call(get("/api/v1/workflow/templates"), fx.jwtFor(nobody), null, 403);
        submit(nobody, w.contractDocumentId(), 403);
        approve(nobody, legalTask, 403);
        reject(nobody, legalTask, "no", 403);
        postAs("/api/v1/workflow/tasks/" + legalTask + "/claim", nobody, null, 403);
        postAs("/api/v1/departments", nobody, Map.of("name", "X", "code", "XX"), 403);
        // Not a participant, no audit/document permission: the status is hidden.
        call(get("/api/v1/workflow/instances/" + instanceId), fx.jwtFor(nobody), null, 403);
        // My Tasks needs no permission - it is just empty.
        assertThat(myTaskIds(nobody)).isEmpty();
    }

    @Test
    void statusVisibility_participantsAndAuditViewers() throws Exception {
        contractTemplate();
        UUID instanceId = id(submit(w.chamath(), w.contractDocumentId(), 201), "instanceId");
        // Dilani can act on the open Legal pool task - she sees it, with her task id.
        JsonNode asDilani = instance(instanceId, w.dilani());
        assertThat(asDilani.get("myOpenTaskId").isNull()).isFalse();
        // Nimal is not yet a participant (stage 2 not reached) - hidden.
        call(get("/api/v1/workflow/instances/" + instanceId), fx.jwtFor(w.nimal()), null, 403);

        User auditor = fx.user(w.tenantId(), "Auditor");
        fx.role(w.tenantId(), "Auditor", new String[]{"wa.view-workflow-audit-trail.view"}, auditor);
        assertThat(instance(instanceId, auditor).get("myOpenTaskId").isNull()).isTrue();
        // The initiator never gets an actionable task in their own workflow.
        assertThat(instance(instanceId, w.chamath()).get("myOpenTaskId").isNull()).isTrue();
    }

    // ------------------------------------------------------ approver pools

    @Test
    void emptyPoolOrInitiatorAsApprover_blocksSubmissionAndWritesNothing() throws Exception {
        activeTemplate(w.admin(), "EMPTY", w.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "all", "halt_immediately", null, entry("department", w.legal().getDepartmentId())),
                stageDef("Procurement", "any", "halt_immediately", null,
                        entry("department", fx.department(w.tenantId(), "Procurement").getDepartmentId()))));
        JsonNode refused = submit(w.chamath(), w.contractDocumentId(), 422);
        assertThat(refused.get("code").asString()).isEqualTo("WORKFLOW_NO_ELIGIBLE_APPROVERS");
        assertThat(refused.get("message").asString()).contains("Procurement");
        assertThat(fx.count("select count(*) from workflow_instances where tenant_id = ?", w.tenantId())).isZero();

        ContractWorld x = ContractWorld.create(fx);
        fx.role(x.tenantId(), "Approver", WorkflowFixtures.APPROVER_PERMISSIONS, x.chamath());
        activeTemplate(x.admin(), "SELF", x.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "all", "halt_immediately", null, entry("user", x.chamath().getUserId()))));
        assertThat(submit(x.chamath(), x.contractDocumentId(), 422).get("code").asString())
                .isEqualTo("WORKFLOW_INITIATOR_IS_APPROVER");
    }

    @Test
    void poolEmptiedMidFlight_parksTheTaskAndMailsTheInitiator() throws Exception {
        activeTemplate(w.admin(), "PARK", w.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "all", "halt_immediately", null, entry("department", w.legal().getDepartmentId())),
                stageDef("Finance check", "any", "halt_immediately", null,
                        entry("department", w.finance().getDepartmentId()))));
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        // finance1 leaves Finance before stage 2 is reached.
        adminPut("/api/v1/users/" + w.finance1().getUserId() + "/department", w.admin(),
                Collections.singletonMap("departmentId", null), 200);

        approve(w.saman(), taskId(submitted, 1, "Legal"), 200);
        JsonNode parked = instance(instanceId, w.chamath());
        assertThat(stage(parked, 2).get("status").asString()).isEqualTo("active");
        assertThat(stage(parked, 2).get("tasks").get(0).get("status").asString()).isEqualTo("pending");
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ? and action_type = 'stage_unassignable'",
                instanceId)).isEqualTo(1);
        awaitMail(to(w.chamath()).and(m -> m.getSubject().startsWith("Workflow stuck")));
    }

    @Test
    void taskMailsGoToEveryPoolMemberButNeverTheInitiator() throws Exception {
        contractTemplate();
        submit(w.chamath(), w.contractDocumentId(), 201);
        for (User member : List.of(w.saman(), w.dilani(), w.ruwan(), w.kumari(), w.asanka())) {
            SimpleMailMessage m = awaitMail(to(member));
            assertThat(m.getSubject()).isEqualTo("Approval needed: Supplier contract 2026-114");
            assertThat(m.getText()).contains("Department Review");
        }
        Thread.sleep(300);
        assertThat(sentMails().stream().filter(to(w.chamath()))).isEmpty();
        assertThat(sentMails().stream().filter(to(w.nimal()))).isEmpty();
    }

    // ---------------------------------------------------- claim / release / cancel

    @Test
    void claimHidesThePoolTaskFromOthers_untilReleased() throws Exception {
        contractTemplate();
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID legalTask = taskId(submitted, 1, "Legal");

        postAs("/api/v1/workflow/tasks/" + legalTask + "/claim", w.saman(), null, 200);
        assertThat(myTaskIds(w.dilani())).doesNotContain(legalTask);
        assertThat(approve(w.dilani(), legalTask, 409).get("code").asString()).isEqualTo("WORKFLOW_TASK_ALREADY_CLAIMED");
        assertThat(postAs("/api/v1/workflow/tasks/" + legalTask + "/claim", w.dilani(), null, 409).get("code")
                .asString()).isEqualTo("WORKFLOW_TASK_ALREADY_CLAIMED");
        assertThat(postAs("/api/v1/workflow/tasks/" + legalTask + "/release", w.dilani(), null, 409).get("code")
                .asString()).isEqualTo("WORKFLOW_TASK_NOT_CLAIMED_BY_YOU");
        assertThat(get200("/api/v1/workflow/tasks/my?view=claimed", w.saman()).get("totalElements").asLong()).isEqualTo(1);

        postAs("/api/v1/workflow/tasks/" + legalTask + "/release", w.saman(), null, 200);
        assertThat(myTaskIds(w.dilani())).contains(legalTask);
        approve(w.dilani(), legalTask, 200);
        assertThat(postAs("/api/v1/workflow/tasks/" + legalTask + "/claim", w.ruwan(), null, 409).get("code").asString())
                .isEqualTo("WORKFLOW_ENTRY_SATISFIED");
    }

    @Test
    void onlyTheInitiatorOrATenantAdminCancels() throws Exception {
        contractTemplate();
        UUID instanceId = id(submit(w.chamath(), w.contractDocumentId(), 201), "instanceId");
        User otherSubmitter = fx.user(w.tenantId(), "Other");
        fx.role(w.tenantId(), "Submitter2", new String[]{WorkflowFixtures.SUBMIT_PERMISSION}, otherSubmitter);

        assertThat(postAs("/api/v1/workflow/instances/" + instanceId + "/cancel", otherSubmitter,
                Map.of("reason", "x"), 403).get("code").asString()).isEqualTo("WORKFLOW_CANCEL_FORBIDDEN");
        postAs("/api/v1/workflow/instances/" + instanceId + "/cancel", w.chamath(), Map.of(), 400);
        JsonNode cancelled = postAs("/api/v1/workflow/instances/" + instanceId + "/cancel", w.chamath(),
                Map.of("reason", "Wrong attachment"), 200);
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("outcomeReason").asString()).isEqualTo("Wrong attachment");
        assertThat(myTaskIds(w.saman())).isEmpty();
        // The document can be submitted again.
        submit(w.chamath(), w.contractDocumentId(), 201);
    }

    // ------------------------------------------------------------- departments

    @Test
    void departments_crudCycleAndDeleteGuards() throws Exception {
        UUID parent = id(adminPost("/api/v1/departments", w.admin(), Map.of("name", "Operations", "code", "ops"), 201),
                "departmentId");
        assertThat(adminPost("/api/v1/departments", w.admin(), Map.of("name", "Ops 2", "code", "OPS"), 409)
                .get("code").asString()).isEqualTo("DEPARTMENT_CODE_EXISTS");
        UUID child = id(adminPost("/api/v1/departments", w.admin(),
                Map.of("name", "Logistics", "code", "LOG", "parentDepartmentId", parent), 201), "departmentId");

        assertThat(adminPut("/api/v1/departments/" + parent, w.admin(),
                Map.of("name", "Operations", "parentDepartmentId", child, "status", "active"), 409)
                .get("code").asString()).isEqualTo("DEPARTMENT_CYCLE");
        assertThat(call(delete("/api/v1/departments/" + parent), fx.tenantAdminJwtFor(w.admin()), null, 409)
                .get("code").asString()).isEqualTo("DEPARTMENT_HAS_CHILDREN");
        assertThat(call(delete("/api/v1/departments/" + w.legal().getDepartmentId()), fx.tenantAdminJwtFor(w.admin()),
                null, 409).get("code").asString()).isEqualTo("DEPARTMENT_HAS_MEMBERS");

        adminPut("/api/v1/users/" + w.nimal().getUserId() + "/department", w.admin(), Map.of("departmentId", child), 200);
        JsonNode members = call(get("/api/v1/departments/" + child + "/members"), fx.tenantAdminJwtFor(w.admin()), null, 200);
        assertThat(members).hasSize(1);
        assertThat(call(get("/api/v1/users/" + w.nimal().getUserId() + "/department"), fx.tenantAdminJwtFor(w.admin()),
                null, 200).get("departmentName").asString()).isEqualTo("Logistics");

        // A department a template routes to can't be deleted.
        UUID empty = id(adminPost("/api/v1/departments", w.admin(), Map.of("name", "Audit", "code", "AUD"), 201),
                "departmentId");
        Map<String, Object> create = Map.of("name", "T", "code", "USESAUD", "documentTypeId", w.contractTypeId());
        UUID templateId = id(adminPost("/api/v1/workflow/templates", w.admin(), create, 201), "templateId");
        adminPost("/api/v1/workflow/templates/" + templateId + "/stages", w.admin(),
                stageDef("Audit", "all", "halt_immediately", null, entry("department", empty)), 201);
        assertThat(call(delete("/api/v1/departments/" + empty), fx.tenantAdminJwtFor(w.admin()), null, 409)
                .get("code").asString()).isEqualTo("DEPARTMENT_IN_USE");
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'DEPARTMENT_CREATED'",
                w.tenantId())).isEqualTo(3);
    }

    // ------------------------------------------------------------- locking

    /** Q8: a workflow transaction waits at most 5 s for a row lock, then answers 409 CONCURRENT_UPDATE. */
    @Test
    void lockWaitTimesOutAfterFiveSeconds() throws Exception {
        contractTemplate();
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID legalTask = taskId(submitted, 1, "Legal");

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "select instance_id from workflow_instances where instance_id = ? for update")) {
                lock.setObject(1, instanceId);
                lock.executeQuery();
            }
            long started = System.nanoTime();
            JsonNode refused = approve(w.saman(), legalTask, 409);
            long waitedMs = (System.nanoTime() - started) / 1_000_000;
            assertThat(refused.get("code").asString()).isEqualTo("CONCURRENT_UPDATE");
            assertThat(waitedMs).isBetween(4_500L, 15_000L);
            holder.rollback();
        }
        approve(w.saman(), legalTask, 200);
        Set<String> statuses = new HashSet<>();
        instance(instanceId, w.chamath()).get("stages").forEach(s -> statuses.add(s.get("status").asString()));
        assertThat(statuses).contains("active");
    }
}
