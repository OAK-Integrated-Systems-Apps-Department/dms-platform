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

/** Loop prevention for Supersedes, Replaces and Parent -> Child (D4), and what the error may reveal (D5). */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipCycleIT extends RelationshipApiSupport {

    private UUID tenantId;
    private User editor;
    private UUID type;
    private UUID[] docs;

    @BeforeEach
    void world() {
        tenantId = tenantWithTypes();
        editor = fx.user(tenantId, "Editor");
        fx.role(tenantId, "Editor", new String[]{CREATE, REMOVE, VIEW}, editor);
        type = fx.documentType(tenantId, "Policy");
        docs = new UUID[8];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = fx.document(tenantId, type, editor, "Policy " + (char) ('A' + i));
        }
    }

    @Test
    void twoDocumentsCantSupersedeEachOther_andTheErrorNamesTheChain() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        createLink(editor, supersedes, docs[0], docs[1], 201);
        JsonNode error = createLink(editor, supersedes, docs[1], docs[0], 409);
        assertThat(error.get("code").asString()).isEqualTo("RELATIONSHIP_CYCLE");
        assertThat(error.get("message").asString()).contains("Policy B").contains("Policy A").contains("loop");
        JsonNode chain = error.get("details").get("chain");
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).get("documentId").asString()).isEqualTo(docs[0].toString());
        assertThat(activeLinks(tenantId)).isEqualTo(1);
    }

    @Test
    void longerLoops_areCaught_andTheChainListsEveryDocument() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        for (int i = 0; i < 4; i++) {
            createLink(editor, supersedes, docs[i], docs[i + 1], 201);
        }
        JsonNode error = createLink(editor, supersedes, docs[4], docs[0], 409);
        assertThat(error.get("details").get("chain")).hasSize(5);
        assertThat(error.get("message").asString()).contains("Policy A").contains("Policy E");
        // A shortcut that doesn't close a loop is fine.
        createLink(editor, supersedes, docs[0], docs[4], 201);
        // Joining a chain from outside is fine.
        createLink(editor, supersedes, docs[5], docs[0], 201);
        createLink(editor, supersedes, docs[4], docs[6], 201);
    }

    @Test
    void replacesAndParentChild_areCheckedToo_otherTypesAreNot() throws Exception {
        for (String code : List.of("REPLACES", "PARENT_CHILD")) {
            UUID t = typeId(tenantId, code);
            createLink(editor, t, docs[0], docs[1], 201);
            assertThat(createLink(editor, t, docs[1], docs[0], 409).get("code").asString()).isEqualTo("RELATIONSHIP_CYCLE");
        }
        // Duplicate of / Has attachment don't prevent loops (D4).
        UUID attachment = typeId(tenantId, "MAIN_ATTACHMENT");
        createLink(editor, attachment, docs[0], docs[1], 201);
        createLink(editor, attachment, docs[1], docs[0], 201);
        // Types are separate graphs: a Supersedes chain doesn't block a Replaces link the other way.
        createLink(editor, typeId(tenantId, "SUPERSEDES"), docs[2], docs[3], 201);
        createLink(editor, typeId(tenantId, "REPLACES"), docs[3], docs[2], 201);
    }

    @Test
    void removedLinksDontCount_andARemovedLoopCanBeClosedAgain() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        UUID first = id(createLink(editor, supersedes, docs[0], docs[1], 201), "relationshipId");
        createLink(editor, supersedes, docs[1], docs[0], 409);
        deleteAs("/api/v1/document-relationships/" + first, editor, 204);
        createLink(editor, supersedes, docs[1], docs[0], 201);
    }

    @Test
    void aBulkRequestCantCloseALoopWithinItself() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        createLink(editor, supersedes, docs[1], docs[2], 201);
        // docs[0] supersedes 1 and 2 is fine; docs[2] superseding... use anchor 1 -> {2 exists, 0}: 0 -> 1 -> 2 later closing 2 -> 0 is a loop.
        createLink(editor, supersedes, docs[0], docs[1], 201);
        JsonNode bad = postJson("/api/v1/document-relationships/bulk", editor, body("relationshipTypeId", supersedes,
                "sourceDocumentIds", List.of(docs[2]), "targetDocumentIds", List.of(docs[3], docs[0])), 422);
        assertThat(bad.get("details")).hasSize(1);
        assertThat(bad.get("details").get(0).get("code").asString()).isEqualTo("CYCLE");
        assertThat(bad.get("details").get(0).get("documentId").asString()).isEqualTo(docs[0].toString());
        assertThat(activeLinks(tenantId)).isEqualTo(2);
    }

    @Test
    void theChainNeverNamesADocumentTheCallerCantOpen() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        User owner = fx.user(tenantId, "Owner");
        fx.role(tenantId, "Owner", new String[]{CREATE, REMOVE, VIEW}, owner);
        UUID secret = fx.document(tenantId, type, owner, "Salary policy");
        restrictTo(tenantId, secret, owner, "manage");
        // docs[0] -> secret -> docs[1], made by the owner; the editor then tries docs[1] -> docs[0].
        createLink(owner, supersedes, docs[0], secret, 201);
        createLink(owner, supersedes, secret, docs[1], 201);

        JsonNode error = createLink(editor, supersedes, docs[1], docs[0], 409);
        assertThat(error.get("code").asString()).isEqualTo("RELATIONSHIP_CYCLE");
        String text = error.toString();
        assertThat(text).doesNotContain(secret.toString()).doesNotContain("Salary policy");
        assertThat(text).contains("A document you can't open");
        List<String> titles = new ArrayList<>();
        error.get("details").get("chain").forEach(c -> titles.add(c.get("title").asString()));
        assertThat(titles).contains("A document you can't open", "Policy A", "Policy B");
    }

    @Test
    void aChainLongerThanTheLimit_isRefusedRatherThanGuessed() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        int n = 55;
        UUID[] chain = new UUID[n];
        for (int i = 0; i < n; i++) {
            chain[i] = fx.document(tenantId, type, editor, "Long " + i);
        }
        for (int i = 0; i + 1 < n; i++) {
            fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id,"
                    + " target_document_id) values (?, ?, ?, ?)", tenantId, supersedes, chain[i], chain[i + 1]);
        }
        // The new link would close the loop only after 54 hops - past the 50-hop limit.
        JsonNode error = createLink(editor, supersedes, chain[n - 1], chain[0], 409);
        assertThat(error.get("code").asString()).isEqualTo("RELATIONSHIP_CHAIN_TOO_LONG");
        // A loop found within the limit is still reported as a loop.
        createLink(editor, supersedes, chain[10], chain[0], 409);
    }
}
