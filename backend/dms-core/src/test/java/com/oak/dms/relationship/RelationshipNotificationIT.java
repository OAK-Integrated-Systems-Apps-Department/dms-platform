package com.oak.dms.relationship;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** "Document linked" notification (R10): who gets it, what it may say, and the bulk rule. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipNotificationIT extends RelationshipApiSupport {

    private UUID tenantId;
    private User actor;
    private User owner;
    private UUID contractType;
    private UUID mine;
    private UUID theirs;

    @BeforeEach
    void world() {
        tenantId = tenantWithTypes();
        actor = fx.user(tenantId, "Actor");
        owner = fx.user(tenantId, "Owner");
        fx.role(tenantId, "Actor", new String[]{CREATE, REMOVE, VIEW}, actor);
        fx.role(tenantId, "Owner", new String[]{VIEW}, owner);
        contractType = fx.documentType(tenantId, "Contract");
        mine = fx.document(tenantId, contractType, actor, "Actor's report");
        theirs = fx.document(tenantId, contractType, owner, "Owner's contract");
    }

    private List<Map<String, Object>> notifications(User to) {
        return fx.jdbc().queryForList("select title, body, link_url, category_code, tier from notifications"
                + " where tenant_id = ? and recipient_user_id = ? and category_code = 'document.relationship-added'",
                tenantId, to.getUserId());
    }

    @Test
    void theOwnerOfTheDocumentLinkedTo_isToldByName_andTheActorIsNot() throws Exception {
        createLink(actor, typeId(tenantId, "SUPERSEDES"), mine, theirs, 201);

        List<Map<String, Object>> told = notifications(owner);
        assertThat(told).hasSize(1);
        assertThat(told.get(0).get("title").toString()).isEqualTo("Actor linked a document to \"Owner's contract\"");
        assertThat(told.get(0).get("body").toString()).contains("Superseded by");
        assertThat(told.get(0).get("link_url").toString()).isEqualTo("/documents/" + theirs + "#relationships");
        assertThat(told.get(0).get("tier")).isEqualTo("optional");
        // Never names the document that was linked.
        assertThat(told.toString()).doesNotContain("Actor's report");
        assertThat(notifications(actor)).isEmpty();
    }

    @Test
    void linkingToYourOwnDocument_tellsNobody() throws Exception {
        UUID other = fx.document(tenantId, contractType, actor, "Another of mine");
        createLink(actor, typeId(tenantId, "RELATED"), mine, other, 201);
        assertThat(notifications(actor)).isEmpty();
    }

    @Test
    void anOwnerWhoCantOpenTheirDocument_getsNoTitleAndNoLink() throws Exception {
        // Their own document is locked to someone else.
        restrictTo(tenantId, theirs, actor, "manage");
        createLink(actor, typeId(tenantId, "RELATED"), mine, theirs, 201);

        List<Map<String, Object>> told = notifications(owner);
        assertThat(told).hasSize(1);
        assertThat(told.get(0).get("title")).isEqualTo("A document was linked to a document you own");
        assertThat(told.toString()).doesNotContain("Owner's contract").doesNotContain("Actor's report");
        assertThat(told.get(0).get("link_url")).isNull();
    }

    @Test
    void aBulkRequest_sendsOneNotificationPerDocumentLinkedTo() throws Exception {
        UUID s1 = fx.document(tenantId, contractType, actor, "S1");
        UUID s2 = fx.document(tenantId, contractType, actor, "S2");
        UUID s3 = fx.document(tenantId, contractType, actor, "S3");
        postJson("/api/v1/document-relationships/bulk", actor, body("relationshipTypeId", typeId(tenantId, "MAIN_ATTACHMENT"),
                "sourceDocumentIds", List.of(s1, s2, s3), "targetDocumentIds", List.of(theirs)), 201);
        assertThat(notifications(owner)).hasSize(1);

        // One document linked to several owned by the same person: one each.
        UUID t2 = fx.document(tenantId, contractType, owner, "Owner's second");
        postJson("/api/v1/document-relationships/bulk", actor, body("relationshipTypeId", typeId(tenantId, "REPORT_EVIDENCE"),
                "sourceDocumentIds", List.of(mine), "targetDocumentIds", List.of(theirs, t2)), 201);
        assertThat(notifications(owner)).hasSize(3);
    }
}
