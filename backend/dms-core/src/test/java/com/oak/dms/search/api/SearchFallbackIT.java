package com.oak.dms.search.api;

import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.engine.SearchEngineException;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The title search stays the safety net (D9): when the engine is down, the index is empty or still being built, or search
 * is switched off, the same API answers from the browse queries - with browse's visibility rules, not search's - and says
 * what it could not do. Nothing here may break browsing itself.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchFallbackIT extends SearchApiSupport {

    @MockitoSpyBean
    private SearchEngine spyEngine;
    @Autowired
    private SearchProperties props;

    @AfterEach
    void restore() {
        Mockito.reset(spyEngine);
        props.setEnabled(true);
    }

    private static void assertBasic(JsonNode r) {
        assertThat(r.get("mode").asString()).isEqualTo("basic");
        assertThat(noticeCodes(r)).contains("FALLBACK");
    }

    @Test
    void aTenantThatWasNeverIndexedIsServedByTheTitleSearch() throws Exception {
        UUID d = doc("Quarterly revenue report", "q.txt", "words only in the body: zebra");
        // no index() at all: no state, no alias
        JsonNode r = search(alice, "q", "revenue");
        assertBasic(r);
        assertThat(ids(r)).containsExactly(d);
        assertThat(r.get("total").get("exact").asBoolean()).isTrue();
        assertThat(r.get("items").get(0).get("highlights").get("body")).isEmpty();
        JsonNode body = search(alice, "q", "zebra");
        assertThat(body.get("items")).as("body text is not searched in basic mode").isEmpty();
        assertThat(noticeCodes(body)).contains("NO_RESULTS", "FILTERS_NOT_APPLIED");
        assertThat(body.toString()).contains("text inside documents");
    }

    @Test
    void anIndexThatIsStillBeingBuiltIsNotUsed() throws Exception {
        UUID d = doc("Contract draft", "c.txt", "x");
        tenantState.ensure(tenantId); // a row, but index_ready = false: the backfill has not finished
        assertBasic(search(alice, "q", "contract"));
        index();
        assertThat(tenantState.find(tenantId).orElseThrow().indexReady()).isTrue();
        JsonNode r = search(alice, "q", "contract");
        assertThat(r.get("mode").asString()).isEqualTo("search");
        assertThat(ids(r)).containsExactly(d);
    }

    @Test
    void theTenantSwitchAndTheGlobalSwitchBothFallBack() throws Exception {
        UUID d = doc("Switchable doc", "s.txt", "x");
        index();
        assertThat(search(alice, "q", "switchable").get("mode").asString()).isEqualTo("search");

        tenantState.setEnabled(tenantId, false);
        JsonNode off = search(alice, "q", "switchable");
        assertBasic(off);
        assertThat(ids(off)).containsExactly(d);
        tenantState.setEnabled(tenantId, true);

        props.setEnabled(false);
        assertBasic(search(alice, "q", "switchable"));
        props.setEnabled(true);
        assertThat(search(alice, "q", "switchable").get("mode").asString()).isEqualTo("search");
    }

    @Test
    void whenTheEngineFailsTheSearchStillAnswers_andRecoversByItself() throws Exception {
        UUID d = doc("Resilient doc", "r.txt", "x");
        index();
        Mockito.doThrow(new SearchEngineException("engine down")).when(spyEngine).search(anyString(), any(), any());
        JsonNode r = search(alice, "q", "resilient");
        assertBasic(r);
        assertThat(ids(r)).containsExactly(d);

        Mockito.reset(spyEngine);
        assertThat(search(alice, "q", "resilient").get("mode").asString()).as("the next search is back on the index").isEqualTo("search");
    }

    @Test
    void anIndexThatWasEmptiedUnderneathIsNotAnEmptyAnswer() throws Exception {
        UUID d = doc("Still there", "t.txt", "x");
        index();
        // the whole index disappears (an operator dropped it): the tenant is marked ready but the alias is gone
        engine.deleteIndex(indexAdmin.currentIndex());
        indexAdmin.forgetTenantAliases();
        JsonNode r = search(alice, "q", "there");
        assertBasic(r);
        assertThat(ids(r)).as("not a misleading 'No results found'").containsExactly(d);
    }

    @Test
    void theFallbackShowsExactlyWhatBrowsingShows() throws Exception {
        UUID open = doc("Common ground", "o.txt", "x");
        UUID secret = doc("Common secret", "s.txt", "x");
        UUID binned = doc("Common binned", "b.txt", "x");
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", secret);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) values (?, ?, 'user', ?, 'manage')",
                tenantId, secret, alice.getUserId());
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", binned);

        JsonNode bobs = search(bob, "q", "common");
        assertBasic(bobs);
        assertThat(ids(bobs)).containsExactly(open);
        List<UUID> browse = new ArrayList<>();
        get200("/api/v1/documents?all=true&q=common&size=50", bob).get("content")
                .forEach(x -> browse.add(UUID.fromString(x.get("documentId").asString())));
        assertThat(ids(bobs)).as("the same set browsing gives bob").containsExactlyInAnyOrderElementsOf(browse);
        assertThat(ids(search(alice, "q", "common"))).containsExactlyInAnyOrder(open, secret);
    }

    @Test
    void archivedDocumentsInTheFallbackNeedThePermissionToo() throws Exception {
        UUID active = doc("Lifecycle active", "a.txt", "x");
        UUID archived = doc("Lifecycle archived", "r.txt", "x");
        fx.jdbc().update("update documents set lifecycle_state = 'archived' where document_id = ?", archived);

        searchExpecting(403, alice, "q", "lifecycle", "status", "archived");
        assertThat(ids(search(alice, "q", "lifecycle"))).containsExactly(active);
        JsonNode arch = search(archivist, "q", "lifecycle", "status", "archived");
        assertBasic(arch);
        assertThat(ids(arch)).containsExactly(archived);
        JsonNode all = search(archivist, "q", "lifecycle", "status", "all");
        assertThat(ids(all)).as("active and archive cannot be merged in basic mode").containsExactly(active);
        assertThat(all.toString()).contains("inactive and archived documents");
    }

    @Test
    void theFallbackPagesAndTellsWhichFiltersItCouldNotApply() throws Exception {
        for (int i = 0; i < 5; i++) {
            doc("Paged " + i, "p" + i + ".txt", "x");
        }
        JsonNode page = search(alice, "q", "paged", "size", 2, "sort", "title", "dir", "asc");
        List<String> seen = new ArrayList<>(titles(page));
        while (!page.get("nextCursor").isNull()) {
            page = search(alice, "q", "paged", "size", 2, "sort", "title", "dir", "asc", "cursor", page.get("nextCursor").asString());
            seen.addAll(titles(page));
        }
        assertThat(seen).containsExactly("Paged 0", "Paged 1", "Paged 2", "Paged 3", "Paged 4");

        JsonNode filtered = search(alice, "q", "paged", "createdFrom", "2020-01-01", "meta", UUID.randomUUID() + ":eq:x");
        assertThat(noticeCodes(filtered)).contains("FILTERS_NOT_APPLIED", "FILTER_NO_LONGER_EXISTS");
        assertThat(filtered.toString()).contains("created date");
    }

    @Test
    void thePermissionMatrixHoldsInBasicModeToo() throws Exception {
        doc("Anything", "a.txt", "x");
        searchExpecting(403, carol, "q", "anything");
        assertBasic(search(alice, "q", "anything"));
    }
}
