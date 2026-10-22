package com.oak.dms.relationship;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Who may do what (R11, D1): the DRM permission AND the right access on the documents, every
 * endpoint, including the hidden-document cases - and that another tenant gets 404 everywhere (D9).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipPermissionMatrixIT extends RelationshipApiSupport {

    private UUID tenantId;
    private User full;      // create + remove + view
    private User creator;   // create + view
    private User remover;   // remove + view
    private User viewer;    // view only
    private User nobody;    // no permissions
    private User admin;     // tenant admin
    private UUID supersedes;
    private UUID related;
    private UUID a;
    private UUID b;
    private UUID c;
    private UUID viewOnly;  // full may open it but not change it
    private UUID hidden;    // full may not open it

    @BeforeEach
    void world() {
        tenantId = tenantWithTypes();
        full = fx.user(tenantId, "Full");
        creator = fx.user(tenantId, "Creator");
        remover = fx.user(tenantId, "Remover");
        viewer = fx.user(tenantId, "Viewer");
        nobody = fx.user(tenantId, "Nobody");
        admin = fx.user(tenantId, "Admin");
        fx.role(tenantId, "Full", new String[]{CREATE, REMOVE, VIEW}, full);
        fx.role(tenantId, "Creator", new String[]{CREATE, VIEW}, creator);
        fx.role(tenantId, "Remover", new String[]{REMOVE, VIEW}, remover);
        fx.role(tenantId, "Viewer", new String[]{VIEW}, viewer);
        UUID type = fx.documentType(tenantId, "Contract");
        a = fx.document(tenantId, type, full, "A");
        b = fx.document(tenantId, type, full, "B");
        c = fx.document(tenantId, type, full, "C");
        User someoneElse = fx.user(tenantId, "SomeoneElse");
        viewOnly = fx.document(tenantId, type, someoneElse, "View-only for Full");
        restrictTo(tenantId, viewOnly, full, "view");
        hidden = fx.document(tenantId, type, someoneElse, "Hidden from Full");
        restrictTo(tenantId, hidden, someoneElse, "manage");
        supersedes = typeId(tenantId, "SUPERSEDES");
        related = typeId(tenantId, "RELATED");
    }

    private JsonNode create(User as, UUID type, UUID source, UUID target, int expected) throws Exception {
        return createLink(as, type, source, target, expected);
    }

    private UUID existingLink(UUID source, UUID target) {
        return fx.jdbc().queryForObject("insert into document_relationships (tenant_id, relationship_type_id, source_document_id,"
                + " target_document_id, created_by) values (?, ?, ?, ?, ?) returning relationship_id", UUID.class, tenantId,
                supersedes, source, target, full.getUserId());
    }

    @Test
    void create_needsTheCreatePermission_andEditOnTheSource_andViewOnTheTarget() throws Exception {
        create(full, related, a, b, 201);
        create(creator, supersedes, a, c, 201);
        create(remover, supersedes, b, c, 403);
        create(viewer, supersedes, b, c, 403);
        create(nobody, supersedes, b, c, 403);
    }

    @Test
    void create_accessOnTheDocuments() throws Exception {
        // Source only viewable: 403 with the access-denied code. As the target it is fine (view is enough).
        assertThat(create(full, supersedes, viewOnly, a, 403).get("code").asString()).isEqualTo("DOCUMENT_ACCESS_DENIED");
        create(full, supersedes, a, viewOnly, 201);
        // A document the caller can't open, as source or as target: 404, exactly as if it didn't exist.
        create(full, supersedes, hidden, a, 404);
        create(full, supersedes, a, hidden, 404);
        create(full, supersedes, a, UUID.randomUUID(), 404);
    }

    @Test
    void edit_remove_needTheirPermission_andEditOnTheSource() throws Exception {
        UUID link = existingLink(a, b);
        patchAs("/api/v1/document-relationships/" + link, remover, body("note", "x"), 403);
        patchAs("/api/v1/document-relationships/" + link, viewer, body("note", "x"), 403);
        patchAs("/api/v1/document-relationships/" + link, nobody, body("note", "x"), 403);
        patchAs("/api/v1/document-relationships/" + link, creator, body("note", "ok"), 200);
        patchAs("/api/v1/document-relationships/" + link, full, body("note", "ok again"), 200);

        deleteAs("/api/v1/document-relationships/" + link, creator, 403);
        deleteAs("/api/v1/document-relationships/" + link, viewer, 403);
        deleteAs("/api/v1/document-relationships/" + link, nobody, 403);
        deleteAs("/api/v1/document-relationships/" + link, remover, 204);
        UUID again = existingLink(a, c);
        deleteAs("/api/v1/document-relationships/" + again, full, 204);

        // Source only viewable by this caller: they see the link exists but can't change or remove it.
        UUID viewOnlySource = existingLink(viewOnly, a);
        assertThat(patchAs("/api/v1/document-relationships/" + viewOnlySource, full, body("note", "x"), 403)
                .get("code").asString()).isEqualTo("DOCUMENT_ACCESS_DENIED");
        deleteAs("/api/v1/document-relationships/" + viewOnlySource, full, 403);
        // Source hidden: as if there were no such link.
        UUID hiddenSource = existingLink(hidden, a);
        patchAs("/api/v1/document-relationships/" + hiddenSource, full, body("note", "x"), 404);
        deleteAs("/api/v1/document-relationships/" + hiddenSource, full, 404);
        // Hidden target of a link whose source is editable: the target is not needed to remove, but editing needs to see it.
        UUID toHidden = existingLink(a, hidden);
        patchAs("/api/v1/document-relationships/" + toHidden, full, body("note", "x"), 404);
        deleteAs("/api/v1/document-relationships/" + toHidden, full, 204);
    }

    @Test
    void bulk_needsTheCreatePermission() throws Exception {
        postJson("/api/v1/document-relationships/bulk", creator, body("relationshipTypeId", related,
                "sourceDocumentIds", List.of(a), "targetDocumentIds", List.of(b, c)), 201);
        postJson("/api/v1/document-relationships/bulk", remover, body("relationshipTypeId", supersedes,
                "sourceDocumentIds", List.of(a), "targetDocumentIds", List.of(b)), 403);
        postJson("/api/v1/document-relationships/bulk", nobody, body("relationshipTypeId", supersedes,
                "sourceDocumentIds", List.of(a), "targetDocumentIds", List.of(b)), 403);
        // An anchor that is only viewable can't be the source.
        postJson("/api/v1/document-relationships/bulk", full, body("relationshipTypeId", supersedes,
                "sourceDocumentIds", List.of(viewOnly), "targetDocumentIds", List.of(b)), 403);
        // Several viewable-only sources, one target: the sources are named in the errors.
        JsonNode bad = postJson("/api/v1/document-relationships/bulk", full, body("relationshipTypeId", supersedes,
                "sourceDocumentIds", List.of(a, viewOnly), "targetDocumentIds", List.of(c)), 422);
        assertThat(bad.get("details").get(0).get("code").asString()).isEqualTo("NO_EDIT_ACCESS");
        assertThat(bad.get("details").get(0).get("documentId").asString()).isEqualTo(viewOnly.toString());
    }

    @Test
    void reading_needsOnlyViewAccess_noDrmPermission() throws Exception {
        existingLink(a, b);
        assertThat(panel(viewer, a).get("total").asInt()).isEqualTo(1);
        assertThat(panel(viewer, a).get("canAdd").asBoolean()).isFalse();
        assertThat(panel(viewer, a).get("groups").get(0).get("items").get(0).get("canEdit").asBoolean()).isFalse();
        assertThat(panel(viewer, a).get("groups").get(0).get("items").get(0).get("canRemove").asBoolean()).isFalse();
        call(get("/api/v1/documents/" + a + "/relationships"), fx.jwtFor(nobody), null, 403);
        call(get("/api/v1/documents/" + hidden + "/relationships"), fx.jwtFor(full), null, 404);
        postJson("/api/v1/document-relationships/counts", viewer, body("documentIds", List.of(a)), 200);
        postJson("/api/v1/document-relationships/counts", nobody, body("documentIds", List.of(a)), 403);
        // A viewer with only view access on a: the panel offers nothing to change.
        assertThat(panel(full, viewOnly).get("canAdd").asBoolean()).isFalse();
        assertThat(panel(full, a).get("canAdd").asBoolean()).isTrue();
    }

    @Test
    void typesAreReadByLinkersAndConfigurers_only() throws Exception {
        types(nobody, false, 403);
        types(viewer, false, 403);
        types(remover, false, 403);
        types(creator, false, 200);
    }

    @Test
    void aTenantAdminCanDoItAll() throws Exception {
        UUID link = id(call(post("/api/v1/document-relationships"), fx.tenantAdminJwtFor(admin),
                body("relationshipTypeId", supersedes, "sourceDocumentId", a, "targetDocumentId", b), 201), "relationshipId");
        // Hidden documents are an admin's too.
        call(post("/api/v1/document-relationships"), fx.tenantAdminJwtFor(admin),
                body("relationshipTypeId", related, "sourceDocumentId", hidden, "targetDocumentId", viewOnly), 201);
        call(patch("/api/v1/document-relationships/" + link), fx.tenantAdminJwtFor(admin), body("note", "n"), 200);
        call(delete("/api/v1/document-relationships/" + link), fx.tenantAdminJwtFor(admin), null, 204);
    }

    @Test
    void anotherTenant_getsNotFound_onEveryEndpoint() throws Exception {
        UUID link = existingLink(a, b);
        UUID otherTenant = tenantWithTypes();
        User stranger = fx.user(otherTenant, "Stranger");
        fx.role(otherTenant, "Stranger", new String[]{CREATE, REMOVE, VIEW, TYPE_UPDATE, TYPE_DELETE}, stranger);
        UUID theirType = typeId(otherTenant, "SUPERSEDES");
        UUID theirDoc = fx.document(otherTenant, fx.documentType(otherTenant, "Contract"), stranger, "Theirs");

        // Their documents, this tenant's ids, in every position.
        create(stranger, theirType, theirDoc, a, 404);
        create(stranger, theirType, a, theirDoc, 404);
        create(stranger, supersedes, theirDoc, theirDoc, 404);   // this tenant's TYPE id with their own documents
        postJson("/api/v1/document-relationships/bulk", stranger, body("relationshipTypeId", theirType,
                "sourceDocumentIds", List.of(a), "targetDocumentIds", List.of(b)), 404);
        patchAs("/api/v1/document-relationships/" + link, stranger, body("note", "x"), 404);
        deleteAs("/api/v1/document-relationships/" + link, stranger, 404);
        call(get("/api/v1/documents/" + a + "/relationships"), fx.jwtFor(stranger), null, 404);
        call(get("/api/v1/documents?all=true&relatedTo=" + a), fx.jwtFor(stranger), null, 404);
        // The counts endpoint leaves out what isn't theirs, rather than answering per id.
        JsonNode counts = postJson("/api/v1/document-relationships/counts", stranger, body("documentIds", List.of(a, theirDoc)), 200);
        assertThat(counts.get("counts").toString()).doesNotContain(a.toString()).contains(theirDoc.toString());
        // Types: theirs only, and another tenant's type id is not found.
        call(delete("/api/v1/relationship-types/" + supersedes), fx.jwtFor(stranger), null, 404);
        assertThat(activeLinks(tenantId)).isEqualTo(1);
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ?", otherTenant)).isZero();
    }
}
