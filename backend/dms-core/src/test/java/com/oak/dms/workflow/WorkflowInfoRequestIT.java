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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Information requests (Phase 2 S7, decisions 11-16, plan §3/§7): pause and
 * resume, the decision block, auto-cancel, the per-stage switch and the
 * guards. Scenario J end to end is in WorkflowScenariosIT.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowInfoRequestIT extends WorkflowApiSupport {

    static final String REQUEST_INFO = "wa.request-workflow-information.create";

    private ContractWorld w;
    private User outsider;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
        fx.role(w.tenantId(), "Requesters " + UUID.randomUUID(), new String[]{REQUEST_INFO},
                w.saman(), w.dilani(), w.kumari(), w.asanka(), w.nimal());
        outsider = fx.user(w.tenantId(), "Outsider");
    }

    private JsonNode parallel(String completion, Boolean allowInfoRequests) throws Exception {
        Map<String, Object> stage = stageDef("Department Review", completion, "halt_immediately", 48,
                entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId()));
        if (allowInfoRequests != null) {
            stage.put("allowInfoRequests", allowInfoRequests);
        }
        activeTemplate(w.admin(), "INFO", w.contractTypeId(), "flag_only", List.of(stage));
        return submit(w.chamath(), w.contractDocumentId(), 201);
    }

    JsonNode raise(User as, UUID taskId, User to, String message, int expected) throws Exception {
        return postAs("/api/v1/workflow/tasks/" + taskId + "/info-requests", as,
                Map.of("requestedFromUserId", to.getUserId(), "message", message), expected);
    }

    JsonNode respond(User as, UUID requestId, String message, List<Map<String, Object>> documents, int expected)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("supportingDocuments", documents);
        return postAs("/api/v1/workflow/info-requests/" + requestId + "/respond", as, body, expected);
    }

    private String taskStatus(UUID taskId) {
        return fx.jdbc().queryForObject("select status from workflow_tasks where task_id = ?", String.class, taskId);
    }

    @Test
    void raise_pausesTheTask_blocksTheDecision_andOthersCarryOn() throws Exception {
        JsonNode submitted = parallel("all", null);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");
        UUID heads = taskId(submitted, 1, w.deptHead().getRoleName());

        JsonNode request = raise(w.saman(), legal, w.chamath(), "Please send the signed annex", 201);
        assertThat(request.get("status").asString()).isEqualTo("open");
        assertThat(taskStatus(legal)).isEqualTo("awaiting_info");
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and claimed_by = ?"
                + " and sla_paused_at is not null", legal, w.saman().getUserId())).isEqualTo(1);

        assertThat(approve(w.saman(), legal, 409).get("code").asString()).isEqualTo("WORKFLOW_TASK_AWAITING_INFO");
        // Nobody decides a paused entry (plan §4 checks it before routing); the other entry carries on (decision 12).
        assertThat(approve(w.dilani(), legal, 409).get("code").asString()).isEqualTo("WORKFLOW_TASK_AWAITING_INFO");
        postAs("/api/v1/workflow/tasks/" + legal + "/claim", w.dilani(), null, 409);
        approve(w.kumari(), heads, 200);
        assertThat(stage(instance(instanceId, w.chamath()), 1).get("status").asString()).isEqualTo("active");

        assertThat(myTasks(w.saman(), "waiting")).hasSize(1);
        assertThat(myTasks(w.saman(), "all").get(0).get("overdue").asBoolean()).isFalse();
        assertThat(get200("/api/v1/workflow/info-requests/my", w.chamath()).get("content")).hasSize(1);
        assertThat(delegate(w.saman(), legal, w.dilani(), 409).get("code").asString())
                .isEqualTo("WORKFLOW_TASK_AWAITING_INFO");
        assertThat(postAs("/api/v1/workflow/tasks/" + legal + "/release", w.saman(), null, 409).get("code").asString())
                .isEqualTo("WORKFLOW_TASK_AWAITING_INFO");
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'WORKFLOW_INFO_REQUESTED'",
                w.tenantId())).isEqualTo(1);
    }

    /** Plan §7: overlapping requests pause once; the summed paused time moves due_at forward exactly. */
    @Test
    void twoRequests_resumeOnlyWhenTheLastCloses_andDueAtMovesByThePausedTime() throws Exception {
        JsonNode submitted = parallel("all", null);
        UUID legal = taskId(submitted, 1, "Legal");
        UUID r1 = id(raise(w.saman(), legal, w.chamath(), "Annex A", 201), "infoRequestId");
        UUID r2 = id(raise(w.saman(), legal, w.ruwan(), "Annex B", 201), "infoRequestId");
        OffsetDateTime dueBefore = fx.jdbc().queryForObject("select due_at from workflow_tasks where task_id = ?",
                OffsetDateTime.class, legal);
        // Simulate a 3 hour pause.
        fx.jdbc().update("update workflow_tasks set sla_paused_at = sla_paused_at - interval '3 hours' where task_id = ?",
                legal);

        respond(w.chamath(), r1, "Attached in the DMS", null, 200);
        assertThat(taskStatus(legal)).as("one request still open").isEqualTo("awaiting_info");
        approve(w.saman(), legal, 409);

        JsonNode answered = respond(w.ruwan(), r2, "Here it is", null, 200);
        assertThat(answered.get("status").asString()).isEqualTo("answered");
        assertThat(taskStatus(legal)).isEqualTo("claimed");
        Map<String, Object> row = fx.jdbc().queryForMap(
                "select due_at, sla_paused_at, sla_paused_seconds from workflow_tasks where task_id = ?", legal);
        assertThat(row.get("sla_paused_at")).isNull();
        int paused = (Integer) row.get("sla_paused_seconds");
        assertThat(paused).isBetween(3 * 3600, 3 * 3600 + 60);
        OffsetDateTime dueAfter = fx.jdbc().queryForObject("select due_at from workflow_tasks where task_id = ?",
                OffsetDateTime.class, legal);
        assertThat(Duration.between(dueBefore, dueAfter).getSeconds()).isCloseTo(paused, within(1L));
        assertThat(fx.count("select count(*) from workflow_actions where task_id = ? and action_type = 'info_provided'"
                + " and detail ->> 'taskResumed' = 'true'", legal)).isEqualTo(1);
        approve(w.saman(), legal, 200);
    }

    @Test
    void decline_needsAReason_andOnlyThePersonAskedMayAnswer() throws Exception {
        UUID legal = taskId(parallel("all", null), 1, "Legal");
        UUID request = id(raise(w.saman(), legal, w.chamath(), "Budget approval?", 201), "infoRequestId");

        postAs("/api/v1/workflow/info-requests/" + request + "/decline", w.chamath(), Map.of(), 400);
        assertThat(postAs("/api/v1/workflow/info-requests/" + request + "/decline", w.dilani(),
                Map.of("reason", "x"), 403).get("code").asString()).isEqualTo("WORKFLOW_NOT_REQUESTED_PERSON");
        assertThat(respond(w.chamath(), request, null, null, 422).get("code").asString())
                .isEqualTo("WORKFLOW_INFO_RESPONSE_EMPTY");
        JsonNode declined = postAs("/api/v1/workflow/info-requests/" + request + "/decline", w.chamath(),
                Map.of("reason", "Not my area"), 200);
        assertThat(declined.get("declineReason").asString()).isEqualTo("Not my area");
        assertThat(taskStatus(legal)).isEqualTo("claimed");
        assertThat(respond(w.chamath(), request, "late", null, 409).get("code").asString())
                .isEqualTo("WORKFLOW_INFO_REQUEST_CLOSED");
    }

    @Test
    void withdraw_andReassign() throws Exception {
        UUID legal = taskId(parallel("all", null), 1, "Legal");
        UUID request = id(raise(w.saman(), legal, w.ruwan(), "Prior contract?", 201), "infoRequestId");

        assertThat(postAs("/api/v1/workflow/info-requests/" + request + "/reassign", w.saman(),
                Map.of("requestedFromUserId", w.dilani().getUserId()), 403).get("code").asString())
                .isEqualTo("WORKFLOW_INFO_REASSIGN_FORBIDDEN");
        JsonNode moved = postAs("/api/v1/workflow/info-requests/" + request + "/reassign", w.chamath(),
                Map.of("requestedFromUserId", w.dilani().getUserId()), 200);
        assertThat(moved.get("requestedFrom").get("userId").asString()).isEqualTo(w.dilani().getUserId().toString());
        assertThat(taskStatus(legal)).as("the task stays paused").isEqualTo("awaiting_info");
        respond(w.ruwan(), request, "x", null, 403);

        assertThat(postAs("/api/v1/workflow/info-requests/" + request + "/withdraw", w.dilani(), null, 403)
                .get("code").asString()).isEqualTo("WORKFLOW_NOT_REQUESTER");
        JsonNode withdrawn = postAs("/api/v1/workflow/info-requests/" + request + "/withdraw", w.saman(), null, 200);
        assertThat(withdrawn.get("status").asString()).isEqualTo("cancelled");
        assertThat(withdrawn.get("cancelReason").asString()).isEqualTo("Withdrawn by the requester");
        assertThat(taskStatus(legal)).isEqualTo("claimed");
    }

    @Test
    void theStageClosingFirst_cancelsTheOpenRequest() throws Exception {
        JsonNode submitted = parallel("any", null);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");
        UUID request = id(raise(w.saman(), legal, w.chamath(), "Signed annex?", 201), "infoRequestId");

        approve(w.kumari(), taskId(submitted, 1, w.deptHead().getRoleName()), 200);
        assertThat(taskStatus(legal)).isEqualTo("cancelled");
        Map<String, Object> row = fx.jdbc().queryForMap(
                "select status, cancel_reason from workflow_info_requests where info_request_id = ?", request);
        assertThat(row.get("status")).isEqualTo("cancelled");
        assertThat(row.get("cancel_reason")).isEqualTo("The stage was approved without this approver");
        assertThat(fx.count("select count(*) from workflow_actions where info_request_id = ? and action_type = 'info_cancelled'",
                request)).isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and sla_paused_at is null"
                + " and sla_paused_seconds >= 0", legal)).isEqualTo(1);
        assertThat(instance(instanceId, w.chamath()).get("status").asString()).isEqualTo("approved");
        assertThat(respond(w.chamath(), request, "too late", null, 409).get("code").asString())
                .isEqualTo("WORKFLOW_INFO_REQUEST_CLOSED");
    }

    @Test
    void withdrawingTheWorkflow_cancelsOpenRequests() throws Exception {
        JsonNode submitted = parallel("all", null);
        UUID instanceId = id(submitted, "instanceId");
        UUID request = id(raise(w.saman(), taskId(submitted, 1, "Legal"), w.ruwan(), "?", 201), "infoRequestId");
        postAs("/api/v1/workflow/instances/" + instanceId + "/cancel", w.chamath(), Map.of("reason", "Wrong file"), 200);
        assertThat(fx.jdbc().queryForObject("select cancel_reason from workflow_info_requests where info_request_id = ?",
                String.class, request)).isEqualTo("The workflow was withdrawn");
    }

    @Test
    void adminReassigningAWaitingTask_cancelsItsRequestsAndResumesIt() throws Exception {
        UUID legal = taskId(parallel("all", null), 1, "Legal");
        UUID request = id(raise(w.saman(), legal, w.chamath(), "?", 201), "infoRequestId");
        reassign(w.admin(), legal, w.dilani(), "Saman is away", 200);
        assertThat(fx.jdbc().queryForObject("select cancel_reason from workflow_info_requests where info_request_id = ?",
                String.class, request)).isEqualTo("The task was reassigned to someone else");
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and status = 'claimed'"
                + " and delegated_to = ? and sla_paused_at is null", legal, w.dilani().getUserId())).isEqualTo(1);
        approve(w.dilani(), legal, 200);
    }

    /** S10: the status API and the queues show open requests and supporting documents. */
    @Test
    void statusAndQueues_showRequestsAndSupportingDocuments() throws Exception {
        JsonNode submitted = parallel("all", null);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");
        UUID request = id(raise(w.saman(), legal, w.chamath(), "Annex?", 201), "infoRequestId");

        JsonNode asChamath = instance(instanceId, w.chamath());
        assertThat(stage(asChamath, 1).get("allowInfoRequests").asBoolean()).isTrue();
        JsonNode task = task(asChamath, legal);
        assertThat(task.get("status").asString()).isEqualTo("awaiting_info");
        assertThat(task.get("openInfoRequestCount").asLong()).isEqualTo(1);
        assertThat(task.get("slaPausedAt").isNull()).isFalse();
        assertThat(asChamath.get("infoRequests")).hasSize(1);
        assertThat(asChamath.get("myOpenInfoRequestIds").get(0).asString()).isEqualTo(request.toString());
        assertThat(get200("/api/v1/workflow/tasks/my/count", w.chamath()).get("requestsForMe").asLong()).isEqualTo(1);
        assertThat(myTasks(w.saman(), "all").get(0).get("openInfoRequestCount").asLong()).isEqualTo(1);
        // The person asked can see the run even though they hold no task in it (§9) - Ruwan here.
        UUID second = id(raise(w.saman(), legal, w.ruwan(), "And the budget?", 201), "infoRequestId");
        assertThat(instance(instanceId, w.ruwan()).get("myOpenInfoRequestIds").get(0).asString())
                .isEqualTo(second.toString());

        UUID annex = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Annex A");
        respond(w.chamath(), request, "Attached", List.of(Map.of("documentId", annex, "source", "linked_existing",
                "note", "Signed")), 200);
        JsonNode after = instance(instanceId, w.saman());
        assertThat(after.get("supportingDocuments")).hasSize(1);
        assertThat(after.get("supportingDocuments").get(0).get("canOpen").asBoolean()).isFalse();
        JsonNode answered = after.get("infoRequests").get(0);
        assertThat(answered.get("status").asString()).isEqualTo("answered");
        assertThat(answered.get("supportingDocuments").get(0).get("title").asString()).isEqualTo("Annex A");
        assertThat(get200("/api/v1/workflow/info-requests/my?status=answered", w.chamath()).get("content")).hasSize(1);
        assertThat(get200("/api/v1/workflow/tasks/my/count", w.chamath()).get("requestsForMe").asLong()).isZero();
    }

    @Test
    void switchedOffStage_andGuards() throws Exception {
        UUID legal = taskId(parallel("all", false), 1, "Legal");
        assertThat(raise(w.saman(), legal, w.chamath(), "?", 422).get("code").asString())
                .isEqualTo("WORKFLOW_INFO_REQUESTS_DISABLED");

        ContractWorld other = ContractWorld.create(fx);
        fx.role(other.tenantId(), "Requesters " + UUID.randomUUID(), new String[]{REQUEST_INFO}, other.saman(),
                other.nimal());
        w = other;
        UUID task = taskId(parallel("all", null), 1, "Legal");
        assertThat(raise(other.nimal(), task, other.chamath(), "?", 403).get("code").asString())
                .isEqualTo("WORKFLOW_NOT_ASSIGNEE");
        assertThat(raise(other.saman(), task, other.saman(), "?", 422).get("code").asString())
                .isEqualTo("WORKFLOW_INVALID_INFO_TARGET");
        raise(other.dilani(), task, other.chamath(), "?", 403);   // no request permission
        assertThat(raise(other.saman(), task, outsider, "?", 422).get("code").asString())
                .as("someone from another tenant").isEqualTo("WORKFLOW_INVALID_INFO_TARGET");
    }
}
