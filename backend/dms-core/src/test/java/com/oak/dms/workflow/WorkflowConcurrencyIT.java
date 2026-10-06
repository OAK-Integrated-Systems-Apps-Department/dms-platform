package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.service.WorkflowSlaService;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Decision 6 / plan §6: concurrent actors on the same stage, task or
 * document. Each case releases every request at once from a start gate,
 * repeats on a fresh tenant, and then checks the invariants: counters equal
 * the decided task rows, at most one active stage, at most one in-progress
 * instance, and no request failed with anything but its expected refusal.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowConcurrencyIT extends WorkflowApiSupport {

    private static final int REPEATS = 5;

    @Autowired
    private WorkflowSlaService slaService;

    /** all, n=2: Saman and Kumari approve at the same moment - stage 2 activates exactly once. */
    @RepeatedTest(REPEATS)
    void twoLastApproversAtOnce_advanceExactlyOnce() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        contractTemplate(w, "all", "halt_immediately");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");

        List<Integer> codes = concurrently(List.of(
                act(w.saman(), taskId(submitted, 1, "Legal"), "approve"),
                act(w.kumari(), taskId(submitted, 1, w.deptHead().getRoleName()), "approve")));

        assertThat(codes).containsOnly(200);
        JsonNode after = instance(instanceId, w.chamath());
        assertThat(stage(after, 1).get("status").asString()).isEqualTo("approved");
        assertThat(stage(after, 1).get("approvedCount").asInt()).isEqualTo(2);
        assertThat(stage(after, 2).get("status").asString()).isEqualTo("active");
        assertThat(stage(after, 2).get("tasks")).hasSize(1);
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ? and action_type = 'stage_activated'",
                instanceId)).isEqualTo(2);
        assertInvariants(instanceId);
    }

    /** any, n=3: three entries approve at once - one concludes the stage, the others are refused cleanly. */
    @RepeatedTest(REPEATS)
    void anyRule_threeApproversAtOnce_oneWins() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        activeTemplate(w.admin(), "ANY3", w.contractTypeId(), "flag_only", List.of(
                stageDef("Department Review", "any", "halt_immediately", null,
                        entry("department", w.legal().getDepartmentId()),
                        entry("department", w.finance().getDepartmentId()),
                        entry("role", w.deptHead().getRoleId())),
                stageDef("Final Sign-off", "any", "halt_immediately", null, entry("user", w.nimal().getUserId()))));
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");

        List<Integer> codes = concurrently(List.of(
                act(w.saman(), taskId(submitted, 1, "Legal"), "approve"),
                act(w.finance1(), taskId(submitted, 1, "Finance"), "approve"),
                act(w.kumari(), taskId(submitted, 1, w.deptHead().getRoleName()), "approve")));

        assertThat(codes).containsOnly(200, 409);
        assertThat(codes.stream().filter(c -> c == 200)).hasSize(1);
        JsonNode after = instance(instanceId, w.chamath());
        assertThat(stage(after, 1).get("status").asString()).isEqualTo("approved");
        assertThat(stage(after, 1).get("approvedCount").asInt()).isEqualTo(1);
        long cancelled = 0;
        for (JsonNode t : stage(after, 1).get("tasks")) {
            if ("cancelled".equals(t.get("status").asString())) {
                cancelled++;
            }
        }
        assertThat(cancelled).isEqualTo(2);
        assertThat(stage(after, 2).get("tasks")).hasSize(1);
        assertInvariants(instanceId);
    }

    /** halt_immediately: an approval and a rejection race - whatever the order, the workflow ends rejected. */
    @RepeatedTest(REPEATS)
    void approveRacesReject_endsRejectedWithConsistentCounters() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        contractTemplate(w, "all", "halt_immediately");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");

        List<Integer> codes = concurrently(List.of(
                act(w.saman(), taskId(submitted, 1, "Legal"), "approve"),
                act(w.asanka(), taskId(submitted, 1, w.deptHead().getRoleName()), "reject")));

        // Approve first: both 200. Reject first: the approval is refused (stage closed).
        assertThat(codes).allMatch(c -> c == 200 || c == 409);
        assertThat(codes.get(1)).isEqualTo(200);
        assertThat(instance(instanceId, w.chamath()).get("status").asString()).isEqualTo("rejected");
        assertInvariants(instanceId);
    }

    /** Ten Legal members claim the same pool task at once - exactly one holds it. */
    @RepeatedTest(REPEATS)
    void claimRace_exactlyOneClaimer() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        List<User> legal = new ArrayList<>(List.of(w.saman(), w.dilani(), w.ruwan()));
        for (int i = 0; i < 7; i++) {
            User member = fx.user(w.tenantId(), "Legal" + i);
            fx.role(w.tenantId(), "Approver extra", WorkflowFixtures.APPROVER_PERMISSIONS, member);
            member.setDepartmentId(w.legal().getDepartmentId());
            legal.add(member);
        }
        legal.stream().skip(3).forEach(this::saveUser);
        contractTemplate(w, "all", "halt_immediately");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID legalTask = taskId(submitted, 1, "Legal");

        List<Callable<Integer>> calls = new ArrayList<>();
        for (User member : legal) {
            calls.add(request(member, post("/api/v1/workflow/tasks/" + legalTask + "/claim"), null));
        }
        List<Integer> codes = concurrently(calls);

        assertThat(codes).containsOnly(200, 409);
        assertThat(codes.stream().filter(c -> c == 200)).hasSize(1);
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and status = 'claimed'"
                + " and claimed_by is not null", legalTask)).isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_actions where task_id = ? and action_type = 'claimed'",
                legalTask)).isEqualTo(1);
    }

    /** The initiator withdraws while an approver acts - one of them wins, the instance ends cancelled. */
    @RepeatedTest(REPEATS)
    void approveRacesCancel_endsCancelled() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        contractTemplate(w, "all", "halt_immediately");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");

        List<Integer> codes = concurrently(List.of(
                act(w.saman(), taskId(submitted, 1, "Legal"), "approve"),
                request(w.chamath(), post("/api/v1/workflow/instances/" + instanceId + "/cancel"),
                        Map.of("reason", "Wrong attachment"))));

        assertThat(codes.get(1)).isEqualTo(200);
        assertThat(codes.get(0)).isIn(200, 409);
        assertThat(instance(instanceId, w.chamath()).get("status").asString()).isEqualTo("cancelled");
        assertInvariants(instanceId);
    }

    /** The holder delegates while approving - one wins; the task is never both decided and newly delegated. */
    @RepeatedTest(REPEATS)
    void delegateRacesApprove_oneWins() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        activeTemplate(w.admin(), "DELEGATE", w.contractTypeId(), "flag_only", List.of(
                stageDef("Final Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID task = taskId(submitted, 1, w.nimal().getDisplayName());

        List<Integer> codes = concurrently(List.of(
                act(w.nimal(), task, "approve"),
                request(w.nimal(), post("/api/v1/workflow/tasks/" + task + "/delegate"),
                        Map.of("delegateUserId", w.kumari().getUserId()))));

        // Approve first: the delegation finds a closed task (409). Delegate first: Nimal no longer holds it (403).
        assertThat(codes).containsAnyOf(200);
        assertThat(codes.stream().filter(c -> c == 200)).hasSize(1);
        assertThat(codes).allMatch(c -> c == 200 || c == 403 || c == 409);
        boolean approved = codes.get(0) == 200;
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and status = 'approved'", task))
                .isEqualTo(approved ? 1 : 0);
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and delegated_to = ?", task,
                w.kumari().getUserId())).isEqualTo(approved ? 0 : 1);
        assertThat(fx.count("select count(*) from workflow_actions where task_id = ? and action_type = 'delegated'",
                task)).isEqualTo(approved ? 0 : 1);
        assertInvariants(instanceId);
    }

    /** Two scheduler runs (two app instances) over the same due tasks - each reminder is recorded exactly once. */
    @RepeatedTest(REPEATS)
    void twoSlaRunnersAtOnce_remindEachTaskOnce() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        Map<String, Object> stage = stageDef("Review", "all", "halt_immediately", 48,
                entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId()));
        stage.put("reminderBeforeHours", 24);
        activeTemplate(w.admin(), "SLARACE", w.contractTypeId(), "flag_only", List.of(stage));
        List<UUID> instances = new ArrayList<>();
        instances.add(id(submit(w.chamath(), w.contractDocumentId(), 201), "instanceId"));
        for (int i = 0; i < 9; i++) {
            UUID doc = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Contract " + i);
            instances.add(id(submit(w.chamath(), doc, 201), "instanceId"));
        }
        fx.jdbc().update("update workflow_tasks t set due_at = now() + interval '23 hours' from workflow_stage_instances si"
                + " where si.stage_instance_id = t.stage_instance_id and si.tenant_id = ?", w.tenantId());

        concurrently(List.of(slaService::runOnce, slaService::runOnce));
        slaService.runOnce();   // whatever a runner skipped as busy

        assertThat(fx.count("""
                select count(*) from workflow_tasks t join workflow_stage_instances si
                  on si.stage_instance_id = t.stage_instance_id
                where si.tenant_id = ? and (select count(*) from workflow_actions a
                                            where a.task_id = t.task_id and a.action_type = 'reminded') <> 1
                """, w.tenantId())).as("tasks not reminded exactly once").isZero();
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'WORKFLOW_TASK_REMINDER_SENT'",
                w.tenantId())).isEqualTo(20);
        for (UUID instanceId : instances) {
            assertInvariants(instanceId);
        }
    }

    /** The scheduler escalates while the holder approves - both end consistent, the escalation at most once. */
    @RepeatedTest(REPEATS)
    void slaRunnerRacesApprove_consistent() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        Map<String, Object> stage = stageDef("Review", "any", "halt_immediately", 48,
                entry("department", w.legal().getDepartmentId()));
        stage.put("escalationTarget", "named_user");
        stage.put("escalationUserId", w.nimal().getUserId());
        activeTemplate(w.admin(), "SLAAPPROVE", w.contractTypeId(), "flag_only", List.of(stage));
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");
        fx.jdbc().update("update workflow_tasks set due_at = now() - interval '1 minute' where task_id = ?", legal);

        List<Integer> codes = concurrently(List.of(
                act(w.saman(), legal, "approve"),
                () -> { slaService.runOnce(); return 0; }));

        assertThat(codes.get(0)).isEqualTo(200);
        assertThat(instance(instanceId, w.chamath()).get("status").asString()).isEqualTo("approved");
        assertThat(fx.count("select count(*) from workflow_actions where task_id = ? and action_type = 'escalated'",
                legal)).isLessThanOrEqualTo(1);
        assertInvariants(instanceId);
    }

    /** The holder raises an information request while approving - one wins cleanly. */
    @RepeatedTest(REPEATS)
    void raiseRacesApprove_oneWins() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        fx.role(w.tenantId(), "Requesters", new String[]{WorkflowInfoRequestIT.REQUEST_INFO}, w.saman());
        contractTemplate(w, "all", "halt_immediately");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");

        List<Integer> codes = concurrently(List.of(
                act(w.saman(), legal, "approve"),
                request(w.saman(), post("/api/v1/workflow/tasks/" + legal + "/info-requests"),
                        Map.of("requestedFromUserId", w.chamath().getUserId(), "message", "Annex?"))));

        // Approve first: the request finds a decided task (409). Raise first: the approval is blocked (409).
        assertThat(codes).containsAnyOf(200, 201);
        assertThat(codes.stream().filter(c -> c == 409)).hasSize(1);
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and status = 'approved'", legal))
                .isEqualTo(codes.get(0) == 200 ? 1 : 0);
        assertInvariants(instanceId);
    }

    /** Two people answer the task's last two open requests at once - the task resumes exactly once. */
    @RepeatedTest(REPEATS)
    void lastTwoResponsesAtOnce_resumeOnce() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        fx.role(w.tenantId(), "Requesters", new String[]{WorkflowInfoRequestIT.REQUEST_INFO}, w.saman());
        contractTemplate(w, "all", "halt_immediately");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");
        UUID r1 = id(postAs("/api/v1/workflow/tasks/" + legal + "/info-requests", w.saman(),
                Map.of("requestedFromUserId", w.chamath().getUserId(), "message", "A?"), 201), "infoRequestId");
        UUID r2 = id(postAs("/api/v1/workflow/tasks/" + legal + "/info-requests", w.saman(),
                Map.of("requestedFromUserId", w.ruwan().getUserId(), "message", "B?"), 201), "infoRequestId");
        fx.jdbc().update("update workflow_tasks set sla_paused_at = sla_paused_at - interval '1 hour' where task_id = ?",
                legal);
        OffsetDateTime dueBefore = fx.jdbc().queryForObject("select due_at from workflow_tasks where task_id = ?",
                OffsetDateTime.class, legal);

        List<Integer> codes = concurrently(List.of(
                request(w.chamath(), post("/api/v1/workflow/info-requests/" + r1 + "/respond"), Map.of("message", "a")),
                request(w.ruwan(), post("/api/v1/workflow/info-requests/" + r2 + "/respond"), Map.of("message", "b"))));

        assertThat(codes).containsOnly(200);
        Map<String, Object> task = fx.jdbc().queryForMap(
                "select status, sla_paused_at, sla_paused_seconds, due_at from workflow_tasks where task_id = ?", legal);
        assertThat(task.get("status")).isEqualTo("claimed");
        assertThat(task.get("sla_paused_at")).isNull();
        int paused = (Integer) task.get("sla_paused_seconds");
        assertThat(paused).isBetween(3600, 3700);
        OffsetDateTime dueAfter = fx.jdbc().queryForObject("select due_at from workflow_tasks where task_id = ?",
                OffsetDateTime.class, legal);
        assertThat(java.time.Duration.between(dueBefore, dueAfter).getSeconds()).as("due_at moved once")
                .isEqualTo((long) paused);
        assertThat(fx.count("select count(*) from workflow_actions where task_id = ? and action_type = 'info_provided'"
                + " and detail ->> 'taskResumed' = 'true'", legal)).isEqualTo(1);
        assertInvariants(instanceId);
    }

    /** A response races the approval that closes the stage (any) - the request is answered or auto-cancelled. */
    @RepeatedTest(REPEATS)
    void respondRacesStageClosing_consistent() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        fx.role(w.tenantId(), "Requesters", new String[]{WorkflowInfoRequestIT.REQUEST_INFO}, w.saman());
        contractTemplate(w, "any", "halt_immediately");
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        UUID instanceId = id(submitted, "instanceId");
        UUID legal = taskId(submitted, 1, "Legal");
        UUID request = id(postAs("/api/v1/workflow/tasks/" + legal + "/info-requests", w.saman(),
                Map.of("requestedFromUserId", w.chamath().getUserId(), "message", "A?"), 201), "infoRequestId");

        List<Integer> codes = concurrently(List.of(
                request(w.chamath(), post("/api/v1/workflow/info-requests/" + request + "/respond"),
                        Map.of("message", "here")),
                act(w.kumari(), taskId(submitted, 1, w.deptHead().getRoleName()), "approve")));

        assertThat(codes.get(1)).isEqualTo(200);
        String status = fx.jdbc().queryForObject("select status from workflow_info_requests where info_request_id = ?",
                String.class, request);
        assertThat(status).isEqualTo(codes.get(0) == 200 ? "answered" : "cancelled");
        assertThat(codes.get(0)).isIn(200, 409);
        assertThat(fx.jdbc().queryForObject("select status from workflow_tasks where task_id = ?", String.class, legal))
                .isEqualTo("cancelled");
        assertInvariants(instanceId);
    }

    /** Ten submissions of the same document at once - one instance, nine clean 409s. */
    @RepeatedTest(REPEATS)
    void submitRace_oneInstance() throws Exception {
        ContractWorld w = ContractWorld.create(fx);
        contractTemplate(w, "all", "halt_immediately");

        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(request(w.chamath(), post("/api/v1/workflow/instances"),
                    Map.of("documentId", w.contractDocumentId())));
        }
        List<Integer> codes = concurrently(calls);

        assertThat(codes).containsOnly(201, 409);
        assertThat(codes.stream().filter(c -> c == 201)).hasSize(1);
        assertThat(fx.count("select count(*) from workflow_instances where document_id = ?", w.contractDocumentId()))
                .isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_stage_instances si join workflow_instances i"
                + " on i.instance_id = si.instance_id where i.document_id = ?", w.contractDocumentId())).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private void contractTemplate(ContractWorld w, String completion, String rejection) throws Exception {
        activeTemplate(w.admin(), "CONTRACT", w.contractTypeId(), "flag_only", List.of(
                stageDef("Department Review", completion, rejection, 48,
                        entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId())),
                stageDef("Final Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
    }

    private void saveUser(User user) {
        fx.jdbc().update("update users set department_id = ? where user_id = ?", user.getDepartmentId(), user.getUserId());
    }

    private Callable<Integer> act(User as, UUID taskId, String decision) {
        Map<String, Object> body = "reject".equals(decision) ? Map.of("reason", "Not acceptable") : Map.of();
        return request(as, post("/api/v1/workflow/tasks/" + taskId + "/" + decision), body);
    }

    private Callable<Integer> request(User as, MockHttpServletRequestBuilder request, Object body) {
        return () -> {
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
            }
            int status = mvc.perform(request.with(fx.jwtFor(as))).andReturn().getResponse().getStatus();
            return status;
        };
    }

    /** Runs every call at once (start gate) and returns their HTTP statuses in call order. */
    private static List<Integer> concurrently(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : futures) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Counters match the decided task rows; at most one active stage; at most one in-progress instance. */
    private void assertInvariants(UUID instanceId) {
        assertThat(fx.count("""
                select count(*) from workflow_stage_instances si
                where si.instance_id = ?
                  and (si.approved_count <> (select count(*) from workflow_tasks t
                                             where t.stage_instance_id = si.stage_instance_id and t.status = 'approved')
                    or si.rejected_count <> (select count(*) from workflow_tasks t
                                             where t.stage_instance_id = si.stage_instance_id and t.status = 'rejected'))
                """, instanceId)).as("stage counters vs task rows").isZero();
        assertThat(fx.count("select count(*) from workflow_stage_instances where instance_id = ? and status = 'active'",
                instanceId)).isLessThanOrEqualTo(1);
        assertThat(fx.count("""
                select count(*) from workflow_tasks t join workflow_stage_instances si
                  on si.stage_instance_id = t.stage_instance_id
                where si.instance_id = ? and si.status <> 'active' and t.status in ('pending', 'claimed', 'awaiting_info')
                """, instanceId)).as("open tasks left on a closed stage").isZero();
        // decision 12/13: a task awaits information exactly while it has an open request
        assertThat(fx.count("""
                select count(*) from workflow_tasks t join workflow_stage_instances si
                  on si.stage_instance_id = t.stage_instance_id
                where si.instance_id = ?
                  and (t.status = 'awaiting_info') <> exists (select 1 from workflow_info_requests r
                                                              where r.task_id = t.task_id and r.status = 'open')
                """, instanceId)).as("awaiting_info vs open requests").isZero();
        assertThat(fx.count("""
                select count(*) from workflow_info_requests r join workflow_tasks t on t.task_id = r.task_id
                where r.instance_id = ? and r.status = 'open' and t.status <> 'awaiting_info'
                """, instanceId)).as("open requests on a task that isn't waiting").isZero();
    }
}
