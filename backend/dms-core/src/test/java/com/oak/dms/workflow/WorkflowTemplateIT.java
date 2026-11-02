package com.oak.dms.workflow;

import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Template CRUD, versioning and stage configuration (W3/W4): drafts only
 * are editable, activation demotes the previous active version of the type,
 * a new version deep-copies stages and approvers, and in-flight instances
 * keep their own version (decision 9).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowTemplateIT extends WorkflowApiSupport {

    private ContractWorld w;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
    }

    private UUID draft(String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Contract approval");
        body.put("code", code);
        body.put("documentTypeId", w.contractTypeId());
        JsonNode created = adminPost("/api/v1/workflow/templates", w.admin(), body, 201);
        assertThat(created.get("status").asString()).isEqualTo("draft");
        assertThat(created.get("versionNumber").asInt()).isEqualTo(1);
        return id(created, "templateId");
    }

    private Map<String, Object> legalStage(String name) {
        return stageDef(name, "all", "halt_immediately", 48, entry("department", w.legal().getDepartmentId()));
    }

    private JsonNode addStage(UUID templateId, Map<String, Object> stage, int expected) throws Exception {
        return adminPost("/api/v1/workflow/templates/" + templateId + "/stages", w.admin(), stage, expected);
    }

    @Test
    void codeIsUpperCasedAndUnique() throws Exception {
        UUID id = draft("contract-flow");
        assertThat(adminGet("/api/v1/workflow/templates/" + id).get("code").asString()).isEqualTo("CONTRACT-FLOW");
        Map<String, Object> dup = Map.of("name", "x", "code", "CONTRACT-FLOW", "documentTypeId", w.contractTypeId());
        assertThat(adminPost("/api/v1/workflow/templates", w.admin(), dup, 409).get("code").asString())
                .isEqualTo("WORKFLOW_TEMPLATE_CODE_EXISTS");
    }

    @Test
    void stagesAreAddedUpdatedReorderedAndDeleted() throws Exception {
        UUID id = draft("STAGES");
        addStage(id, legalStage("One"), 201);
        addStage(id, stageDef("Two", "any", "wait_for_all", null, entry("role", w.deptHead().getRoleId())), 201);
        JsonNode t = addStage(id, stageDef("Three", "majority", "halt_immediately", 24,
                entry("user", w.nimal().getUserId()), entry("user", w.kumari().getUserId())), 201);
        List<UUID> ids = stageIds(t);
        assertThat(names(t)).containsExactly("One", "Two", "Three");

        JsonNode reordered = adminPut("/api/v1/workflow/templates/" + id + "/stages/order", w.admin(),
                Map.of("stageIds", List.of(ids.get(2), ids.get(0), ids.get(1))), 200);
        assertThat(names(reordered)).containsExactly("Three", "One", "Two");
        assertThat(adminPut("/api/v1/workflow/templates/" + id + "/stages/order", w.admin(),
                Map.of("stageIds", List.of(ids.get(0), ids.get(1))), 422).get("code").asString())
                .isEqualTo("WORKFLOW_INVALID_STAGE_ORDER");

        // Replace "Three"'s approvers: Kumari stays (same participant id), Nimal goes, Asanka comes in.
        UUID kumariParticipant = participantId(reordered, "Three", w.kumari().getUserId());
        JsonNode updated = adminPut("/api/v1/workflow/templates/" + id + "/stages/" + ids.get(2), w.admin(),
                stageDef("Three", "majority", "halt_immediately", 24,
                        entry("user", w.kumari().getUserId()), entry("user", w.asanka().getUserId())), 200);
        assertThat(participantId(updated, "Three", w.kumari().getUserId())).isEqualTo(kumariParticipant);
        assertThat(stageNamed(updated, "Three").get("participants")).hasSize(2);

        JsonNode afterDelete = call(delete("/api/v1/workflow/templates/" + id + "/stages/" + ids.get(2)),
                fx.tenantAdminJwtFor(w.admin()), null, 200);
        assertThat(names(afterDelete)).containsExactly("One", "Two");
        List<Integer> orders = new ArrayList<>();
        afterDelete.get("stages").forEach(s -> orders.add(s.get("stageOrder").asInt()));
        assertThat(orders).containsExactly(1, 2);
    }

    @Test
    void stageValidation() throws Exception {
        UUID id = draft("VALIDATION");
        assertThat(addStage(id, stageDef("Dup", "all", "halt_immediately", null,
                entry("user", w.nimal().getUserId()), entry("user", w.nimal().getUserId())), 422).get("code").asString())
                .isEqualTo("WORKFLOW_DUPLICATE_PARTICIPANT");
        assertThat(addStage(id, stageDef("Ghost", "all", "halt_immediately", null,
                entry("role", UUID.randomUUID())), 422).get("code").asString()).isEqualTo("WORKFLOW_INVALID_ASSIGNEE");
        Map<String, Object> reminderWithoutSla = new LinkedHashMap<>(legalStage("Reminder"));
        reminderWithoutSla.put("slaHours", null);
        reminderWithoutSla.put("reminderBeforeHours", 4);
        assertThat(addStage(id, reminderWithoutSla, 422).get("code").asString()).isEqualTo("WORKFLOW_INVALID_STAGE");
        addStage(id, stageDef("Empty", "all", "halt_immediately", null), 400); // @NotEmpty participants

        // Another tenant's department is "not found" here.
        ContractWorld other = ContractWorld.create(fx);
        assertThat(addStage(id, stageDef("Foreign", "all", "halt_immediately", null,
                entry("department", other.legal().getDepartmentId())), 422).get("code").asString())
                .isEqualTo("WORKFLOW_INVALID_ASSIGNEE");
    }

    @Test
    void activationNeedsStages_andDemotesOnlyThePreviousActiveVersionOfTheSameCode() throws Exception {
        UUID first = draft("FIRST");
        assertThat(adminPost("/api/v1/workflow/templates/" + first + "/activate", w.admin(), null, 422)
                .get("code").asString()).isEqualTo("WORKFLOW_TEMPLATE_INVALID");
        addStage(first, legalStage("Review"), 201);
        adminPost("/api/v1/workflow/templates/" + first + "/activate", w.admin(), null, 200);

        // SECOND is a different template (code), same document type - V121: it does NOT demote FIRST.
        UUID second = draft("SECOND");
        addStage(second, legalStage("Review"), 201);
        adminPost("/api/v1/workflow/templates/" + second + "/activate", w.admin(), null, 200);

        assertThat(adminGet("/api/v1/workflow/templates/" + first).get("status").asString()).isEqualTo("active");
        assertThat(adminGet("/api/v1/workflow/templates/" + second).get("status").asString()).isEqualTo("active");
        assertThat(fx.count("select count(*) from workflow_templates where tenant_id = ? and document_type_id = ?"
                + " and status = 'active'", w.tenantId(), w.contractTypeId())).isEqualTo(2);

        // A new version of FIRST's own code still demotes FIRST (version exclusivity is unchanged).
        JsonNode firstV2 = adminPost("/api/v1/workflow/templates/" + first + "/versions", w.admin(), null, 201);
        UUID firstV2Id = id(firstV2, "templateId");
        addStage(firstV2Id, legalStage("Review"), 201);
        adminPost("/api/v1/workflow/templates/" + firstV2Id + "/activate", w.admin(), null, 200);
        assertThat(adminGet("/api/v1/workflow/templates/" + first).get("status").asString()).isEqualTo("inactive");
        assertThat(adminGet("/api/v1/workflow/templates/" + firstV2Id).get("status").asString()).isEqualTo("active");
        assertThat(adminGet("/api/v1/workflow/templates/" + second).get("status").asString()).isEqualTo("active");

        // Only drafts are edited.
        assertThat(adminPost("/api/v1/workflow/templates/" + second + "/stages", w.admin(), legalStage("More"), 409)
                .get("code").asString()).isEqualTo("WORKFLOW_TEMPLATE_NOT_DRAFT");
    }

    @Test
    void concurrentActivationsOfDifferentCodes_canAllEndUpActive() throws Exception {
        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID d = draft("RACE" + i);
            addStage(d, legalStage("Review"), 201);
            drafts.add(d);
        }
        ExecutorService pool = Executors.newFixedThreadPool(drafts.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID d : drafts) {
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post("/api/v1/workflow/templates/" + d + "/activate")
                        .with(fx.tenantAdminJwtFor(w.admin()))).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdownNow();
        // Different template families (codes) don't conflict with each other - all 4 stay active.
        assertThat(fx.count("select count(*) from workflow_templates where tenant_id = ? and document_type_id = ?"
                + " and status = 'active'", w.tenantId(), w.contractTypeId())).isEqualTo(4);
    }

    @Test
    void concurrentActivationsOfTheSameCode_leaveExactlyOneActive() throws Exception {
        // Only one draft per code can exist at a time, so build up several versions of the same
        // family by activating each before drafting the next - v1 and v2 end up inactive (superseded),
        // v3 active. All three are still individually re-activatable; racing v1/v2/v3's activation
        // against each other is what exercises the per-code backstop below.
        UUID v1 = draft("SAMECODE");
        addStage(v1, legalStage("Review"), 201);
        adminPost("/api/v1/workflow/templates/" + v1 + "/activate", w.admin(), null, 200);

        UUID v2 = id(adminPost("/api/v1/workflow/templates/" + v1 + "/versions", w.admin(), null, 201), "templateId");
        addStage(v2, legalStage("Review"), 201);
        adminPost("/api/v1/workflow/templates/" + v2 + "/activate", w.admin(), null, 200);

        UUID v3 = id(adminPost("/api/v1/workflow/templates/" + v2 + "/versions", w.admin(), null, 201), "templateId");
        addStage(v3, legalStage("Review"), 201);
        adminPost("/api/v1/workflow/templates/" + v3 + "/activate", w.admin(), null, 200);

        List<UUID> versions = List.of(v1, v2, v3);
        ExecutorService pool = Executors.newFixedThreadPool(versions.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID v : versions) {
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post("/api/v1/workflow/templates/" + v + "/activate")
                        .with(fx.tenantAdminJwtFor(w.admin()))).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isIn(200, 409);
        }
        pool.shutdownNow();
        assertThat(fx.count("select count(*) from workflow_templates where tenant_id = ? and code = ?"
                + " and status = 'active'", w.tenantId(), "SAMECODE")).isEqualTo(1);
    }

    @Test
    void newVersionDeepCopies_andInFlightInstancesKeepTheirVersion() throws Exception {
        UUID v1 = draft("VERSIONED");
        addStage(v1, legalStage("Review"), 201);
        addStage(v1, stageDef("Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId())), 201);
        adminPost("/api/v1/workflow/templates/" + v1 + "/activate", w.admin(), null, 200);
        UUID instanceId = id(submit(w.chamath(), w.contractDocumentId(), 201), "instanceId");

        JsonNode v2 = adminPost("/api/v1/workflow/templates/" + v1 + "/versions", w.admin(), null, 201);
        UUID v2Id = id(v2, "templateId");
        assertThat(v2.get("versionNumber").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asString()).isEqualTo("draft");
        assertThat(names(v2)).containsExactly("Review", "Sign-off");
        assertThat(stageIds(v2)).doesNotContainAnyElementsOf(stageIds(adminGet("/api/v1/workflow/templates/" + v1)));
        assertThat(adminPost("/api/v1/workflow/templates/" + v1 + "/versions", w.admin(), null, 409)
                .get("code").asString()).isEqualTo("WORKFLOW_DRAFT_EXISTS");

        // Change v2 and activate it - the running instance stays on v1's stages.
        adminPut("/api/v1/workflow/templates/" + v2Id + "/stages/" + stageIds(v2).get(1), w.admin(),
                stageDef("CFO sign-off", "any", "halt_immediately", 12, entry("user", w.nimal().getUserId())), 200);
        adminPost("/api/v1/workflow/templates/" + v2Id + "/activate", w.admin(), null, 200);

        JsonNode running = instance(instanceId, w.chamath());
        assertThat(running.get("templateVersionNumber").asInt()).isEqualTo(1);
        assertThat(id(running, "templateId")).isEqualTo(v1);
        assertThat(stage(running, 2).get("name").asString()).isEqualTo("Sign-off");

        assertThat(adminGet("/api/v1/workflow/templates/" + v1 + "/versions")).hasSize(2);
        // v1 ran an instance - it can never be deleted.
        assertThat(call(delete("/api/v1/workflow/templates/" + v1), fx.tenantAdminJwtFor(w.admin()), null, 409)
                .get("code").asString()).isEqualTo("WORKFLOW_TEMPLATE_IN_USE");
    }

    @Test
    void allowInfoRequests_defaultsOn_isStored_andIsCopiedToANewVersion() throws Exception {
        UUID v1 = draft("INFOREQ");
        JsonNode open = addStage(v1, legalStage("Open"), 201);
        Map<String, Object> closed = legalStage("Closed");
        closed.put("allowInfoRequests", false);
        JsonNode both = addStage(v1, closed, 201);
        assertThat(open.get("stages").get(0).get("allowInfoRequests").asBoolean()).isTrue();
        assertThat(both.get("stages").get(1).get("allowInfoRequests").asBoolean()).isFalse();

        adminPost("/api/v1/workflow/templates/" + v1 + "/activate", w.admin(), null, 200);
        JsonNode v2 = adminPost("/api/v1/workflow/templates/" + v1 + "/versions", w.admin(), null, 201);
        assertThat(v2.get("stages").get(0).get("allowInfoRequests").asBoolean()).isTrue();
        assertThat(v2.get("stages").get(1).get("allowInfoRequests").asBoolean()).isFalse();
    }

    @Test
    void draftWithoutInstances_canBeDeleted() throws Exception {
        UUID id = draft("THROWAWAY");
        addStage(id, legalStage("Review"), 201);
        call(delete("/api/v1/workflow/templates/" + id), fx.tenantAdminJwtFor(w.admin()), null, 204);
        call(get("/api/v1/workflow/templates/" + id), fx.tenantAdminJwtFor(w.admin()), null, 404);
    }

    @Test
    void listFiltersByStatusAndSearch() throws Exception {
        UUID a = draft("ALPHA");
        addStage(a, legalStage("Review"), 201);
        adminPost("/api/v1/workflow/templates/" + a + "/activate", w.admin(), null, 200);
        draft("BETA");
        JsonNode active = adminGet("/api/v1/workflow/templates?status=active");
        assertThat(active.get("totalElements").asLong()).isEqualTo(1);
        assertThat(active.get("content").get(0).get("code").asString()).isEqualTo("ALPHA");
        assertThat(active.get("content").get(0).get("stageCount").asLong()).isEqualTo(1);
        assertThat(adminGet("/api/v1/workflow/templates?search=bet").get("totalElements").asLong()).isEqualTo(1);
    }

    /** Plan R8: a designer holding only wa.configure-workflow-templates.* can fill the stage builder's pickers. */
    @Test
    void lookups_areReadableByADesignerWithoutAdminListPermissions() throws Exception {
        var designer = fx.user(w.tenantId(), "Designer");
        fx.role(w.tenantId(), "Designer", WorkflowFixtures.DESIGNER_PERMISSIONS, designer);
        JsonNode users = get200("/api/v1/workflow/lookups/users", designer);
        assertThat(users.toString()).contains(w.nimal().getUserId().toString()).contains("Nimal");
        assertThat(get200("/api/v1/workflow/lookups/roles", designer).toString()).contains(w.deptHead().getRoleName());
        assertThat(get200("/api/v1/workflow/lookups/document-types", designer).toString()).contains("Contract", "Invoice");
        call(get("/api/v1/users"), fx.jwtFor(designer), null, 403);
        call(get("/api/v1/workflow/lookups/users"), fx.jwtFor(w.saman()), null, 403);
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode adminGet(String path) throws Exception {
        return call(get(path), fx.tenantAdminJwtFor(w.admin()), null, 200);
    }

    private static List<String> names(JsonNode template) {
        List<String> names = new ArrayList<>();
        template.get("stages").forEach(s -> names.add(s.get("name").asString()));
        return names;
    }

    private static List<UUID> stageIds(JsonNode template) {
        List<UUID> ids = new ArrayList<>();
        template.get("stages").forEach(s -> ids.add(UUID.fromString(s.get("stageId").asString())));
        return ids;
    }

    private static JsonNode stageNamed(JsonNode template, String name) {
        for (JsonNode s : template.get("stages")) {
            if (name.equals(s.get("name").asString())) {
                return s;
            }
        }
        throw new AssertionError("No stage " + name);
    }

    private static UUID participantId(JsonNode template, String stage, UUID assigneeId) {
        for (JsonNode p : stageNamed(template, stage).get("participants")) {
            if (assigneeId.toString().equals(p.get("assigneeId").asString())) {
                return UUID.fromString(p.get("participantId").asString());
            }
        }
        throw new AssertionError("No participant " + assigneeId);
    }
}
