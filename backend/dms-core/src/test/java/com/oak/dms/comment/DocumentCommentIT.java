package com.oak.dms.comment;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Threaded document comments (Phase 2 S5, decision 9). */
@SpringBootTest
@Import(WorkflowFixtures.class)
class DocumentCommentIT extends WorkflowApiSupport {

    static final String[] COMMENTER = {"docm.documents.view", "docm.document-comments.create"};

    private ContractWorld w;
    private User viewer;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
        fx.role(w.tenantId(), "Commenter " + UUID.randomUUID(), COMMENTER, w.chamath(), w.dilani());
        viewer = fx.user(w.tenantId(), "Viewer");
        fx.role(w.tenantId(), "Viewer " + UUID.randomUUID(), new String[]{"docm.documents.view"}, viewer);
    }

    private String commentsPath(UUID documentId) {
        return "/api/v1/documents/" + documentId + "/comments";
    }

    private JsonNode comment(User as, UUID documentId, String body, UUID parent, UUID instance, int expected)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("body", body);
        request.put("parentCommentId", parent);
        request.put("workflowInstanceId", instance);
        return postAs(commentsPath(documentId), as, request, expected);
    }

    @Test
    void threadsAreTwoLevels_andListOldestFirst() throws Exception {
        UUID root = id(comment(w.chamath(), w.contractDocumentId(), "Is clause 4 final?", null, null, 201), "commentId");
        UUID reply = id(comment(w.dilani(), w.contractDocumentId(), "Yes", root, null, 201), "commentId");
        JsonNode nested = comment(w.chamath(), w.contractDocumentId(), "Thanks", reply, null, 201);
        assertThat(nested.get("parentCommentId").asString()).as("a reply to a reply joins the root")
                .isEqualTo(root.toString());
        UUID second = id(comment(w.dilani(), w.contractDocumentId(), "Another topic", null, null, 201), "commentId");

        JsonNode page = get200(commentsPath(w.contractDocumentId()), viewer);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        JsonNode first = page.get("content").get(0);
        assertThat(first.get("root").get("commentId").asString()).isEqualTo(root.toString());
        assertThat(first.get("root").get("author").get("displayName").asString())
                .isEqualTo(w.chamath().getDisplayName());
        assertThat(first.get("replies")).hasSize(2);
        assertThat(first.get("replies").get(0).get("body").asString()).isEqualTo("Yes");
        assertThat(page.get("content").get(1).get("root").get("commentId").asString()).isEqualTo(second.toString());
    }

    @Test
    void permissions_andTenantIsolation() throws Exception {
        comment(w.chamath(), w.contractDocumentId(), "Hello", null, null, 201);
        // Saman approves but has no docm.documents.view.
        call(get(commentsPath(w.contractDocumentId())), fx.jwtFor(w.saman()), null, 403);
        // View only: may read, not write.
        get200(commentsPath(w.contractDocumentId()), viewer);
        comment(viewer, w.contractDocumentId(), "Me too", null, null, 403);
        comment(w.chamath(), w.contractDocumentId(), "   ", null, null, 400);

        ContractWorld other = ContractWorld.create(fx);
        fx.role(other.tenantId(), "Commenter " + UUID.randomUUID(), COMMENTER, other.chamath());
        call(get(commentsPath(w.contractDocumentId())), fx.jwtFor(other.chamath()), null, 404);
        comment(other.chamath(), w.contractDocumentId(), "x", null, null, 404);
    }

    @Test
    void instanceScopedComments_recordTheTimeline_andMustBelongToTheDocument() throws Exception {
        activeTemplate(w.admin(), "CMT", w.contractTypeId(), "flag_only", List.of(
                stageDef("Sign-off", "any", "halt_immediately", 24, entry("user", w.nimal().getUserId()))));
        UUID instanceId = id(submit(w.chamath(), w.contractDocumentId(), 201), "instanceId");
        UUID otherDocument = fx.document(w.tenantId(), w.contractTypeId(), w.chamath(), "Other");

        assertThat(comment(w.chamath(), otherDocument, "x", null, instanceId, 422).get("code").asString())
                .isEqualTo("COMMENT_INSTANCE_MISMATCH");
        UUID scoped = id(comment(w.chamath(), w.contractDocumentId(), "Please prioritise", null, instanceId, 201),
                "commentId");
        comment(w.chamath(), w.contractDocumentId(), "General note", null, null, 201);
        JsonNode reply = comment(w.dilani(), w.contractDocumentId(), "Noted", scoped, null, 201);
        assertThat(reply.get("workflowInstanceId").asString()).as("a reply inherits the thread's workflow")
                .isEqualTo(instanceId.toString());

        JsonNode filtered = get200(commentsPath(w.contractDocumentId()) + "?workflowInstanceId=" + instanceId, viewer);
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_actions where instance_id = ? and action_type = 'commented'",
                instanceId)).isEqualTo(2);
        // The audit row carries ids and length, never the text.
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'DOCUMENT_COMMENT_CREATED'"
                + " and new_value::text like '%prioritise%'", w.tenantId())).isZero();
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'DOCUMENT_COMMENT_CREATED'",
                w.tenantId())).isEqualTo(3);
    }

    @Test
    void onlyTheAuthorEdits_andDeleteKeepsTheReplies() throws Exception {
        UUID root = id(comment(w.chamath(), w.contractDocumentId(), "Draft wording", null, null, 201), "commentId");
        comment(w.dilani(), w.contractDocumentId(), "Looks fine", root, null, 201);

        assertThat(call(put("/api/v1/comments/" + root), fx.jwtFor(w.dilani()), Map.of("body", "hijack"), 403)
                .get("code").asString()).isEqualTo("COMMENT_NOT_AUTHOR");
        JsonNode edited = call(put("/api/v1/comments/" + root), fx.jwtFor(w.chamath()), Map.of("body", "Final wording"), 200);
        assertThat(edited.get("body").asString()).isEqualTo("Final wording");
        assertThat(edited.get("editedAt").isNull()).isFalse();

        call(delete("/api/v1/comments/" + root), fx.jwtFor(w.dilani()), null, 403);
        call(delete("/api/v1/comments/" + root), fx.jwtFor(w.chamath()), null, 204);
        JsonNode thread = get200(commentsPath(w.contractDocumentId()), viewer).get("content").get(0);
        assertThat(thread.get("root").get("deleted").asBoolean()).isTrue();
        assertThat(thread.get("root").get("body").asString()).isEqualTo("[deleted]");
        assertThat(thread.get("replies")).hasSize(1);

        UUID lonely = id(comment(w.dilani(), w.contractDocumentId(), "Off topic", null, null, 201), "commentId");
        call(delete("/api/v1/comments/" + lonely), fx.tenantAdminJwtFor(w.admin()), null, 204);
        assertThat(get200(commentsPath(w.contractDocumentId()), viewer).get("totalElements").asLong())
                .as("a deleted root without replies disappears").isEqualTo(1);
    }
}
