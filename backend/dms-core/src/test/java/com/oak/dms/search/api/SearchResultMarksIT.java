package com.oak.dms.search.api;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S11: what a results page carries beyond the index. Pinned and bookmarked marks and the thumbnail link are decided in
 * PostgreSQL for the verified page only, are per person, and can never exist for a document the caller cannot see.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchResultMarksIT extends SearchApiSupport {

    private void restrictTo(UUID document, User... members) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", document);
        for (User m : members) {
            fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) values (?, ?, 'user', ?, 'manage')",
                    tenantId, document, m.getUserId());
        }
    }

    private void bookmark(User user, UUID document) {
        fx.jdbc().update("insert into document_bookmarks (tenant_id, user_id, document_id) values (?, ?, ?)", tenantId, user.getUserId(), document);
    }

    private void pin(User by, UUID document, String mode, User... audience) {
        UUID assignment = fx.jdbc().queryForObject("insert into document_pin_assignments (tenant_id, document_id, pinned_by, audience_mode)"
                + " values (?, ?, ?, ?) returning assignment_id", UUID.class, tenantId, document, by.getUserId(), mode);
        for (User a : audience) {
            fx.jdbc().update("insert into document_pin_audience_members (assignment_id, user_id) values (?, ?)", assignment, a.getUserId());
        }
    }

    private static JsonNode item(JsonNode response, UUID id) {
        for (JsonNode i : response.get("items")) {
            if (i.get("documentId").asString().equals(id.toString())) {
                return i;
            }
        }
        throw new AssertionError("not in results: " + id);
    }

    @Test
    void marksAndThumbnailLinksAreCarriedPerPersonForTheVisiblePage() throws Exception {
        UUID a = doc("Zebra alpha", "a.txt", "zebra");
        UUID b = doc("Zebra beta", "b.txt", "zebra");
        UUID c = doc("Zebra gamma", "c.txt", "zebra");
        bookmark(alice, a);
        pin(alice, b, "USERS", alice);          // alice only
        pin(alice, c, "ALL");                    // everyone except nobody: bob sees it pinned too
        index();

        JsonNode asAlice = search(alice, "q", "zebra");
        assertThat(item(asAlice, a).path("bookmarked").asBoolean()).isTrue();
        assertThat(item(asAlice, a).path("pinned").asBoolean()).isFalse();
        assertThat(item(asAlice, b).path("pinned").asBoolean()).isTrue();
        assertThat(item(asAlice, c).path("pinned").asBoolean()).isTrue();
        assertThat(item(asAlice, b).path("bookmarked").asBoolean()).isFalse();

        JsonNode asBob = search(bob, "q", "zebra");
        assertThat(item(asBob, a).path("bookmarked").asBoolean()).as("bookmarks are personal").isFalse();
        assertThat(item(asBob, b).path("pinned").asBoolean()).as("a pin for alice only is not bob's").isFalse();
        assertThat(item(asBob, c).path("pinned").asBoolean()).as("a pin for everyone is").isTrue();

        // the fields the preview and thumbnail need
        JsonNode first = item(asAlice, a);
        assertThat(first.path("thumbnailUrl").asString()).isEqualTo("/api/v1/documents/" + a + "/thumbnail");
        assertThat(first.has("versionNumber")).isTrue();
        assertThat(first.has("sizeBytes")).isTrue();
        assertThat(first.path("sizeBytes").asLong()).isPositive();
    }

    @Test
    void noThumbnailLinkWithoutThePermissionTheThumbnailEndpointNeeds() throws Exception {
        User searchOnly = fx.user(tenantId, "Searchonly");
        fx.role(tenantId, "Only search", new String[]{"dsr.search-documents"}, searchOnly);
        UUID a = doc("Zebra alpha", "a.txt", "zebra");
        index();
        // the document is the person's own, so the PostgreSQL check passes, but the picture endpoint would refuse them
        fx.jdbc().update("update documents set owner_user_id = ? where document_id = ?", searchOnly.getUserId(), a);
        JsonNode r = search(searchOnly, "q", "zebra");
        assertThat(r.get("items")).hasSize(1);
        assertThat(r.get("items").get(0).path("thumbnailUrl").isNull()).isTrue();
    }

    @Test
    void aHiddenDocumentNeverGetsAMarkOrAThumbnailLink_orChangesWhatTheCallerSees() throws Exception {
        UUID shown = doc("Zebra shown", "s.txt", "zebra");
        UUID hidden = doc("Zebra hidden", "h.txt", "zebra");
        restrictTo(hidden, alice);              // bob cannot open it
        bookmark(bob, hidden);                   // a stale bookmark from before it was restricted
        pin(alice, hidden, "ALL");               // pinned for everybody, but bob may not see it
        index();

        JsonNode asBob = search(bob, "q", "zebra");
        assertThat(titles(asBob)).containsExactly("Zebra shown");
        assertThat(asBob.toString()).doesNotContain(hidden.toString()).doesNotContain("Zebra hidden");
        assertThat(item(asBob, shown).path("pinned").asBoolean()).isFalse();
        assertThat(item(asBob, shown).path("bookmarked").asBoolean()).isFalse();
        // and the response is the same as if the hidden marks did not exist
        fx.jdbc().update("delete from document_bookmarks where document_id = ?", hidden);
        fx.jdbc().update("delete from document_pin_assignments where document_id = ?", hidden);
        assertThat(search(bob, "q", "zebra").toString()).isEqualTo(asBob.toString());
    }

    @Test
    void marksAreLookedUpWithOneQueryPerKindForThePageNotPerCandidate() throws Exception {
        for (int i = 0; i < 30; i++) {
            doc("Zebra " + i, "z" + i + ".txt", "zebra");
        }
        index();
        // 25 per page: the marks come from the page's ids only, so a page of 25 can hold at most 25 marks, whatever
        // the size of the candidate window the over-fetch loop looked at
        JsonNode page = search(alice, "q", "zebra", "size", 25);
        assertThat(page.get("items")).hasSize(25);
        for (JsonNode item : page.get("items")) {
            assertThat(item.has("pinned")).isTrue();
            assertThat(item.has("bookmarked")).isTrue();
        }
    }
}
