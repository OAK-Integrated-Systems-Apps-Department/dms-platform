package com.oak.dms.relationship;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Create, inverse view, symmetric pairs, edit, remove, bulk, hidden targets and audit (R4/R5). */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipApiIT extends RelationshipApiSupport {

    private UUID tenantId;
    private User editor;
    private UUID contractType;
    private UUID a;
    private UUID b;
    private UUID c;

    @BeforeEach
    void world() {
        tenantId = tenantWithTypes();
        editor = fx.user(tenantId, "Editor");
        fx.role(tenantId, "Editor", new String[]{CREATE, REMOVE, VIEW}, editor);
        contractType = fx.documentType(tenantId, "Contract");
        a = fx.document(tenantId, contractType, editor, "Policy v2");
        b = fx.document(tenantId, contractType, editor, "Policy v1");
        c = fx.document(tenantId, contractType, editor, "Policy v0");
    }

    private JsonNode item(JsonNode panel, int group, int index) {
        return panel.get("groups").get(group).get("items").get(index);
    }

    @Test
    void create_showsOnBothDocuments_withTheInverseLabelOnTheTarget() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        JsonNode created = createLink(editor, supersedes, a, b, 201);
        assertThat(created.get("typeCode").asString()).isEqualTo("SUPERSEDES");
        assertThat(created.get("origin").asString()).isEqualTo("manual");

        JsonNode source = panel(editor, a);
        assertThat(source.get("total").asInt()).isEqualTo(1);
        assertThat(source.get("canAdd").asBoolean()).isTrue();
        assertThat(item(source, 0, 0).get("label").asString()).isEqualTo("Supersedes");
        assertThat(item(source, 0, 0).get("direction").asString()).isEqualTo("outgoing");
        assertThat(item(source, 0, 0).get("other").get("title").asString()).isEqualTo("Policy v1");
        assertThat(item(source, 0, 0).get("other").get("documentTypeName").asString()).isEqualTo("Contract");
        assertThat(item(source, 0, 0).get("other").get("status").asString()).isEqualTo("Draft");
        assertThat(item(source, 0, 0).get("canRemove").asBoolean()).isTrue();

        JsonNode target = panel(editor, b);
        assertThat(item(target, 0, 0).get("label").asString()).isEqualTo("Superseded by");
        assertThat(item(target, 0, 0).get("direction").asString()).isEqualTo("incoming");
        assertThat(item(target, 0, 0).get("other").get("title").asString()).isEqualTo("Policy v2");
        assertThat(item(target, 0, 0).get("canEdit").asBoolean()).as("edit access on the source (a) too").isTrue();

        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ?", tenantId)).isEqualTo(1);
        assertThat(auditCount(tenantId, "RELATIONSHIP_CREATED")).isEqualTo(1);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'RELATIONSHIP_CREATED'"
                + " and object_type = 'DOCUMENT_RELATIONSHIP' and new_value::text like '%SUPERSEDES%'"
                + " and new_value::text like '%Policy v2%' and new_value::text like '%Policy v1%'", tenantId)).isEqualTo(1);
    }

    @Test
    void manyLinks_ofTheSameAndOfDifferentTypes() throws Exception {
        createLink(editor, typeId(tenantId, "SUPERSEDES"), a, b, 201);
        createLink(editor, typeId(tenantId, "SUPERSEDES"), a, c, 201);
        createLink(editor, typeId(tenantId, "MAIN_ATTACHMENT"), a, b, 201);
        createLink(editor, typeId(tenantId, "REPORT_EVIDENCE"), b, a, 201);

        JsonNode p = panel(editor, a);
        assertThat(p.get("total").asInt()).isEqualTo(4);
        assertThat(p.get("groups")).hasSize(3);
        assertThat(p.get("groups").get(0).get("typeCode").asString()).as("ordered by display order").isEqualTo("MAIN_ATTACHMENT");
        assertThat(p.get("groups").get(1).get("typeCode").asString()).isEqualTo("SUPERSEDES");
        assertThat(p.get("groups").get(1).get("items")).hasSize(2);
    }

    @Test
    void symmetric_isOneRow_andTheReversedPairIsADuplicate() throws Exception {
        UUID related = typeId(tenantId, "RELATED");
        createLink(editor, related, b, a, 201);
        assertThat(createLink(editor, related, a, b, 409).get("code").asString()).isEqualTo("RELATIONSHIP_DUPLICATE");
        assertThat(createLink(editor, related, b, a, 409).get("code").asString()).isEqualTo("RELATIONSHIP_DUPLICATE");

        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ?", tenantId)).isEqualTo(1);
        // Stored lower id first, whichever way it was entered.
        UUID source = fx.jdbc().queryForObject("select source_document_id from document_relationships where tenant_id = ?",
                UUID.class, tenantId);
        UUID target = fx.jdbc().queryForObject("select target_document_id from document_relationships where tenant_id = ?",
                UUID.class, tenantId);
        assertThat(source.toString().compareTo(target.toString())).isNegative();

        for (UUID doc : List.of(a, b)) {
            JsonNode p = panel(editor, doc);
            assertThat(item(p, 0, 0).get("direction").asString()).isEqualTo("symmetric");
            assertThat(item(p, 0, 0).get("label").asString()).isEqualTo("Related document");
            assertThat(item(p, 0, 0).get("other").get("documentId").asString())
                    .isEqualTo((doc.equals(a) ? b : a).toString());
        }
    }

    @Test
    void rejects_selfLink_duplicate_disabledType_unknownType_template() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        assertThat(createLink(editor, supersedes, a, a, 422).get("code").asString()).isEqualTo("RELATIONSHIP_SELF_LINK");
        createLink(editor, supersedes, a, b, 201);
        assertThat(createLink(editor, supersedes, a, b, 409).get("code").asString()).isEqualTo("RELATIONSHIP_DUPLICATE");
        // The same pair under another type, or the other way round for a directional type, is fine.
        createLink(editor, typeId(tenantId, "REPLACES"), a, b, 201);
        createLink(editor, typeId(tenantId, "DUPLICATE_OF"), b, a, 201);
        createLink(editor, typeId(tenantId, "DUPLICATE_OF"), a, b, 201);

        fx.jdbc().update("update relationship_types set is_enabled = false where tenant_id = ? and code = 'CONTRACT_AMENDMENT'", tenantId);
        assertThat(createLink(editor, typeId(tenantId, "CONTRACT_AMENDMENT"), a, c, 409).get("code").asString())
                .isEqualTo("RELATIONSHIP_TYPE_DISABLED");
        createLink(editor, UUID.randomUUID(), a, c, 404);

        fx.jdbc().update("update documents set is_template = true where document_id = ?", c);
        assertThat(createLink(editor, typeId(tenantId, "RELATED"), a, c, 422).get("code").asString())
                .isEqualTo("RELATIONSHIP_TEMPLATE");
        assertThat(activeLinks(tenantId)).isEqualTo(4);
    }

    @Test
    void edit_changesNoteAndType_withTheSameChecks() throws Exception {
        UUID link = id(createLink(editor, typeId(tenantId, "SUPERSEDES"), a, b, 201), "relationshipId");
        createLink(editor, typeId(tenantId, "REPLACES"), a, b, 201);

        JsonNode noted = patchAs("/api/v1/document-relationships/" + link, editor, body("note", "Approved by Legal"), 200);
        assertThat(noted.get("note").asString()).isEqualTo("Approved by Legal");
        assertThat(item(panel(editor, a), 0, 0).get("note").asString()).isEqualTo("Approved by Legal");

        // Another type that already links the pair is a duplicate; a two-way type is a different direction.
        assertThat(patchAs("/api/v1/document-relationships/" + link, editor,
                body("relationshipTypeId", typeId(tenantId, "REPLACES")), 409).get("code").asString())
                .isEqualTo("RELATIONSHIP_DUPLICATE");
        assertThat(patchAs("/api/v1/document-relationships/" + link, editor,
                body("relationshipTypeId", typeId(tenantId, "RELATED")), 422).get("code").asString())
                .isEqualTo("RELATIONSHIP_TYPE_DIRECTION_MISMATCH");
        JsonNode retyped = patchAs("/api/v1/document-relationships/" + link, editor,
                body("relationshipTypeId", typeId(tenantId, "CONTRACT_AMENDMENT")), 200);
        assertThat(retyped.get("typeCode").asString()).isEqualTo("CONTRACT_AMENDMENT");

        assertThat(auditCount(tenantId, "RELATIONSHIP_UPDATED")).isEqualTo(2);
        patchAs("/api/v1/document-relationships/" + UUID.randomUUID(), editor, body("note", "x"), 404);
    }

    @Test
    void remove_takesTheLinkOffBothDocuments_butKeepsTheRowAndTheAudit() throws Exception {
        UUID link = id(createLink(editor, typeId(tenantId, "SUPERSEDES"), a, b, 201), "relationshipId");
        deleteAs("/api/v1/document-relationships/" + link, editor, 204);

        assertThat(panel(editor, a).get("total").asInt()).isZero();
        assertThat(panel(editor, b).get("total").asInt()).isZero();
        assertThat(fx.count("select count(*) from document_relationships where relationship_id = ? and removed_at is not null"
                + " and removed_reason = 'user_removed' and removed_by = ?", link, editor.getUserId())).isEqualTo(1);
        assertThat(auditCount(tenantId, "RELATIONSHIP_REMOVED")).isEqualTo(1);
        deleteAs("/api/v1/document-relationships/" + link, editor, 404);
        // The pair can be linked again.
        createLink(editor, typeId(tenantId, "SUPERSEDES"), a, b, 201);
    }

    @Test
    void bulk_createsIndividualRows_oneBatch_andIsAllOrNothing() throws Exception {
        UUID supersedes = typeId(tenantId, "REPORT_EVIDENCE");
        UUID d = fx.document(tenantId, contractType, editor, "Evidence 1");
        UUID e = fx.document(tenantId, contractType, editor, "Evidence 2");

        JsonNode ok = postJson("/api/v1/document-relationships/bulk", editor,
                body("relationshipTypeId", supersedes, "sourceDocumentIds", List.of(a), "targetDocumentIds", List.of(b, d, e)), 201);
        UUID batch = id(ok, "batchId");
        assertThat(ok.get("created")).hasSize(3);
        assertThat(fx.count("select count(*) from document_relationships where batch_id = ? and origin = 'bulk'", batch)).isEqualTo(3);
        assertThat(auditCount(tenantId, "RELATIONSHIP_CREATED")).isEqualTo(3);
        assertThat(auditCount(tenantId, "RELATIONSHIP_BATCH_CREATED")).isEqualTo(1);

        // Several sources to one target.
        UUID f = fx.document(tenantId, contractType, editor, "Report");
        JsonNode reverse = postJson("/api/v1/document-relationships/bulk", editor,
                body("relationshipTypeId", typeId(tenantId, "MAIN_ATTACHMENT"), "sourceDocumentIds", List.of(b, c, d),
                        "targetDocumentIds", List.of(f)), 201);
        assertThat(reverse.get("created")).hasSize(3);

        // One bad document sinks the lot: a duplicate and a document that doesn't exist, both named.
        UUID ghost = UUID.randomUUID();
        long before = activeLinks(tenantId);
        JsonNode bad = postJson("/api/v1/document-relationships/bulk", editor,
                body("relationshipTypeId", supersedes, "sourceDocumentIds", List.of(a), "targetDocumentIds", List.of(c, b, ghost)), 422);
        assertThat(bad.get("code").asString()).isEqualTo("RELATIONSHIP_BULK_INVALID");
        Map<String, String> codes = new java.util.HashMap<>();
        bad.get("details").forEach(x -> codes.put(x.get("documentId").asString(), x.get("code").asString()));
        assertThat(codes).containsEntry(b.toString(), "DUPLICATE").containsEntry(ghost.toString(), "NOT_FOUND_OR_NO_ACCESS")
                .doesNotContainKey(c.toString());
        assertThat(activeLinks(tenantId)).as("c was valid but nothing is saved").isEqualTo(before);

        // Several to several is not a request.
        postJson("/api/v1/document-relationships/bulk", editor, body("relationshipTypeId", supersedes,
                "sourceDocumentIds", List.of(a, b), "targetDocumentIds", List.of(c, d)), 422);
    }

    @Test
    void aTargetTheCallerCantOpen_isRefused_andShownAsLockedOnThePanel() throws Exception {
        User owner = fx.user(tenantId, "Owner");
        fx.role(tenantId, "Owner", new String[]{CREATE, REMOVE, VIEW}, owner);
        UUID secret = fx.document(tenantId, contractType, owner, "Salary review 2026");
        restrictTo(tenantId, secret, owner, "manage");

        // Creating a link to it looks like it doesn't exist; so does a bulk entry.
        createLink(editor, typeId(tenantId, "RELATED"), a, secret, 404);
        JsonNode bulk = postJson("/api/v1/document-relationships/bulk", editor, body("relationshipTypeId", typeId(tenantId, "RELATED"),
                "sourceDocumentIds", List.of(a), "targetDocumentIds", List.of(secret)), 422);
        assertThat(bulk.get("details").get(0).get("code").asString()).isEqualTo("NOT_FOUND_OR_NO_ACCESS");

        // The owner links their secret to a; the editor sees THAT a relationship exists, nothing about the secret.
        createLink(owner, typeId(tenantId, "SUPERSEDES"), secret, a, 201);
        JsonNode p = panel(editor, a);
        JsonNode row = item(p, 0, 0);
        assertThat(row.get("label").asString()).isEqualTo("Superseded by");
        assertThat(row.get("other").get("accessible").asBoolean()).isFalse();
        assertThat(row.get("other").size()).as("nothing but accessible:false").isEqualTo(1);
        assertThat(p.toString()).doesNotContain(secret.toString()).doesNotContain("Salary review").doesNotContain("Draft\"");
        assertThat(row.get("canEdit").asBoolean()).as("the secret is the source and the editor can't edit it").isFalse();
        assertThat(row.get("canRemove").asBoolean()).isFalse();
        deleteAs("/api/v1/document-relationships/" + id(row, "relationshipId"), editor, 404);
        assertThat(postJson("/api/v1/document-relationships/counts", editor, body("documentIds", List.of(a, secret)), 200)
                .get("counts").toString()).contains(a.toString()).doesNotContain(secret.toString());
    }

    @Test
    void counts_coverTheRequestedDocuments() throws Exception {
        createLink(editor, typeId(tenantId, "SUPERSEDES"), a, b, 201);
        createLink(editor, typeId(tenantId, "RELATED"), a, c, 201);
        JsonNode counts = postJson("/api/v1/document-relationships/counts", editor, body("documentIds", List.of(a, b, c)), 200)
                .get("counts");
        assertThat(counts.get(a.toString()).asInt()).isEqualTo(2);
        assertThat(counts.get(b.toString()).asInt()).isEqualTo(1);
        assertThat(counts.get(c.toString()).asInt()).isEqualTo(1);
    }

    @Test
    void aWorkflowRunShowsAsTheOtherDocumentsStatus() throws Exception {
        createLink(editor, typeId(tenantId, "SUPERSEDES"), a, b, 201);
        UUID run = UUID.randomUUID();
        UUID version = fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?", UUID.class, b);
        // A run in progress on b (its template needs no stages for this read-only check).
        UUID template = UUID.randomUUID();
        fx.jdbc().update("insert into workflow_templates (template_id, tenant_id, document_type_id, name, code, status)"
                + " values (?, ?, ?, 'T', 'T-" + template + "', 'active')", template, tenantId, contractType);
        fx.jdbc().update("insert into workflow_instances (instance_id, tenant_id, document_id, document_version_id, template_id,"
                + " template_version_number, status, initiated_by) values (?, ?, ?, ?, ?, 1, 'in_progress', ?)",
                run, tenantId, b, version, template, editor.getUserId());
        assertThat(item(panel(editor, a), 0, 0).get("other").get("status").asString()).isEqualTo("In approval");
    }
}
