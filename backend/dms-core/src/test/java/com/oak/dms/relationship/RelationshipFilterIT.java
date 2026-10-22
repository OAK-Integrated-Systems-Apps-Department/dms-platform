package com.oak.dms.relationship;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Browsing by relationship (R8): "documents that supersede this policy", with access filtering kept. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipFilterIT extends RelationshipApiSupport {

    private UUID tenantId;
    private User editor;
    private User owner;
    private User admin;
    private UUID policy;
    private UUID v2;
    private UUID secretV3;
    private UUID unrelated;
    private UUID v4;

    @BeforeEach
    void world() throws Exception {
        tenantId = tenantWithTypes();
        editor = fx.user(tenantId, "Editor");
        owner = fx.user(tenantId, "Owner");
        admin = fx.user(tenantId, "Admin");
        fx.role(tenantId, "Editor", new String[]{CREATE, REMOVE, VIEW}, editor);
        fx.role(tenantId, "Owner", new String[]{CREATE, REMOVE, VIEW}, owner);
        UUID type = fx.documentType(tenantId, "Policy");
        policy = fx.document(tenantId, type, editor, "Travel policy");
        v2 = fx.document(tenantId, type, editor, "Travel policy 2026");
        secretV3 = fx.document(tenantId, type, owner, "Travel policy draft (private)");
        restrictTo(tenantId, secretV3, owner, "manage");
        unrelated = fx.document(tenantId, type, editor, "Canteen menu");
        v4 = fx.document(tenantId, type, editor, "Travel policy 2027");

        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        createLink(editor, supersedes, v2, policy, 201);      // v2 supersedes policy
        createLink(owner, supersedes, secretV3, policy, 201); // a private draft supersedes it too
        createLink(editor, supersedes, v4, v2, 201);          // v4 supersedes v2
        createLink(editor, typeId(tenantId, "RELATED"), unrelated, v4, 201);
    }

    private List<UUID> browse(User as, String query) throws Exception {
        List<UUID> ids = new ArrayList<>();
        call(get("/api/v1/documents?all=true&size=50" + query), fx.jwtFor(as), null, 200).get("content")
                .forEach(d -> ids.add(UUID.fromString(d.get("documentId").asString())));
        return ids;
    }

    private List<UUID> browseAsAdmin(String query) throws Exception {
        List<UUID> ids = new ArrayList<>();
        call(get("/api/v1/documents?all=true&size=50" + query), fx.tenantAdminJwtFor(admin), null, 200).get("content")
                .forEach(d -> ids.add(UUID.fromString(d.get("documentId").asString())));
        return ids;
    }

    @Test
    void documentsThatSupersedeThis_areTheSourcesOfItsSupersedesLinks() throws Exception {
        String q = "&relatedTo=" + policy + "&relationshipType=SUPERSEDES&direction=source";
        // The private draft supersedes it too, but the editor can't open it: never listed.
        assertThat(browse(editor, q)).containsExactly(v2);
        // The tenant admin (who sees everything, so the other query path) sees both.
        assertThat(browseAsAdmin(q)).containsExactlyInAnyOrder(v2, secretV3);
        // The owner can open the draft.
        assertThat(browse(owner, q)).containsExactlyInAnyOrder(v2, secretV3);
    }

    @Test
    void direction_target_andAny() throws Exception {
        assertThat(browse(editor, "&relatedTo=" + v2 + "&relationshipType=SUPERSEDES&direction=target")).containsExactly(policy);
        assertThat(browse(editor, "&relatedTo=" + v2 + "&relationshipType=SUPERSEDES&direction=source")).containsExactly(v4);
        assertThat(browse(editor, "&relatedTo=" + v2 + "&relationshipType=SUPERSEDES")).containsExactlyInAnyOrder(policy, v4);
        assertThat(browse(editor, "&relatedTo=" + v2 + "&relationshipType=SUPERSEDES&direction=any")).containsExactlyInAnyOrder(policy, v4);
        // Any type: v2's links are only Supersedes.
        assertThat(browse(editor, "&relatedTo=" + v2)).containsExactlyInAnyOrder(policy, v4);
    }

    @Test
    void aTypeAlone_meansAnyDocumentWithSuchALink() throws Exception {
        assertThat(browse(editor, "&relationshipType=SUPERSEDES&direction=source")).containsExactlyInAnyOrder(v2, v4);
        assertThat(browse(editor, "&relationshipType=SUPERSEDES&direction=target")).containsExactlyInAnyOrder(policy, v2);
        assertThat(browse(editor, "&relationshipType=SUPERSEDES")).containsExactlyInAnyOrder(policy, v2, v4);
        assertThat(browse(editor, "&relationshipType=replaces")).as("a type with no links, code in any case").isEmpty();
    }

    @Test
    void aTwoWayType_matchesEitherSide_whateverTheDirection() throws Exception {
        assertThat(browse(editor, "&relatedTo=" + v4 + "&relationshipType=RELATED&direction=source")).containsExactly(unrelated);
        assertThat(browse(editor, "&relatedTo=" + unrelated + "&relationshipType=RELATED&direction=target")).containsExactly(v4);
    }

    @Test
    void combinesWithTheOtherFilters_andIgnoresRemovedLinks() throws Exception {
        String q = "&relatedTo=" + v2 + "&relationshipType=SUPERSEDES";
        assertThat(browse(editor, q + "&q=2027")).containsExactly(v4);
        assertThat(browse(editor, q + "&excludeDocumentId=" + v4)).containsExactly(policy);
        assertThat(browse(editor, q + "&q=nothing-matches")).isEmpty();

        UUID link = fx.jdbc().queryForObject("select relationship_id from document_relationships where tenant_id = ?"
                + " and source_document_id = ? and target_document_id = ?", UUID.class, tenantId, v4, v2);
        deleteAs("/api/v1/document-relationships/" + link, editor, 204);
        assertThat(browse(editor, q)).containsExactly(policy);
    }

    @Test
    void theRelatedDocumentMustBeOneTheCallerCanOpen_andTheParametersMustBeValid() throws Exception {
        call(get("/api/v1/documents?all=true&relatedTo=" + secretV3), fx.jwtFor(editor), null, 404);
        call(get("/api/v1/documents?all=true&relatedTo=" + UUID.randomUUID()), fx.jwtFor(editor), null, 404);
        call(get("/api/v1/documents?all=true&relationshipType=NO_SUCH_TYPE"), fx.jwtFor(editor), null, 404);
        assertThat(call(get("/api/v1/documents?all=true&relatedTo=" + policy + "&direction=sideways"), fx.jwtFor(editor), null, 422)
                .get("code").asString()).isEqualTo("RELATIONSHIP_DIRECTION_INVALID");

        // Another tenant's admin can't use this tenant's document id, nor see its links.
        UUID other = tenantWithTypes();
        User otherAdmin = fx.user(other, "OtherAdmin");
        call(get("/api/v1/documents?all=true&relatedTo=" + policy), fx.tenantAdminJwtFor(otherAdmin), null, 404);
    }

    @Test
    void withoutTheNewParameters_browsingIsAsBefore() throws Exception {
        List<UUID> all = browse(editor, "");
        assertThat(all).contains(policy, v2, unrelated, v4).doesNotContain(secretV3);
        assertThat(browseAsAdmin("")).contains(secretV3);
    }
}
