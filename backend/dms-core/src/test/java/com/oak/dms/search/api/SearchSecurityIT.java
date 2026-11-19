package com.oak.dms.search.api;

import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The two-layer security (D3) from the outside. Whatever the index holds (stale, wrong, or deliberately over-inclusive), a
 * caller only ever gets documents they may open, and nothing about the ones they may not: not rows, snippets, totals,
 * suggestions, errors or response shape.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchSecurityIT extends SearchApiSupport {

    @Autowired
    private SearchProperties props;

    private int originalRounds;

    @AfterEach
    void restoreCaps() {
        if (originalRounds != 0) {
            props.getQuery().setMaxRounds(originalRounds);
            originalRounds = 0;
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private void restrictTo(UUID document, String level, User... members) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", document);
        for (User m : members) {
            fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) values (?, ?, 'user', ?, ?)",
                    tenantId, document, m.getUserId(), level);
        }
    }

    private void restrictFolder(UUID folder, User... members) {
        fx.jdbc().update("update folders set access_mode = 'restricted' where folder_id = ?", folder);
        for (User m : members) {
            fx.jdbc().update("insert into folder_access (tenant_id, folder_id, principal_type, principal_id, access_level) values (?, ?, 'user', ?, 'view')",
                    tenantId, folder, m.getUserId());
        }
    }

    // ---- visibility ----------------------------------------------------------------------------------------------

    @Test
    void aRestrictedDocumentIsOnlyForPeopleOnItsList_andAccessChangesNeedNoReindex() throws Exception {
        UUID open = doc("Open plan", "o.txt", "shared");
        UUID secret = doc("Secret plan", "s.txt", "shared");
        index();
        assertThat(ids(search(bob, "q", "shared"))).containsExactlyInAnyOrder(open, secret);

        restrictTo(secret, "manage", alice); // no reindex: visibility is never in the index
        assertThat(ids(search(bob, "q", "shared"))).containsExactly(open);
        assertThat(ids(search(alice, "q", "shared"))).containsExactlyInAnyOrder(open, secret);
        assertThat(ids(call(get("/api/v1/search").queryParam("q", "shared"), fx.tenantAdminJwtFor(tenantAdmin), null, 200)))
                .as("a tenant administrator sees everything in their organisation").containsExactlyInAnyOrder(open, secret);

        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) values (?, ?, 'user', ?, 'view')",
                tenantId, secret, bob.getUserId());
        assertThat(ids(search(bob, "q", "shared"))).as("granted again, visible at once").containsExactlyInAnyOrder(open, secret);
    }

    @Test
    void folderRestrictionsHideEverythingInThatFolderAndBelow() throws Exception {
        UUID hr = folder(null, "HR");
        UUID hrSub = folder(hr, "HR Cases");
        UUID pub = folder(null, "Public");
        UUID inHr = doc("HR record", "a.txt", "shared");
        UUID inHrSub = doc("HR case file", "b.txt", "shared");
        UUID inPub = doc("Public notice", "c.txt", "shared");
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", hr, inHr);
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", hrSub, inHrSub);
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", pub, inPub);
        index();
        restrictFolder(hr, alice);

        assertThat(ids(search(bob, "q", "shared"))).containsExactly(inPub);
        assertThat(ids(search(alice, "q", "shared"))).containsExactlyInAnyOrder(inHr, inHrSub, inPub);
        // filtering by a folder you cannot see tells you nothing: it is treated as a folder that does not exist
        JsonNode r = search(bob, "q", "shared", "folderId", hr, "includeSubfolders", true);
        assertThat(noticeCodes(r)).contains("FILTER_NO_LONGER_EXISTS");
        assertThat(ids(r)).containsExactly(inPub);
    }

    @Test
    void anApproverReadsADocumentOnlyWhileTheyHoldAnOpenTask() throws Exception {
        fx.role(tenantId, "Submitter", new String[]{WorkflowFixtures.SUBMIT_PERMISSION}, alice);
        fx.role(tenantId, "Approver", WorkflowFixtures.APPROVER_PERMISSIONS, bob);
        UUID contract = doc("Supplier contract", "c.txt", "shared");
        restrictTo(contract, "manage", alice);
        activeTemplate(tenantAdmin, "CONTRACT", reportType, "flag_only", List.of(
                stageDef("Sign-off", "any", "halt_immediately", 24, entry("user", bob.getUserId()))));
        index();
        assertThat(ids(search(bob, "q", "shared"))).isEmpty();

        JsonNode submitted = submit(alice, contract, 201);
        assertThat(ids(search(bob, "q", "shared"))).as("workflow-readable while the task is open").containsExactly(contract);

        approve(bob, taskId(submitted, 1, bob.getDisplayName()), 200);
        assertThat(ids(search(bob, "q", "shared"))).as("read access ends with the task").isEmpty();
    }

    @Test
    void deletedPurgedAndTemplateDocumentsNeverAppear_evenWhenTheIndexIsStale() throws Exception {
        UUID binned = doc("Binned doc", "a.txt", "shared");
        UUID purged = doc("Purged doc", "b.txt", "shared");
        UUID template = doc("Template doc", "c.txt", "shared");
        UUID kept = doc("Kept doc", "d.txt", "shared");
        index();
        assertThat(ids(search(alice, "q", "shared"))).hasSize(4);

        // changed in PostgreSQL only: the indexer has NOT run, so the index still holds all four
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", binned);
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now(), purged_at = now() where document_id = ?", purged);
        fx.jdbc().update("update documents set is_template = true where document_id = ?", template);
        assertThat(ids(search(alice, "q", "shared"))).containsExactly(kept);

        fx.jdbc().update("update documents set is_deleted = false, deleted_at = null where document_id = ?", binned);
        assertThat(ids(search(alice, "q", "shared"))).as("restored from the Recycle Bin").containsExactlyInAnyOrder(kept, binned);
    }

    // ---- archived ------------------------------------------------------------------------------------------------

    @Test
    void inactiveAndArchivedDocumentsNeedThePermission_andAreNeverMixedIntoNormalResults() throws Exception {
        UUID active = doc("Active record", "a.txt", "shared");
        UUID inactive = doc("Inactive record", "i.txt", "shared");
        UUID archived = doc("Archived record", "r.txt", "shared");
        fx.jdbc().update("update documents set lifecycle_state = 'inactive' where document_id = ?", inactive);
        fx.jdbc().update("update documents set lifecycle_state = 'archived' where document_id = ?", archived);
        index();

        assertThat(ids(search(alice, "q", "shared"))).as("default: active only").containsExactly(active);
        searchExpecting(403, alice, "q", "shared", "status", "archived");
        searchExpecting(403, alice, "q", "shared", "status", "inactive,archived");
        searchExpecting(403, alice, "q", "shared", "status", "all");

        assertThat(ids(search(archivist, "q", "shared"))).containsExactly(active);
        assertThat(ids(search(archivist, "q", "shared", "status", "archived"))).containsExactly(archived);
        assertThat(ids(search(archivist, "q", "shared", "status", "inactive"))).containsExactly(inactive);
        assertThat(ids(search(archivist, "q", "shared", "status", "inactive,archived"))).containsExactlyInAnyOrder(inactive, archived);
        assertThat(ids(search(archivist, "q", "shared", "status", "all"))).containsExactlyInAnyOrder(active, inactive, archived);
        JsonNode item = search(archivist, "q", "archived", "status", "archived").get("items").get(0);
        assertThat(item.get("lifecycleState").asString()).isEqualTo("archived");
    }

    @Test
    void aLifecycleChangeNotYetIndexedStillHidesTheDocumentFromNormalSearch() throws Exception {
        UUID doc = doc("Retiring record", "a.txt", "shared");
        index();
        fx.jdbc().update("update documents set lifecycle_state = 'archived' where document_id = ?", doc); // index still says active
        assertThat(ids(search(alice, "q", "shared"))).as("layer 2 checks the live lifecycle").isEmpty();
        assertThat(ids(search(archivist, "q", "shared"))).isEmpty();
        // a stale index can only fail to show a document, never reveal one: under 'archived' it appears once indexed
        assertThat(ids(search(archivist, "q", "shared", "status", "archived"))).isEmpty();
        index();
        assertThat(ids(search(archivist, "q", "shared", "status", "archived"))).as("and shows it where it now belongs").containsExactly(doc);
        assertThat(ids(search(archivist, "q", "shared"))).isEmpty();
    }

    // ---- nothing about hidden documents leaves the API -----------------------------------------------------------

    @Test
    void totalsPagingAndTheFetchLoopNeverRevealHiddenDocuments() throws Exception {
        UUID visibleA = doc("Shared note A", "a.txt", "secretword");
        UUID visibleB = doc("Shared note B", "b.txt", "secretword");
        // 120 hidden documents (restricted to nobody but administrators) match the same word and rank around the visible ones
        UUID versionRef = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, visibleA);
        fx.jdbc().update("insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id, access_mode)"
                + " select gen_random_uuid(), ?, ?, 'Hidden secretword ' || g, ?, ?, 'restricted' from generate_series(1, 120) g",
                tenantId, reportType, versionRef, alice.getUserId());
        index();
        assertThat(fx.count("select count(*) from documents where tenant_id = ? and access_mode = 'restricted'", tenantId)).isEqualTo(120);

        JsonNode r = search(bob, "q", "secretword");
        assertThat(ids(r)).containsExactlyInAnyOrder(visibleA, visibleB);
        assertThat(r.get("total").get("value").asLong()).as("never the engine's 122").isEqualTo(2);
        assertThat(r.get("total").get("exact").asBoolean()).isTrue();
        assertThat(r.get("nextCursor").isNull()).isTrue();
        assertThat(r.toString()).doesNotContain("Hidden");

        // smaller pages do not leak either: every page holds only visible rows
        JsonNode p1 = search(bob, "q", "secretword", "size", 1, "sort", "title", "dir", "asc");
        JsonNode p2 = search(bob, "q", "secretword", "size", 1, "sort", "title", "dir", "asc", "cursor", p1.get("nextCursor").asString());
        assertThat(titles(p1)).containsExactly("Shared note A");
        assertThat(titles(p2)).containsExactly("Shared note B");
    }

    @Test
    void aCapOnTheFetchLoopSaysSoInsteadOfLettingHiddenDocumentsDecideTheAnswer() throws Exception {
        UUID visible = doc("zzz visible last", "z.txt", "secretword");
        UUID versionRef = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, visible);
        fx.jdbc().update("insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id, access_mode)"
                + " select gen_random_uuid(), ?, ?, 'aaa hidden ' || g, ?, ?, 'restricted' from generate_series(1, 120) g",
                tenantId, reportType, versionRef, alice.getUserId());
        index();
        // sorted by title the one visible document is behind 120 hidden ones
        JsonNode full = search(bob, "q", "secretword", "sort", "title", "dir", "asc");
        assertThat(ids(full)).as("the loop keeps going until it finds it").containsExactly(visible);

        originalRounds = props.getQuery().getMaxRounds();
        props.getQuery().setMaxRounds(1);
        JsonNode capped = search(bob, "q", "secretword", "sort", "title", "dir", "asc");
        assertThat(capped.get("items")).as("one round saw only hidden rows").isEmpty();
        assertThat(noticeCodes(capped)).contains("REFINE");
        assertThat(capped.get("nextCursor").isNull()).as("the user can continue").isFalse();
        assertThat(capped.get("total").get("exact").asBoolean()).isFalse();
        // and the next page really does reach the visible document
        JsonNode last = capped;
        for (int i = 0; i < 5 && ids(last).isEmpty() && !last.get("nextCursor").isNull(); i++) {
            last = search(bob, "q", "secretword", "sort", "title", "dir", "asc", "cursor", last.get("nextCursor").asString());
        }
        assertThat(ids(last)).containsExactly(visible);
    }

    @Test
    void aSearchThatOnlyMatchesHiddenDocumentsLooksExactlyLikeOneThatMatchesNothing() throws Exception {
        UUID hidden = doc("Zephyr acquisition plan", "z.txt", "zephyrbody confidential");
        restrictTo(hidden, "manage", alice);
        doc("Visible memo", "v.txt", "ordinary");
        index();

        JsonNode hiddenOnly = search(bob, "q", "zephyrbody");
        JsonNode nothing = search(bob, "q", "qqqqzzzz");
        assertThat(hiddenOnly.toString()).as("same response, byte for byte").isEqualTo(nothing.toString());
        // and a typo of a word that exists only in a hidden document is not "corrected" to it
        JsonNode typo = search(bob, "q", "zephyer acquisiton");
        assertThat(typo.get("items")).isEmpty();
        assertThat(typo.get("didYouMean").isNull()).as("no suggestion built from hidden documents").isTrue();
        assertThat(typo.toString().toLowerCase()).doesNotContain("zephyr");
        // while the owner, who can see it, does get the help
        assertThat(search(alice, "q", "zephyer acquisiton").get("didYouMean").asString()).isEqualTo("zephyr acquisition");
    }

    @Test
    void typeaheadOnlyOffersDocumentsTheCallerCanOpen() throws Exception {
        UUID open = doc("Budget forecast", "b.txt", "x");
        UUID secret = doc("Budget secrets", "s.txt", "x");
        restrictTo(secret, "manage", alice);
        index();
        JsonNode bobs = call(request("/api/v1/search/suggest", "q", "budg"), fx.jwtFor(bob), null, 200);
        List<String> titles = new ArrayList<>();
        bobs.get("suggestions").forEach(s -> titles.add(s.get("title").asString()));
        assertThat(titles).containsExactly("Budget forecast");
        assertThat(call(request("/api/v1/search/suggest", "q", "b"), fx.jwtFor(bob), null, 200).get("suggestions")).as("too short").isEmpty();
        JsonNode alices = call(request("/api/v1/search/suggest", "q", "budg"), fx.jwtFor(alice), null, 200);
        assertThat(alices.get("suggestions")).hasSize(2);
        assertThat(open).isNotNull();
    }

    // ---- tenants -------------------------------------------------------------------------------------------------

    @Test
    void anotherOrganisationIsInvisible_evenToCraftedFilters() throws Exception {
        UUID otherTenant = fx.tenant();
        User otherUser = fx.user(otherTenant, "Mallory");
        UUID otherType = fx.documentType(otherTenant, "Memo");
        UUID foreign = fx.document(otherTenant, otherType, otherUser, "Tenantsecret roadmap");
        UUID otherFolder = UUID.randomUUID();
        fx.jdbc().update("insert into folders (folder_id, tenant_id, folder_name) values (?, ?, 'Theirs')", otherFolder, otherTenant);
        UUID mine = doc("Tenantsecret mine", "m.txt", "tenantsecret");
        backfill.run(otherTenant, "backfill");
        index();
        for (int i = 0; i < 10 && indexer.runOnce() > 0; i++) { /* drain both tenants */ }
        engine.refresh(indexAdmin.writeAlias());

        assertThat(ids(search(alice, "q", "tenantsecret"))).containsExactly(mine);
        // another organisation's ids are simply "nothing that exists here"
        JsonNode crafted = search(alice, "q", "tenantsecret", "type", otherType, "owner", otherUser.getUserId(), "folderId", otherFolder);
        assertThat(ids(crafted)).containsExactly(mine);
        assertThat(noticeCodes(crafted)).contains("FILTER_NO_LONGER_EXISTS");
        // query syntax cannot name a field or a tenant
        assertThat(ids(search(alice, "q", "tenant_id:" + otherTenant))).isEmpty();
        assertThat(ids(search(alice, "q", "document_id:" + foreign))).isEmpty();
        assertThat(ids(search(alice, "q", "*")).stream().filter(d -> d.equals(foreign)).toList()).isEmpty();
        // the other organisation's user never sees ours
        JsonNode theirs = call(request("/api/v1/search", "q", "tenantsecret"), fx.jwtFor(withSearch(otherTenant, otherUser)), null, 200);
        assertThat(ids(theirs)).containsExactly(foreign);
        // a cursor is bound to the user and tenant it was issued to
        for (int i = 0; i < 3; i++) {
            doc("Cursor doc " + i, "c" + i + ".txt", "cursorword");
        }
        index();
        String cursor = search(alice, "q", "cursorword", "size", 1).get("nextCursor").asString();
        call(request("/api/v1/search", "q", "cursorword", "size", 1, "cursor", cursor), fx.jwtFor(withSearch(otherTenant, otherUser)), null, 400);
    }

    private User withSearch(UUID tenant, User user) {
        fx.role(tenant, "Searcher", new String[]{"dsr.search-documents", "docm.documents.view"}, user);
        return user;
    }

    // ---- who may call ---------------------------------------------------------------------------------------------

    @Test
    void thePermissionMatrix() throws Exception {
        doc("Anything", "a.txt", "x");
        index();
        searchExpecting(403, carol, "q", "anything");                                    // may view documents, may not search
        call(request("/api/v1/search/suggest", "q", "any"), fx.jwtFor(carol), null, 403);
        mvc.perform(get("/api/v1/search").queryParam("q", "x")).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        search(alice, "q", "anything");                                                  // dsr.search-documents
        searchExpecting(403, alice, "q", "anything", "status", "archived");              // archive needs crm.view-archive
        search(archivist, "q", "anything", "status", "archived");
        call(request("/api/v1/search", "q", "anything"), fx.tenantAdminJwtFor(tenantAdmin), null, 200);
    }
}
