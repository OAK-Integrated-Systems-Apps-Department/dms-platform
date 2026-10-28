package com.oak.dms.relationship;

import com.oak.dms.document.RecycleBinService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * What happens to links when a document moves, is renamed through its folder, gets a new version,
 * goes to the Recycle Bin, comes back, or is purged (R6). The checklist for ownership transfer and
 * archive, which don't exist yet, is docs/relationships-lifecycle-checklist.md.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipLifecycleIT extends RelationshipApiSupport {

    @Autowired
    private RecycleBinService recycleBin;

    private UUID tenantId;
    private User editor;
    private UUID type;
    private UUID a;
    private UUID b;
    private UUID c;

    @BeforeEach
    void world() {
        tenantId = tenantWithTypes();
        editor = fx.user(tenantId, "Editor");
        fx.role(tenantId, "Editor", new String[]{CREATE, REMOVE, VIEW, "docm.documents.delete", "docm.documents.move",
                "rbr.restore-deleted-documents", "rbr.permanently-purge-documents",
                "dsc.manage-folder-structure.create-folder", "dsc.manage-folder-structure.rename-move-folder"}, editor);
        type = fx.documentType(tenantId, "Policy");
        a = fx.document(tenantId, type, editor, "Policy A");
        b = fx.document(tenantId, type, editor, "Policy B");
        c = fx.document(tenantId, type, editor, "Policy C");
    }

    private JsonNode firstItem(UUID document) throws Exception {
        return panel(editor, document).get("groups").get(0).get("items").get(0);
    }

    @Test
    void aSoftDeletedTarget_keepsTheLink_flaggedInTheRecycleBin_andRestoreChangesNothing() throws Exception {
        createLink(editor, typeId(tenantId, "SUPERSEDES"), a, b, 201);
        call(delete("/api/v1/documents/" + b), fx.jwtFor(editor), null, 204);

        JsonNode row = firstItem(a);
        assertThat(row.get("other").get("inRecycleBin").asBoolean()).isTrue();
        assertThat(row.get("other").get("title").asString()).isEqualTo("Policy B");
        assertThat(row.get("other").get("accessible").asBoolean()).isTrue();
        assertThat(activeLinks(tenantId)).isEqualTo(1);
        // A document in the bin takes no new links, and has no panel of its own until it is back.
        createLink(editor, typeId(tenantId, "RELATED"), a, b, 404);
        createLink(editor, typeId(tenantId, "RELATED"), b, a, 404);
        call(get("/api/v1/documents/" + b + "/relationships"), fx.jwtFor(editor), null, 404);

        long rowsBefore = fx.count("select count(*) from document_relationships where tenant_id = ?", tenantId);
        call(post("/api/v1/recycle-bin/" + b + "/restore"), fx.jwtFor(editor), null, 204);
        assertThat(firstItem(a).get("other").get("inRecycleBin").asBoolean()).isFalse();
        assertThat(firstItem(b).get("label").asString()).isEqualTo("Superseded by");
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ?", tenantId)).isEqualTo(rowsBefore);
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ? and removed_at is not null",
                tenantId)).isZero();
    }

    @Test
    void aPurgedDocument_losesItsLiveLinks_andTheAuditTrailKeepsWhatItWasRelatedTo() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        UUID kept = id(createLink(editor, supersedes, a, c, 201), "relationshipId");
        UUID asTarget = id(createLink(editor, supersedes, a, b, 201), "relationshipId");
        UUID asSource = id(createLink(editor, typeId(tenantId, "MAIN_ATTACHMENT"), b, c, 201), "relationshipId");

        call(delete("/api/v1/documents/" + b), fx.jwtFor(editor), null, 204);
        call(delete("/api/v1/recycle-bin/" + b), fx.jwtFor(editor), null, 204);

        // Gone from the live panels of the others; the unrelated link is untouched.
        JsonNode pa = panel(editor, a);
        assertThat(pa.get("total").asInt()).isEqualTo(1);
        assertThat(id(pa.get("groups").get(0).get("items").get(0), "relationshipId")).isEqualTo(kept);
        assertThat(panel(editor, c).get("total").asInt()).isEqualTo(1);

        for (UUID gone : List.of(asTarget, asSource)) {
            assertThat(fx.count("select count(*) from document_relationships where relationship_id = ? and removed_at is not null"
                    + " and removed_reason = 'target_purged' and removed_by is null", gone)).isEqualTo(1);
        }
        assertThat(fx.count("select count(*) from document_relationships where relationship_id = ? and removed_at is null", kept))
                .isEqualTo(1);

        // The permanent record: what it was related to and how, with titles as they were.
        assertThat(auditCount(tenantId, "RELATIONSHIP_REMOVED_TARGET_PURGED")).isEqualTo(2);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'RELATIONSHIP_REMOVED_TARGET_PURGED'"
                + " and object_id = ? and new_value::text like '%SUPERSEDES%' and new_value::text like '%Policy A%'"
                + " and new_value::text like '%Policy B%' and new_value::text like '%\"purgedDocumentRole\": \"target\"%'",
                tenantId, asTarget)).isEqualTo(1);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'RELATIONSHIP_REMOVED_TARGET_PURGED'"
                + " and object_id = ? and new_value::text like '%MAIN_ATTACHMENT%' and new_value::text like '%\"purgedDocumentRole\": \"source\"%'",
                tenantId, asSource)).isEqualTo(1);
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ? and source_document_id = ?"
                + " and relationship_id = ?", tenantId, a, asTarget)).as("the row stays for history").isEqualTo(1);
    }

    @Test
    void theRetentionSweep_removesLinksToo() throws Exception {
        createLink(editor, typeId(tenantId, "RELATED"), a, b, 201);
        call(delete("/api/v1/documents/" + b), fx.jwtFor(editor), null, 204);
        fx.jdbc().update("update documents set deleted_at = now() - interval '90 days' where document_id = ?", b);

        recycleBin.purgeExpired();

        assertThat(fx.count("select count(*) from documents where document_id = ? and purged_at is not null", b)).isEqualTo(1);
        assertThat(activeLinks(tenantId)).isZero();
        assertThat(panel(editor, a).get("total").asInt()).isZero();
        assertThat(auditCount(tenantId, "RELATIONSHIP_REMOVED_TARGET_PURGED")).isEqualTo(1);
    }

    @Test
    void movesRenamesFolderChangesAndNewVersions_neverTouchTheLinks() throws Exception {
        createLink(editor, typeId(tenantId, "SUPERSEDES"), a, b, 201);
        createLink(editor, typeId(tenantId, "RELATED"), a, c, 201);
        JsonNode before = panel(editor, a);

        UUID f1 = id(call(post("/api/v1/folders"), fx.jwtFor(editor), body("name", "Policies"), 201), "folderId");
        UUID f2 = id(call(post("/api/v1/folders"), fx.jwtFor(editor), body("name", "Archive-2025"), 201), "folderId");
        // Move a, then b, into folders; rename a folder; move a folder under another.
        call(post("/api/v1/documents/move"), fx.jwtFor(editor), body("documentIds", List.of(a), "folderId", f1), 200);
        call(post("/api/v1/documents/move"), fx.jwtFor(editor), body("documentIds", List.of(b), "folderId", f2), 200);
        call(patch("/api/v1/folders/" + f1), fx.jwtFor(editor), body("name", "Current policies"), 200);
        call(post("/api/v1/folders/" + f2 + "/move"), fx.jwtFor(editor), body("parentFolderId", f1), 200);
        call(post("/api/v1/documents/move"), fx.jwtFor(editor), body("documentIds", List.of(a, b), "folderId", f2), 200);
        // And a new version of each end.
        fx.newVersion(tenantId, a, editor);
        fx.newVersion(tenantId, b, editor);

        JsonNode after = panel(editor, a);
        assertThat(after.get("total").asInt()).isEqualTo(before.get("total").asInt()).isEqualTo(2);
        assertThat(after.get("groups").size()).isEqualTo(before.get("groups").size());
        assertThat(panel(editor, b).get("total").asInt()).isEqualTo(1);
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ? and removed_at is null"
                + " and source_version_id is null and target_version_id is null", tenantId)).isEqualTo(2);
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ?", tenantId)).isEqualTo(2);
    }
}
