package com.oak.dms.search.api;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A document the caller cannot open appears nowhere: not in results, highlights, totals, "did you mean", suggestions,
 * thumbnails, pin or bookmark marks, a preset's run, or the title-search fallback. Each place is asserted against a world
 * where the document does not exist at all.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchHiddenEverywhereIT extends SearchApiSupport {

    private void restrictTo(UUID document, User... members) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", document);
        for (User m : members) {
            fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) values (?, ?, 'user', ?, 'manage')",
                    tenantId, document, m.getUserId());
        }
    }

    @Test
    void theHiddenDocumentIsNowhere() throws Exception {
        UUID shown = doc("Zephyr public note", "public.txt", "zephyr open");
        UUID hidden = doc("Zephyr acquisition plan", "plan.txt", "zephyrsecret takeover confidentialzzz");
        restrictTo(hidden, alice);   // bob cannot open it
        fx.jdbc().update("insert into document_bookmarks (tenant_id, user_id, document_id) values (?, ?, ?)", tenantId, bob.getUserId(), hidden);
        index();

        fx.role(tenantId, "Presets", new String[]{"dsr.manage-saved-search-presets.create"}, bob);
        UUID preset = UUID.fromString(call(post("/api/v1/search/presets"), fx.jwtFor(bob),
                Map.of("name", "Secret words", "criteria", Map.of("q", "zephyrsecret")), 201).path("presetId").asString());

        // 1. a search that can only match the hidden document looks exactly like one that matches nothing
        JsonNode onlyHidden = search(bob, "q", "zephyrsecret");
        JsonNode nothing = search(bob, "q", "qqqqnothingqqqq");
        assertThat(onlyHidden.get("items")).isEmpty();
        assertThat(onlyHidden.toString()).isEqualTo(nothing.toString());

        // 2. a search that matches both: only the visible one, totals count only what the caller can see, no highlights of the other
        JsonNode both = search(bob, "q", "zephyr");
        assertThat(titles(both)).containsExactly("Zephyr public note");
        assertThat(both.path("total").path("value").asLong()).isEqualTo(1);
        assertThat(both.toString()).doesNotContain("acquisition").doesNotContain("confidentialzzz").doesNotContain(hidden.toString());

        // 3. highlights: the body word of the hidden document is nowhere in anything bob receives
        assertThat(search(bob, "q", "confidentialzzz").toString()).doesNotContain("takeover").doesNotContain("acquisition");

        // 4. did you mean: a typo of a word that exists only in the hidden document is not corrected to it
        JsonNode typo = search(bob, "q", "zephyrsecrt");
        assertThat(typo.get("didYouMean").isNull()).isTrue();

        // 5. suggestions
        JsonNode suggestions = call(request("/api/v1/search/suggest", "q", "zephyr"), fx.jwtFor(bob), null, 200);
        assertThat(suggestions.toString()).doesNotContain("acquisition").doesNotContain(hidden.toString());
        assertThat(suggestions.path("suggestions")).hasSize(1);

        // 6. thumbnails and marks: none for the hidden one, and its picture is refused when asked for directly
        for (JsonNode item : both.get("items")) {
            assertThat(item.path("thumbnailUrl").asString()).doesNotContain(hidden.toString());
            assertThat(item.path("bookmarked").asBoolean()).as("the stale bookmark of a hidden document marks nothing").isFalse();
        }
        int thumbnail = mvc.perform(get("/api/v1/documents/" + hidden + "/thumbnail").with(fx.jwtFor(bob))).andReturn().getResponse().getStatus();
        assertThat(thumbnail).isIn(403, 404);

        // 7. a preset saved around the hidden word runs to the very same empty answer; presets hold no document ids
        JsonNode run = call(post("/api/v1/search/presets/" + preset + "/run"), fx.jwtFor(bob), null, 200);
        assertThat(run.path("result").get("items")).isEmpty();
        assertThat(run.path("result").path("total").path("value").asLong()).isZero();
        assertThat(run.toString()).doesNotContain("acquisition").doesNotContain(hidden.toString());
        assertThat(fx.jdbc().queryForObject("select criteria::text from saved_searches where preset_id = ?", String.class, preset))
                .doesNotContain(hidden.toString()).doesNotContain(shown.toString());

        // 8. the title-search fallback shows the same: nothing of the hidden document
        tenantState.setEnabled(tenantId, false);
        try {
            JsonNode basic = search(bob, "q", "Zephyr");
            assertThat(basic.path("mode").asString()).isEqualTo("basic");
            assertThat(titles(basic)).containsExactly("Zephyr public note");
            assertThat(basic.toString()).doesNotContain("acquisition").doesNotContain(hidden.toString());
            assertThat(basic.path("total").path("value").asLong()).isEqualTo(1);
        } finally {
            tenantState.setEnabled(tenantId, true);
        }

        // 9. and the person who may open it does see it, everywhere
        JsonNode asAlice = search(alice, "q", "zephyr");
        assertThat(titles(asAlice)).containsExactlyInAnyOrder("Zephyr public note", "Zephyr acquisition plan");
        assertThat(asAlice.path("total").path("value").asLong()).isEqualTo(2);
    }
}
