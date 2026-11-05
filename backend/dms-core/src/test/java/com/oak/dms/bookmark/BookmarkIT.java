package com.oak.dms.bookmark;

import com.oak.dms.folder.FolderException;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Private bookmarks (4.4 Bookmark Documents): only the owner ever sees their own bookmark, it never
 * grants access, and - unlike a pin - one hidden by the recycle bin or lost access is kept, not deleted,
 * and reappears if the document comes back. The role-permission backfill migration itself is exercised
 * in {@link V134BookmarkPermissionBackfillMigrationTest}, not here - see that class's doc comment.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class BookmarkIT extends WorkflowApiSupport {

    static final String[] VIEWER = {"docm.documents.view", "bd.bookmark-unbookmark-documents.bookmark"};
    static final String[] DELETER = {"docm.documents.view", "bd.bookmark-unbookmark-documents.bookmark",
            "docm.documents.delete", "rbr.restore-deleted-documents", "rbr.permanently-purge-documents"};

    @Autowired
    private BookmarkService bookmarkService;

    private UUID tenantId;
    private UUID typeId;
    private User alice;
    private User bob;
    private UUID doc;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        typeId = fx.documentType(tenantId, "Policy");
        alice = fx.user(tenantId, "Alice");
        bob = fx.user(tenantId, "Bob");
        fx.role(tenantId, "Viewer " + UUID.randomUUID(), VIEWER, alice, bob);
        fx.role(tenantId, "Deleter " + UUID.randomUUID(), DELETER, alice);
        doc = fx.document(tenantId, typeId, alice, "Handbook");
    }

    /** Gives the document its own access list naming only {@code allowed} - nobody else can open it. */
    private void restrictTo(UUID documentId, User allowed) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", documentId);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, "
                + "access_level) values (?, ?, 'user', ?, 'view')", tenantId, documentId, allowed.getUserId());
    }

    private void grantAccess(UUID documentId, User user) {
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, "
                + "access_level) values (?, ?, 'user', ?, 'view')", tenantId, documentId, user.getUserId());
    }

    private JsonNode addBookmark(User as, UUID document, String note, int expectedStatus) throws Exception {
        return call(put("/api/v1/bookmarks/" + document), fx.jwtFor(as),
                note == null ? Map.of() : Map.of("note", note), expectedStatus);
    }

    @Test
    void sameNotFound_forAMissingDocumentAndOneTheCallerCannotSee() throws Exception {
        addBookmark(alice, UUID.randomUUID(), null, 404);

        UUID secret = fx.document(tenantId, typeId, alice, "Secret");
        restrictTo(secret, alice);
        addBookmark(bob, secret, null, 404);
    }

    @Test
    void addIsIdempotent_aSecondAddUpdatesTheNoteInstead() throws Exception {
        JsonNode first = addBookmark(alice, doc, null, 200);
        assertThat(first.get("created").asBoolean()).isTrue();

        JsonNode second = addBookmark(alice, doc, "Check this quarterly", 200);
        assertThat(second.get("created").asBoolean()).isFalse();

        JsonNode page = get200("/api/v1/bookmarks", alice);
        assertThat(page.get("total").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("note").asString()).isEqualTo("Check this quarterly");
    }

    @Test
    void crossUserIsolation_bobCanNeitherSeeNorChangeNorRemoveAlicesBookmark() throws Exception {
        addBookmark(alice, doc, "mine", 200);

        assertThat(get200("/api/v1/bookmarks", bob).get("items")).isEmpty();

        // Bob has no bookmark here at all: updating is a genuine 404, removing is a quiet no-op (own-cleanup rule).
        call(patch("/api/v1/bookmarks/" + doc + "/note"), fx.jwtFor(bob), Map.of("note", "hijacked"), 404);
        call(delete("/api/v1/bookmarks/" + doc), fx.jwtFor(bob), null, 204);

        JsonNode alicePage = get200("/api/v1/bookmarks", alice);
        assertThat(alicePage.get("items")).hasSize(1);
        assertThat(alicePage.get("items").get(0).get("note").asString()).isEqualTo("mine");
    }

    @Test
    void accessRevoked_hidesTheBookmark_andCountsIt_restoredBringsItBack() throws Exception {
        addBookmark(bob, doc, null, 200);

        restrictTo(doc, alice);
        JsonNode hidden = get200("/api/v1/bookmarks", bob);
        assertThat(hidden.get("items")).isEmpty();
        assertThat(hidden.get("unavailableCount").asInt()).isEqualTo(1);

        grantAccess(doc, bob);
        JsonNode restored = get200("/api/v1/bookmarks", bob);
        assertThat(restored.get("items")).hasSize(1);
        assertThat(restored.get("unavailableCount").asInt()).isZero();
    }

    @Test
    void sentToTheRecycleBin_hidesTheBookmark_andRestoreBringsItBack() throws Exception {
        addBookmark(alice, doc, null, 200);

        call(delete("/api/v1/documents/" + doc), fx.jwtFor(alice), null, 204);
        JsonNode hidden = get200("/api/v1/bookmarks", alice);
        assertThat(hidden.get("items")).isEmpty();
        assertThat(hidden.get("unavailableCount").asInt()).isEqualTo(1);

        call(post("/api/v1/recycle-bin/" + doc + "/restore"), fx.jwtFor(alice), null, 204);
        JsonNode restored = get200("/api/v1/bookmarks", alice);
        assertThat(restored.get("items")).hasSize(1);
    }

    @Test
    void purging_removesTheBookmarkForGood() throws Exception {
        addBookmark(alice, doc, null, 200);

        call(delete("/api/v1/documents/" + doc), fx.jwtFor(alice), null, 204);
        call(delete("/api/v1/recycle-bin/" + doc), fx.jwtFor(alice), null, 204);

        assertThat(fx.count("select count(*) from document_bookmarks where document_id = ?", doc)).isZero();
    }

    @Test
    void archivedDocuments_stayListed_flagged() throws Exception {
        addBookmark(alice, doc, null, 200);
        fx.jdbc().update("update documents set lifecycle_state = 'archived' where document_id = ?", doc);

        JsonNode page = get200("/api/v1/bookmarks", alice);
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("archived").asBoolean()).isTrue();
    }

    @Test
    void templatesAreExcluded_sameAsTheDocumentBrowser() throws Exception {
        fx.jdbc().update("update documents set is_template = true where document_id = ?", doc);
        addBookmark(alice, doc, null, 404);
    }

    @Test
    void tenantIsolation_anotherTenantsUserCannotAddressTheDocumentAtAll() throws Exception {
        UUID otherTenant = fx.tenant();
        User stranger = fx.user(otherTenant, "Stranger");
        fx.role(otherTenant, "Viewer " + UUID.randomUUID(), VIEWER, stranger);

        addBookmark(stranger, doc, null, 404);
    }

    @Test
    void statusEndpoint_reportsOnlyTheCallersOwnBookmarks() throws Exception {
        UUID other = fx.document(tenantId, typeId, alice, "Other");
        addBookmark(alice, doc, null, 200);

        JsonNode status = call(get("/api/v1/bookmarks/status?ids=" + doc + "," + other), fx.jwtFor(alice), null, 200);
        assertThat(status.get("bookmarkedDocumentIds")).hasSize(1);
        assertThat(status.get("bookmarkedDocumentIds").get(0).asString()).isEqualTo(doc.toString());

        assertThat(get200("/api/v1/bookmarks/status?ids=" + doc, bob).get("bookmarkedDocumentIds")).isEmpty();
    }

    @Test
    void theCapIsEnforced_andPruneOnWriteReclaimsUnavailableRowsFirst() {
        ReflectionTestUtils.setField(bookmarkService, "maxPerUser", 1);
        UUID second = fx.document(tenantId, typeId, alice, "Second");
        TenantContext.set(tenantId, alice.getUserId());
        try {
            bookmarkService.add(doc, null);

            // At the cap, and `doc` is still available - nothing to prune, so this is a real refusal.
            assertThatThrownBy(() -> bookmarkService.add(second, null))
                    .isInstanceOfSatisfying(FolderException.class,
                            e -> assertThat(e.getCode()).isEqualTo("BOOKMARK_LIMIT_REACHED"));

            // `doc` becomes unavailable to Alice (access revoked) - the cap check must now find it
            // prunable instead of refusing again.
            restrictTo(doc, bob);
            BookmarkService.AddResult result = bookmarkService.add(second, null);
            assertThat(result.created()).isTrue();

            BookmarkService.BookmarksPage page = bookmarkService.list(0, 25, BookmarkService.SortKey.BOOKMARKED_AT,
                    BookmarkService.SortDirection.DESC, null);
            assertThat(page.items()).extracting(i -> i.document().documentId()).containsExactly(second);
            assertThat(fx.count("select count(*) from document_bookmarks where user_id = ? and document_id = ?",
                    alice.getUserId(), doc)).as("pruned, not just hidden").isZero();
        } finally {
            TenantContext.clear();
            ReflectionTestUtils.setField(bookmarkService, "maxPerUser", 500);
        }
    }
}
