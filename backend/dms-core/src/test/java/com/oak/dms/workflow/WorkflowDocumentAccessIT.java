package com.oak.dms.workflow;

import com.oak.dms.user.User;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Workflow-scoped read access (3.1 Phase 2 against V53/V54): while an
 * approval is running, the people it is waiting on can open the document
 * under approval - read-only, and only until their part is over. Plus the
 * document-access rules the rest of the workflow now follows: submission,
 * comments, supporting documents, the people picker, templates and the
 * recycle bin.
 * <p>
 * The document is <em>restricted</em> to its owner alone, so nobody else can
 * open it except through the run.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowDocumentAccessIT extends WorkflowApiSupport {

    private UUID tenantId;
    private User admin;
    private User owner;
    private User approver;
    private User outsider;
    private UUID contractType;
    private UUID document;

    @BeforeEach
    void world() throws Exception {
        tenantId = fx.tenant();
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        approver = fx.user(tenantId, "Approver");
        outsider = fx.user(tenantId, "Outsider");

        fx.role(tenantId, "Owner " + UUID.randomUUID(), new String[]{
                WorkflowFixtures.SUBMIT_PERMISSION, "docm.documents.view", "docm.documents.delete",
                "docm.document-comments.create", "rbr.restore-deleted-documents"}, owner);
        List<String> approverPermissions = new ArrayList<>(List.of(WorkflowFixtures.APPROVER_PERMISSIONS));
        approverPermissions.addAll(List.of("docm.documents.view", "docm.documents.delete",
                "docm.document-comments.create", "wa.request-workflow-information.create"));
        fx.role(tenantId, "Approver " + UUID.randomUUID(), approverPermissions.toArray(String[]::new), approver);
        fx.role(tenantId, "Outsider " + UUID.randomUUID(),
                new String[]{"docm.documents.view", "docm.document-comments.create",
                        WorkflowFixtures.SUBMIT_PERMISSION}, outsider);

        contractType = fx.documentType(tenantId, "Contract");
        document = restricted(fx.document(tenantId, contractType, owner, "Supplier contract 2026-114"), owner);
        activeTemplate(admin, "CONTRACT", contractType, "flag_only", List.of(
                stageDef("Sign-off", "any", "halt_immediately", 24, entry("user", approver.getUserId()))));
    }

    /** Gives the document its own access list (V54) naming only {@code manager}. */
    private UUID restricted(UUID documentId, User manager) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", documentId);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id,"
                + " access_level) values (?, ?, 'user', ?, 'manage')", tenantId, documentId, manager.getUserId());
        return documentId;
    }

    private UUID openTask(JsonNode submitted) {
        return taskId(submitted, 1, approver.getDisplayName());
    }

    /** The documents the browser shows this user across every folder. */
    private List<UUID> browse(User as) throws Exception {
        List<UUID> ids = new ArrayList<>();
        get200("/api/v1/documents?all=true&size=50", as).get("content")
                .forEach(d -> ids.add(UUID.fromString(d.get("documentId").asString())));
        return ids;
    }

    /** One browser row, which carries what this user may do with the document. */
    private JsonNode row(User as, UUID documentId) throws Exception {
        for (JsonNode d : get200("/api/v1/documents?all=true&size=50", as).get("content")) {
            if (documentId.toString().equals(d.get("documentId").asString())) {
                return d;
            }
        }
        throw new AssertionError("No browser row for " + documentId);
    }

    // ------------------------------------------------------------ open tasks

    @Test
    void anApproverHoldingAnOpenTask_canReadTheDocument_untilTheyDecide() throws Exception {
        call(get("/api/v1/documents/" + document), fx.jwtFor(approver), null, 404);
        assertThat(browse(approver)).doesNotContain(document);

        JsonNode submitted = submit(owner, document, 201);

        assertThat(id(call(get("/api/v1/documents/" + document), fx.jwtFor(approver), null, 200), "documentId"))
                .isEqualTo(document);
        // Every other document endpoint goes through the same check (DocumentServiceImpl#findVisible);
        // the browser lists it too, so it can actually be found.
        assertThat(browse(approver)).contains(document);
        assertThat(row(approver, document).get("myAccess").asString()).as("read-only").isEqualTo("view");
        // view is not edit: it can be read, never changed or deleted.
        assertThat(call(delete("/api/v1/documents/" + document), fx.jwtFor(approver), null, 403)
                .get("code").asString()).isEqualTo("DOCUMENT_ACCESS_DENIED");

        approve(approver, openTask(submitted), 200);

        call(get("/api/v1/documents/" + document), fx.jwtFor(approver), null, 404);
        assertThat(browse(approver)).doesNotContain(document);
    }

    @Test
    void theInitiatorKeepsReadingTheirOwnSubmission_evenAfterLosingAccess_untilTheRunEnds() throws Exception {
        JsonNode submitted = submit(owner, document, 201);
        UUID instanceId = id(submitted, "instanceId");
        // Somebody takes the owner off the document's list while it is in approval.
        fx.jdbc().update("delete from document_access where document_id = ? and principal_id = ?",
                document, owner.getUserId());

        assertThat(id(call(get("/api/v1/documents/" + document), fx.jwtFor(owner), null, 200), "documentId"))
                .isEqualTo(document);
        assertThat(browse(owner)).contains(document);

        postAs("/api/v1/workflow/instances/" + instanceId + "/cancel", owner, Map.of("reason", "Withdrawn"), 200);

        call(get("/api/v1/documents/" + document), fx.jwtFor(owner), null, 404);
    }

    @Test
    void someoneAskedForInformation_canReadTheDocument_untilTheyAnswer() throws Exception {
        JsonNode submitted = submit(owner, document, 201);
        call(get("/api/v1/documents/" + document), fx.jwtFor(outsider), null, 404);

        JsonNode request = postAs("/api/v1/workflow/tasks/" + openTask(submitted) + "/info-requests", approver,
                Map.of("requestedFromUserId", outsider.getUserId(), "message", "Which supplier is this?"), 201);

        call(get("/api/v1/documents/" + document), fx.jwtFor(outsider), null, 200);
        assertThat(browse(outsider)).contains(document);

        postAs("/api/v1/workflow/info-requests/" + id(request, "infoRequestId") + "/respond", outsider,
                Map.of("message", "Acme Ltd"), 200);

        call(get("/api/v1/documents/" + document), fx.jwtFor(outsider), null, 404);
    }

    @Test
    void peopleWithNoPartInTheRunNeverSeeTheDocument() throws Exception {
        submit(owner, document, 201);
        call(get("/api/v1/documents/" + document), fx.jwtFor(outsider), null, 404);
        assertThat(browse(outsider)).doesNotContain(document);
    }

    // ----------------------------------------------------------- submission

    @Test
    void submittingADocumentYouCannotOpen_is404() throws Exception {
        submit(outsider, document, 404);
        assertThat(fx.count("select count(*) from workflow_instances where document_id = ?", document)).isZero();
    }

    @Test
    void aDocumentTemplateCannotBeSubmitted() throws Exception {
        UUID template = fx.document(tenantId, contractType, owner, "Contract template");
        fx.jdbc().update("update documents set is_template = true where document_id = ?", template);
        assertThat(submit(owner, template, 422).get("code").asString()).isEqualTo("WORKFLOW_DOCUMENT_IS_TEMPLATE");
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    void aDocumentInAnApprovalCannotGoToTheRecycleBin_untilTheRunEnds() throws Exception {
        JsonNode submitted = submit(owner, document, 201);
        JsonNode refused = call(delete("/api/v1/documents/" + document), fx.jwtFor(owner), null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_IN_APPROVAL");
        assertThat(refused.get("message").asString())
                .isEqualTo("This document is in an approval. Cancel the approval first.");

        postAs("/api/v1/workflow/instances/" + id(submitted, "instanceId") + "/cancel", owner,
                Map.of("reason", "Withdrawn"), 200);
        call(delete("/api/v1/documents/" + document), fx.jwtFor(owner), null, 204);
        // Restoring is unaffected.
        call(post("/api/v1/recycle-bin/" + document + "/restore"), fx.jwtFor(owner), null, 200);
    }

    // -------------------------------------------------------------- comments

    @Test
    void commentsFollowDocumentAccess_withTheMentionException() throws Exception {
        call(get("/api/v1/documents/" + document + "/comments"), fx.jwtFor(outsider), null, 404);
        call(post("/api/v1/documents/" + document + "/comments"), fx.jwtFor(outsider),
                Map.of("body", "Can I see this?"), 404);

        UUID comment = id(postAs("/api/v1/documents/" + document + "/comments", owner,
                Map.of("body", "cc @[" + outsider.getDisplayName() + "](user:" + outsider.getUserId() + ")"), 201),
                "commentId");
        // They hold docm.documents.view but not this document - the mention records that.
        assertThat(fx.count("select count(*) from comment_mentions where comment_id = ? and mentioned_user_id = ?"
                + " and had_document_access = false", comment, outsider.getUserId())).isEqualTo(1);
        // ... and they still read the one comment they were named in.
        assertThat(get200("/api/v1/comments/" + comment, outsider).get("root").get("commentId").asString())
                .isEqualTo(comment.toString());
        assertThat(get200("/api/v1/mentions/my", outsider).get("content").get(0).get("canOpenDocument").asBoolean())
                .isFalse();

        // An approver holding an open task may read and write the run's comments.
        submit(owner, document, 201);
        call(get("/api/v1/documents/" + document + "/comments"), fx.jwtFor(approver), null, 200);
        postAs("/api/v1/documents/" + document + "/comments", approver, Map.of("body", "Looks right to me"), 201);
    }

    // --------------------------------------------------- supporting documents

    @Test
    void supportingDocumentsFollowDocumentAccess_forTheSenderAndForEachViewer() throws Exception {
        UUID secret = restricted(fx.document(tenantId, contractType, owner, "Price list (owner only)"), owner);
        JsonNode submitted = submit(owner, document, 201);
        UUID instanceId = id(submitted, "instanceId");

        // The approver can't open the price list, so they can't attach it either.
        assertThat(postAs("/api/v1/workflow/instances/" + instanceId + "/supporting-documents", approver,
                Map.of("documentId", secret, "source", "linked_existing"), 422).get("code").asString())
                .isEqualTo("WORKFLOW_SUPPORTING_DOCUMENT_INVALID");

        // The owner can, and the approver then sees it listed but cannot open it: workflow-scoped read
        // covers only the document under approval, not its supporting documents (decision 18).
        postAs("/api/v1/workflow/instances/" + instanceId + "/supporting-documents", owner,
                Map.of("documentId", secret, "source", "linked_existing"), 201);
        JsonNode mine = get200("/api/v1/workflow/instances/" + instanceId + "/supporting-documents", owner);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("canOpen").asBoolean()).isTrue();
        // Visible to its owner, the file fields are handed out so the shared viewer can render it.
        assertThat(mine.get(0).get("fileName").asString()).isEqualTo("contract.pdf");

        JsonNode theirs = get200("/api/v1/workflow/instances/" + instanceId + "/supporting-documents", approver);
        assertThat(theirs.get(0).get("title").asString()).isEqualTo("Price list (owner only)");
        assertThat(theirs.get(0).get("canOpen").asBoolean()).isFalse();
        // No access, no file fields - the UI shows the title and note alone, no viewer.
        assertThat(theirs.get(0).get("fileName").isNull()).isTrue();
        call(get("/api/v1/documents/" + secret), fx.jwtFor(approver), null, 404);
    }

    // ----------------------------------------------- list badges and dashboard

    @Test
    void theDocumentStatusBatchOnlyAnswersForDocumentsTheCallerCanOpen() throws Exception {
        UUID other = fx.document(tenantId, contractType, owner, "Open contract");
        JsonNode submitted = submit(owner, document, 201);

        JsonNode mine = get200("/api/v1/workflow/document-status?documentIds=" + document + "," + other, owner);
        assertThat(mine).hasSize(1);
        assertThat(id(mine.get(0), "documentId")).isEqualTo(document);
        assertThat(id(mine.get(0), "instanceId")).isEqualTo(id(submitted, "instanceId"));
        assertThat(mine.get(0).get("status").asString()).isEqualTo("in_progress");

        // The outsider can open neither the restricted document nor its run, so they get nothing.
        assertThat(get200("/api/v1/workflow/document-status?documentIds=" + document, outsider)).isEmpty();
        // The approver holds its open task, so the badge is theirs to see.
        assertThat(get200("/api/v1/workflow/document-status?documentIds=" + document, approver)).hasSize(1);

        approve(approver, openTask(submitted), 200);
        assertThat(get200("/api/v1/workflow/document-status?documentIds=" + document, owner).get(0)
                .get("status").asString()).isEqualTo("approved");
    }

    @Test
    void dashboardActivityShowsApprovalsOnlyToTheirParticipants() throws Exception {
        JsonNode submitted = submit(owner, document, 201);

        assertThat(activityActions(owner)).contains("WORKFLOW_SUBMITTED");
        assertThat(activityActions(approver)).contains("WORKFLOW_TASK_ASSIGNED");
        assertThat(activityActions(outsider))
                .doesNotContain("WORKFLOW_SUBMITTED", "WORKFLOW_TASK_ASSIGNED", "WORKFLOW_APPROVED");

        approve(approver, openTask(submitted), 200);
        assertThat(activityActions(owner)).contains("WORKFLOW_APPROVED");
        assertThat(activityActions(approver)).contains("WORKFLOW_APPROVED");
        assertThat(activityActions(outsider)).doesNotContain("WORKFLOW_APPROVED");
    }

    private List<String> activityActions(User as) throws Exception {
        List<String> actions = new ArrayList<>();
        get200("/api/v1/dashboard", as).get("activity").forEach(e -> actions.add(e.get("action").asString()));
        return actions;
    }

    // ---------------------------------------------------------- people picker

    @Test
    void peopleLookupAsksAboutTheNamedDocument_notJustThePermission() throws Exception {
        JsonNode anyone = get200("/api/v1/people/lookup?search=" + outsider.getFirstName(), owner);
        assertThat(anyone).hasSize(1);
        assertThat(anyone.get(0).get("hasDocumentAccess").asBoolean()).as("holds docm.documents.view").isTrue();

        JsonNode forDocument = get200("/api/v1/people/lookup?documentId=" + document + "&search="
                + outsider.getFirstName(), owner);
        assertThat(forDocument.get(0).get("hasDocumentAccess").asBoolean()).isFalse();

        // Once they are asked for information on the run, they can open it - and the picker says so.
        JsonNode submitted = submit(owner, document, 201);
        postAs("/api/v1/workflow/tasks/" + openTask(submitted) + "/info-requests", approver,
                Map.of("requestedFromUserId", outsider.getUserId(), "message", "Which supplier is this?"), 201);
        assertThat(get200("/api/v1/people/lookup?documentId=" + document + "&search=" + outsider.getFirstName(), owner)
                .get(0).get("hasDocumentAccess").asBoolean()).isTrue();
    }
}
