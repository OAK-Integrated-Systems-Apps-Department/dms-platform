package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 2 S11: the permission each new endpoint needs, and tenant isolation
 * (another tenant - even its admin - gets 404 for every id of this one).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowPhase2AccessIT extends WorkflowApiSupport {

    private ContractWorld w;
    private User nobody;
    private UUID instanceId;
    private UUID legal;
    private UUID request;
    private UUID comment;
    private UUID supporting;

    @BeforeEach
    void world() throws Exception {
        w = ContractWorld.create(fx);
        fx.role(w.tenantId(), "Requesters " + UUID.randomUUID(), new String[]{WorkflowInfoRequestIT.REQUEST_INFO},
                w.saman());
        fx.role(w.tenantId(), "Commenters " + UUID.randomUUID(),
                new String[]{"docm.documents.view", "docm.document-comments.create"}, w.chamath());
        nobody = fx.user(w.tenantId(), "Nobody");
        activeTemplate(w.admin(), "ACCESS", w.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "all", "halt_immediately", 48,
                        entry("department", w.legal().getDepartmentId()), entry("role", w.deptHead().getRoleId()))));
        JsonNode submitted = submit(w.chamath(), w.contractDocumentId(), 201);
        instanceId = id(submitted, "instanceId");
        legal = taskId(submitted, 1, "Legal");
        request = id(postAs("/api/v1/workflow/tasks/" + legal + "/info-requests", w.saman(),
                Map.of("requestedFromUserId", w.chamath().getUserId(), "message", "?"), 201), "infoRequestId");
        comment = id(postAs("/api/v1/documents/" + w.contractDocumentId() + "/comments", w.chamath(),
                Map.of("body", "hello"), 201), "commentId");
        UUID annex = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Annex");
        supporting = id(postAs("/api/v1/workflow/instances/" + instanceId + "/supporting-documents", w.chamath(),
                Map.of("documentId", annex, "source", "linked_existing"), 201), "supportingDocumentId");
    }

    @Test
    void permissionMatrix() throws Exception {
        JwtRequestPostProcessor none = fx.jwtFor(nobody);
        // Delegation and reassignment
        call(post("/api/v1/workflow/tasks/" + legal + "/delegate"), none,
                Map.of("delegateUserId", w.dilani().getUserId()), 403);
        call(post("/api/v1/workflow/tasks/" + legal + "/reassign"), fx.jwtFor(w.saman()),
                Map.of("userId", w.dilani().getUserId(), "reason", "x"), 403);
        call(get("/api/v1/workflow/tasks/" + legal + "/delegate-candidates"), none, null, 403);
        // Information requests: raising needs the code; answering only identity
        call(post("/api/v1/workflow/tasks/" + legal + "/info-requests"), fx.jwtFor(w.dilani()),
                Map.of("requestedFromUserId", w.chamath().getUserId(), "message", "?"), 403);
        call(post("/api/v1/workflow/info-requests/" + request + "/decline"), none, Map.of("reason", "x"), 403);
        call(post("/api/v1/workflow/info-requests/" + request + "/withdraw"), none, null, 403);
        call(post("/api/v1/workflow/info-requests/" + request + "/reassign"), none,
                Map.of("requestedFromUserId", w.dilani().getUserId()), 403);
        call(get("/api/v1/workflow/instances/" + instanceId + "/info-requests"), none, null, 403);
        assertThat(call(get("/api/v1/workflow/info-requests/my"), none, null, 200).get("content")).isEmpty();
        // Supporting documents: attaching needs docm.documents.view; reading needs visibility
        call(post("/api/v1/workflow/instances/" + instanceId + "/supporting-documents"), none,
                Map.of("documentId", w.contractDocumentId(), "source", "linked_existing"), 403);
        call(get("/api/v1/workflow/instances/" + instanceId + "/supporting-documents"), none, null, 403);
        call(delete("/api/v1/workflow/instances/" + instanceId + "/supporting-documents/" + supporting), none, null, 403);
        // Comments
        call(get("/api/v1/documents/" + w.contractDocumentId() + "/comments"), none, null, 403);
        call(post("/api/v1/documents/" + w.contractDocumentId() + "/comments"), none, Map.of("body", "x"), 403);
        call(put("/api/v1/comments/" + comment), none, Map.of("body", "x"), 403);
        call(delete("/api/v1/comments/" + comment), none, null, 403);
        call(get("/api/v1/comments/" + comment), none, null, 403);
        assertThat(call(get("/api/v1/mentions/my"), none, null, 200).get("content")).isEmpty();
        call(get("/api/v1/people/lookup"), none, null, 403);

        // A tenant admin holds every code implicitly.
        JwtRequestPostProcessor admin = fx.tenantAdminJwtFor(w.admin());
        call(get("/api/v1/workflow/instances/" + instanceId + "/info-requests"), admin, null, 200);
        call(get("/api/v1/documents/" + w.contractDocumentId() + "/comments"), admin, null, 200);
        call(get("/api/v1/people/lookup"), admin, null, 200);
    }

    @Test
    void anotherTenantGets404ForEveryId_andCantUseThisTenantsPeople() throws Exception {
        ContractWorld other = ContractWorld.create(fx);
        JwtRequestPostProcessor outsider = fx.tenantAdminJwtFor(other.admin());
        String task = "/api/v1/workflow/tasks/" + legal;
        String req = "/api/v1/workflow/info-requests/" + request;
        String run = "/api/v1/workflow/instances/" + instanceId;
        String doc = "/api/v1/documents/" + w.contractDocumentId();

        call(post(task + "/delegate"), outsider, Map.of("delegateUserId", other.dilani().getUserId()), 404);
        call(post(task + "/reassign"), outsider, Map.of("userId", other.dilani().getUserId(), "reason", "x"), 404);
        call(get(task + "/delegate-candidates"), outsider, null, 404);
        call(post(task + "/info-requests"), outsider,
                Map.of("requestedFromUserId", other.dilani().getUserId(), "message", "?"), 404);
        call(post(req + "/respond"), outsider, Map.of("message", "x"), 404);
        call(post(req + "/decline"), outsider, Map.of("reason", "x"), 404);
        call(post(req + "/withdraw"), outsider, null, 404);
        call(post(req + "/reassign"), outsider, Map.of("requestedFromUserId", other.dilani().getUserId()), 404);
        call(get(run + "/info-requests"), outsider, null, 404);
        call(get(run + "/supporting-documents"), outsider, null, 404);
        call(post(run + "/supporting-documents"), outsider,
                Map.of("documentId", other.contractDocumentId(), "source", "linked_existing"), 404);
        call(delete(run + "/supporting-documents/" + supporting), outsider, null, 404);
        call(get(doc + "/comments"), outsider, null, 404);
        call(post(doc + "/comments"), outsider, Map.of("body", "x"), 404);
        call(put("/api/v1/comments/" + comment), outsider, Map.of("body", "x"), 404);
        call(delete("/api/v1/comments/" + comment), outsider, null, 404);
        call(get("/api/v1/comments/" + comment), outsider, null, 404);
        assertThat(call(get("/api/v1/workflow/info-requests/my"), outsider, null, 200).get("content")).isEmpty();
        assertThat(call(get("/api/v1/people/lookup?search=" + w.saman().getFirstName()), outsider, null, 200))
                .noneSatisfy(p -> assertThat(p.get("userId").asString()).isEqualTo(w.saman().getUserId().toString()));

        // This tenant's people can't be another tenant's escalation target, request target or delegate.
        UUID draft = id(call(post("/api/v1/workflow/templates"), outsider, Map.of("name", "X", "code", "XTENANT",
                "documentTypeId", other.contractTypeId()), 201), "templateId");
        Map<String, Object> stage = stageDef("S", "any", "halt_immediately", 24, entry("user", other.nimal().getUserId()));
        stage.put("escalationTarget", "named_user");
        stage.put("escalationUserId", w.kumari().getUserId());
        assertThat(call(post("/api/v1/workflow/templates/" + draft + "/stages"), outsider, stage, 422)
                .get("code").asString()).isEqualTo("WORKFLOW_INVALID_ASSIGNEE");
    }
}
