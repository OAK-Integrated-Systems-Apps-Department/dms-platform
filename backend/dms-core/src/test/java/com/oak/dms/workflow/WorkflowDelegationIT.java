package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Delegation and admin reassignment (Phase 2 S3, plan §5): the same task
 * changes holder in place, the guards of decision 3, the claimed-pool-only
 * rule (A4), re-delegation chains (A3) and unblocking a parked task.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowDelegationIT extends WorkflowApiSupport {

    private ContractWorld w;
    private User delegate1;
    private User outsider;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
        delegate1 = fx.user(w.tenantId(), "Delegate1");
        fx.role(w.tenantId(), "Delegates " + UUID.randomUUID(), WorkflowFixtures.APPROVER_PERMISSIONS, delegate1);
        outsider = fx.user(w.tenantId(), "Outsider");
    }

    private UUID nimalTemplate() throws Exception {
        return activeTemplate(w.admin(), "SIGNOFF", w.contractTypeId(), "flag_only", List.of(
                stageDef("Final Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
    }

    @Test
    void userTask_delegatedInPlace_movesQueue_andTheDelegateApproves() throws Exception {
        nimalTemplate();
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID task = taskId(submitted, 1, w.nimal().getDisplayName());

        JsonNode result = delegate(w.nimal(), task, delegate1, 200);
        assertThat(result.get("taskStatus").asString()).isEqualTo("pending");

        JsonNode view = task(instance(instanceId, w.chamath()), task);
        assertThat(view.get("assigneeId").asString()).isEqualTo(w.nimal().getUserId().toString());
        assertThat(view.get("delegatedFrom").get("userId").asString()).isEqualTo(w.nimal().getUserId().toString());
        assertThat(view.get("delegatedTo").get("userId").asString()).isEqualTo(delegate1.getUserId().toString());
        assertThat(fx.count("select count(*) from workflow_tasks where stage_instance_id ="
                + " (select stage_instance_id from workflow_tasks where task_id = ?)", task)).isEqualTo(1);

        assertThat(myTaskIds(w.nimal())).doesNotContain(task);
        JsonNode row = myTasks(delegate1, "all").get(0);
        assertThat(row.get("taskId").asString()).isEqualTo(task.toString());
        assertThat(row.get("route").asString()).isEqualTo("delegated");
        assertThat(row.get("delegatedFrom").get("userId").asString()).isEqualTo(w.nimal().getUserId().toString());
        assertThat(myTasks(delegate1, "assigned")).hasSize(1);
        assertThat(myTasks(delegate1, "available")).isEmpty();

        assertThat(approve(w.nimal(), task, 403).get("code").asString()).isEqualTo("WORKFLOW_NOT_ASSIGNEE");
        approve(delegate1, task, 200);
        JsonNode done = instance(instanceId, w.chamath());
        assertThat(done.get("status").asString()).isEqualTo("approved");
        assertThat(task(done, task).get("actedBy").get("userId").asString()).isEqualTo(delegate1.getUserId().toString());

        JsonNode approved = timelineEntry(instanceId, "approved");
        assertThat(approved.get("actor").get("userId").asString()).isEqualTo(delegate1.getUserId().toString());
        assertThat(approved.get("detail").get("route").asString()).isEqualTo("delegated");
        assertThat(approved.get("detail").get("delegatedFrom").asString()).isEqualTo(w.nimal().getUserId().toString());
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'WORKFLOW_TASK_DELEGATED'"
                + " and object_id = ?", w.tenantId(), task)).isEqualTo(1);
    }

    @Test
    void delegateGuards() throws Exception {
        activeTemplate(w.admin(), "GUARDS", w.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "all", "halt_immediately", 24,
                        entry("department", w.legal().getDepartmentId()), entry("user", w.nimal().getUserId()))));
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID nimalTask = taskId(submitted, 1, w.nimal().getDisplayName());
        UUID legalTask = taskId(submitted, 1, "Legal");

        assertThat(delegate(w.nimal(), nimalTask, w.chamath(), 422).get("code").asString())
                .isEqualTo("WORKFLOW_DELEGATE_IS_INITIATOR");
        assertThat(delegate(w.nimal(), nimalTask, w.nimal(), 422).get("code").asString())
                .isEqualTo("WORKFLOW_INVALID_DELEGATE");
        assertThat(delegate(w.nimal(), nimalTask, outsider, 422).get("code").asString())
                .isEqualTo("WORKFLOW_DELEGATE_LACKS_PERMISSION");
        assertThat(delegate(w.saman(), nimalTask, delegate1, 403).get("code").asString())
                .isEqualTo("WORKFLOW_NOT_TASK_HOLDER");

        approve(w.saman(), legalTask, 200);
        assertThat(delegate(w.nimal(), nimalTask, w.saman(), 422).get("code").asString())
                .isEqualTo("WORKFLOW_DELEGATE_ALREADY_ACTED");
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and delegated_to is not null",
                nimalTask)).isZero();

        List<UUID> candidates = new ArrayList<>();
        get200("/api/v1/workflow/tasks/" + nimalTask + "/delegate-candidates?limit=50", w.nimal())
                .forEach(c -> candidates.add(UUID.fromString(c.get("userId").asString())));
        assertThat(candidates).contains(delegate1.getUserId(), w.kumari().getUserId())
                .doesNotContain(w.nimal().getUserId(), w.chamath().getUserId(), outsider.getUserId(),
                        w.saman().getUserId());
    }

    @Test
    void poolTask_onlyAClaimedOneCanBeDelegated_byItsClaimer() throws Exception {
        activeTemplate(w.admin(), "POOL", w.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "any", "halt_immediately", 24, entry("department", w.legal().getDepartmentId()))));
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID legal = taskId(submitted, 1, "Legal");

        assertThat(delegate(w.saman(), legal, delegate1, 409).get("code").asString())
                .isEqualTo("WORKFLOW_TASK_NOT_CLAIMED");
        postAs("/api/v1/workflow/tasks/" + legal + "/claim", w.saman(), null, 200);
        assertThat(delegate(w.dilani(), legal, delegate1, 403).get("code").asString())
                .isEqualTo("WORKFLOW_NOT_TASK_HOLDER");
        delegate(w.saman(), legal, delegate1, 200);

        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and claimed_by = ? and status = 'claimed'",
                legal, w.saman().getUserId())).isEqualTo(1);
        assertThat(postAs("/api/v1/workflow/tasks/" + legal + "/release", w.saman(), null, 409).get("code").asString())
                .isEqualTo("WORKFLOW_TASK_DELEGATED");
        assertThat(approve(w.saman(), legal, 403).get("code").asString()).isEqualTo("WORKFLOW_NOT_ASSIGNEE");
        assertThat(myTaskIds(w.saman())).doesNotContain(legal);
        assertThat(myTaskIds(w.dilani())).doesNotContain(legal);
        assertThat(myTaskIds(delegate1)).containsExactly(legal);
        approve(delegate1, legal, 200);
    }

    @Test
    void reDelegation_keepsTheOriginalHolder_andHandingBackClearsIt() throws Exception {
        nimalTemplate();
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID task = taskId(submitted, 1, w.nimal().getDisplayName());

        delegate(w.nimal(), task, delegate1, 200);
        delegate(delegate1, task, w.asanka(), 200);
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and delegated_from = ?"
                + " and delegated_to = ?", task, w.nimal().getUserId(), w.asanka().getUserId())).isEqualTo(1);
        assertThat(approve(delegate1, task, 403).get("code").asString()).isEqualTo("WORKFLOW_NOT_ASSIGNEE");

        delegate(w.asanka(), task, w.nimal(), 200);
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and delegated_from is null"
                + " and delegated_to is null and delegated_at is null", task)).isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_actions where task_id = ? and action_type = 'delegated'",
                task)).isEqualTo(3);
        assertThat(myTaskIds(w.nimal())).containsExactly(task);
        approve(w.nimal(), task, 200);
    }

    @Test
    void reassign_unblocksAParkedTask_andNeedsAReasonAndThePermission() throws Exception {
        activeTemplate(w.admin(), "PARKED", w.contractTypeId(), "flag_only", List.of(
                stageDef("Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId())),
                stageDef("Heads", "any", "halt_immediately", 24, entry("role", w.deptHead().getRoleId()))));
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        // The Dept Head pool empties mid-flight, so stage 2 activates parked.
        fx.jdbc().update("delete from user_roles where role_id = ?", w.deptHead().getRoleId());
        approve(w.nimal(), taskId(submitted, 1, w.nimal().getDisplayName()), 200);
        JsonNode parked = instance(instanceId, w.chamath());
        UUID heads = taskId(parked, 2, w.deptHead().getRoleName());
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ? and action_type = 'stage_unassignable'",
                instanceId)).isEqualTo(1);

        postAs("/api/v1/workflow/tasks/" + heads + "/reassign", w.saman(),
                Map.of("userId", delegate1.getUserId(), "reason", "x"), 403);
        assertThat(adminPost("/api/v1/workflow/tasks/" + heads + "/reassign", w.admin(),
                Map.of("userId", delegate1.getUserId()), 400).get("status").asInt()).isEqualTo(400);
        reassign(w.admin(), heads, delegate1, "Dept heads are on leave", 200);

        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and delegated_from is null"
                + " and delegated_to = ?", heads, delegate1.getUserId())).isEqualTo(1);
        assertThat(timelineEntry(instanceId, "reassigned").get("reason").asString()).isEqualTo("Dept heads are on leave");
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'WORKFLOW_TASK_REASSIGNED'"
                + " and reason = 'Dept heads are on leave'", w.tenantId())).isEqualTo(1);
        approve(delegate1, heads, 200);
        assertThat(instance(instanceId, w.chamath()).get("status").asString()).isEqualTo("approved");
    }

    @Test
    void anotherTenantGets404() throws Exception {
        nimalTemplate();
        UUID task = taskId(submit(w.chamath(), w.contractDocumentId(), 201), 1, w.nimal().getDisplayName());
        ContractWorld other = ContractWorld.create(fx);
        var outsiderAdmin = fx.tenantAdminJwtFor(other.admin());
        call(post("/api/v1/workflow/tasks/" + task + "/delegate"), outsiderAdmin,
                Map.of("delegateUserId", other.kumari().getUserId()), 404);
        call(post("/api/v1/workflow/tasks/" + task + "/reassign"), outsiderAdmin,
                Map.of("userId", other.kumari().getUserId(), "reason", "x"), 404);
        call(get("/api/v1/workflow/tasks/" + task + "/delegate-candidates"), outsiderAdmin, null, 404);
        // A user of another tenant is never a valid delegate.
        assertThat(delegate(w.nimal(), task, other.kumari(), 422).get("code").asString())
                .isEqualTo("WORKFLOW_INVALID_DELEGATE");
    }

    private JsonNode timelineEntry(UUID instanceId, String actionType) throws Exception {
        for (JsonNode a : get200("/api/v1/workflow/instances/" + instanceId + "/actions?size=200", w.chamath())
                .get("content")) {
            if (actionType.equals(a.get("actionType").asString())) {
                return a;
            }
        }
        throw new AssertionError("No " + actionType + " action");
    }
}
