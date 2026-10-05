package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

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
                where si.instance_id = ? and si.status <> 'active' and t.status in ('pending', 'claimed')
                """, instanceId)).as("open tasks left on a closed stage").isZero();
    }
}
