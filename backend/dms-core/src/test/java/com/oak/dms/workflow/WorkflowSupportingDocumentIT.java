package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Supporting documents (Phase 2 S8, decisions 17-18). */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowSupportingDocumentIT extends WorkflowApiSupport {

    private ContractWorld w;
    private UUID instanceId;
    private UUID annex;
    private User viewer;

    @BeforeEach
    void world() throws Exception {
        w = ContractWorld.create(fx);
        activeTemplate(w.admin(), "SUPPORT", w.contractTypeId(), "flag_only", List.of(
                stageDef("Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
        instanceId = id(submit(w.chamath(), w.contractDocumentId(), 201), "instanceId");
        annex = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Annex A");
        viewer = fx.user(w.tenantId(), "Viewer");
        fx.role(w.tenantId(), "Viewer " + UUID.randomUUID(), new String[]{"docm.documents.view"}, viewer);
    }

    private String path() {
        return "/api/v1/workflow/instances/" + instanceId + "/supporting-documents";
    }

    private Map<String, Object> link(UUID documentId, UUID versionId, String source) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", documentId);
        body.put("documentVersionId", versionId);
        body.put("source", source);
        body.put("note", "For clause 4");
        return body;
    }

    @Test
    void initiatorLinksADocument_pinnedToItsVersion_andANewVersionChangesNothing() throws Exception {
        JsonNode added = postAs(path(), w.chamath(), link(annex, null, "linked_existing"), 201);
        UUID pinned = id(added, "versionId");
        assertThat(added.get("versionNumber").asInt()).isEqualTo(1);
        assertThat(added.get("canOpen").asBoolean()).isTrue();
        assertThat(postAs(path(), w.chamath(), link(annex, null, "linked_existing"), 409).get("code").asString())
                .isEqualTo("WORKFLOW_SUPPORTING_DOCUMENT_EXISTS");

        fx.newVersion(w.tenantId(), annex, w.chamath());
        JsonNode listed = get200(path(), w.chamath()).get(0);
        assertThat(id(listed, "versionId")).isEqualTo(pinned);
        JsonNode run = instance(instanceId, w.chamath());
        assertThat(run.get("versionChangedFlag").asBoolean()).as("decision 17").isFalse();
        assertThat(run.get("status").asString()).isEqualTo("in_progress");
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ?"
                + " and action_type = 'supporting_document_added'", instanceId)).isEqualTo(1);
    }

    @Test
    void aSupportingDocumentOfARunningApproval_cannotBeDisposedOfByRetention() throws Exception {
        postAs(path(), w.chamath(), link(annex, null, "linked_existing"), 201);
        // The annex has reached the end of its retention and waits for an administrator...
        fx.jdbc().update("update documents set lifecycle_state = 'archived', review_flagged_at = now() where document_id = ?", annex);

        // ...but the approval that is still running needs it, so disposal is refused with the reason.
        JsonNode refused = adminPost("/api/v1/documents/" + annex + "/retention/dispose", w.admin(), Map.of("reason", "Retention ended"), 409);
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_REFERENCED");
        assertThat(refused.get("message").asString()).contains("supporting document");
        assertThat(fx.count("select count(*) from documents where document_id = ? and not is_deleted", annex)).isEqualTo(1);
    }

    @Test
    void anApproverWithoutViewPermission_seesTitleAndNote_butCantOpenIt() throws Exception {
        postAs(path(), w.chamath(), link(annex, null, "linked_existing"), 201);
        JsonNode seen = get200(path(), w.nimal()).get(0);
        assertThat(seen.get("title").asString()).isEqualTo("Annex A");
        assertThat(seen.get("note").asString()).isEqualTo("For clause 4");
        assertThat(seen.get("canOpen").asBoolean()).isFalse();
        call(get("/api/v1/documents/" + annex + "/content"), fx.jwtFor(w.nimal()), null, 403);
        // ...and can't attach anything: no docm.documents.view.
        call(post(path()), fx.jwtFor(w.nimal()), link(annex, null, "linked_existing"), 403);
    }

    @Test
    void guards() throws Exception {
        assertThat(postAs(path(), w.chamath(), link(w.contractDocumentId(), null, "linked_existing"), 422)
                .get("code").asString()).isEqualTo("WORKFLOW_SUPPORTING_IS_SUBJECT");
        assertThat(postAs(path(), viewer, link(annex, null, "linked_existing"), 403).get("code").asString())
                .isEqualTo("WORKFLOW_NOT_PARTICIPANT");
        UUID other = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Other");
        UUID otherVersion = UUID.fromString(get200("/api/v1/documents/" + other, w.chamath())
                .get("latestVersionId").asString());
        assertThat(postAs(path(), w.chamath(), link(annex, otherVersion, "linked_existing"), 422)
                .get("code").asString()).isEqualTo("WORKFLOW_SUPPORTING_DOCUMENT_INVALID");
        UUID nimals = fx.document(w.tenantId(), w.contractTypeId(), w.nimal(), "Nimal's memo");
        assertThat(postAs(path(), w.chamath(), link(nimals, null, "uploaded_new"), 422).get("code").asString())
                .isEqualTo("WORKFLOW_SUPPORTING_DOCUMENT_INVALID");

        ContractWorld outside = ContractWorld.create(fx);
        call(post(path()), fx.tenantAdminJwtFor(outside.admin()), link(annex, null, "linked_existing"), 404);
        call(get(path()), fx.tenantAdminJwtFor(outside.admin()), null, 404);
    }

    @Test
    void submittingWithSupportingDocuments_attachesThemInTheSameTransaction() throws Exception {
        UUID doc = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Contract 2026-200");
        UUID evidence = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Evidence A");

        JsonNode submitted = submit(w.chamath(), doc, List.of(link(evidence, null, "linked_existing")), 201);
        UUID newInstance = id(submitted, "instanceId");

        JsonNode listed = get200("/api/v1/workflow/instances/" + newInstance + "/supporting-documents", w.chamath());
        assertThat(listed).hasSize(1);
        assertThat(id(listed.get(0), "documentId")).isEqualTo(evidence);
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ?"
                + " and action_type = 'supporting_document_added'", newInstance)).isEqualTo(1);
    }

    @Test
    void supportingDocumentContent_servesThePinnedVersion_notTheCurrentOne() throws Exception {
        // Attach v1 of the annex - the supporting document is pinned to that version (decision 17).
        JsonNode added = postAs(path(), w.chamath(), link(annex, null, "linked_existing"), 201);
        UUID supportingDocumentId = id(added, "supportingDocumentId");
        UUID pinnedVersion = id(added, "versionId");
        fx.putVersionContent(pinnedVersion, "PINNED-V1".getBytes(StandardCharsets.UTF_8));

        // A new version becomes the document's current one; the pinned attachment must not follow it.
        UUID currentVersion = fx.newVersion(w.tenantId(), annex, w.chamath());
        fx.putVersionContent(currentVersion, "CURRENT-V2".getBytes(StandardCharsets.UTF_8));
        assertThat(pinnedVersion).isNotEqualTo(currentVersion);

        String contentPath = path() + "/" + supportingDocumentId + "/content";
        // The viewer gets v1's content - the pinned version, not the current one. Chamath holds
        // docm.documents.view and their own access, and no dvc.versions.view, which is all that's needed.
        MockHttpServletResponse ok = getRaw(contentPath, fx.jwtFor(w.chamath()));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getContentAsString()).isEqualTo("PINNED-V1");

        // A participant who can't open the document (no docm.documents.view) gets no content.
        assertThat(getRaw(contentPath, fx.jwtFor(w.nimal())).getStatus()).isEqualTo(403);
        // Someone outside the run can't see it at all.
        ContractWorld outside = ContractWorld.create(fx);
        assertThat(getRaw(contentPath, fx.tenantAdminJwtFor(outside.admin())).getStatus()).isEqualTo(404);
    }

    @Test
    void anInvalidSupportingDocument_failsTheWholeSubmission_andCreatesNothing() throws Exception {
        UUID doc = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Contract 2026-201");

        // The document under submission can't be its own supporting document - and the whole submission fails.
        assertThat(submit(w.chamath(), doc, List.of(link(doc, null, "linked_existing")), 422).get("code").asString())
                .isEqualTo("WORKFLOW_SUPPORTING_IS_SUBJECT");

        // All-or-nothing (decision 11): no instance, no supporting documents, no timeline actions.
        assertThat(fx.count("select count(*) from workflow_instances where document_id = ?", doc)).isZero();
        assertThat(fx.count("select count(*) from workflow_supporting_documents where document_id = ?", doc)).isZero();
        assertThat(fx.count("select count(*) from workflow_actions a join workflow_instances i"
                + " on i.instance_id = a.instance_id where i.document_id = ?", doc)).isZero();
    }

    @Test
    void onlyTheAdderRemovesIt() throws Exception {
        UUID sd = id(postAs(path(), w.chamath(), link(annex, null, "linked_existing"), 201), "supportingDocumentId");
        fx.role(w.tenantId(), "Viewer " + UUID.randomUUID(), new String[]{"docm.documents.view"}, w.nimal());
        call(delete(path() + "/" + sd), fx.jwtFor(w.nimal()), null, 403);
        call(delete(path() + "/" + sd), fx.jwtFor(w.chamath()), null, 204);
        assertThat(get200(path(), w.chamath())).isEmpty();
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ?"
                + " and action_type = 'supporting_document_removed'", instanceId)).isEqualTo(1);
    }
}
