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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Another organisation sees nothing of this one, whatever it asks for: results, filters built from this tenant's ids,
 * cursors, presets, thumbnails and the admin figures. Every refusal is the same 404 / 400 as for something that never existed.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchCrossTenantIT extends SearchApiSupport {

    private static final String PRESETS = "/api/v1/search/presets";

    @Test
    void anotherOrganisationNeverSeesOrReachesThisOnesData() throws Exception {
        // this organisation: documents, a folder, a metadata field, a preset
        UUID mine = doc("Zebra acquisition plan", "plan.txt", "zebra confidentialzzz");
        doc("Zebra second", "second.txt", "zebra");
        UUID folder = folder(null, "Finance");
        UUID field = metadataField("invoice_no", "text", true);
        fx.role(tenantId, "Presets", new String[]{"dsr.manage-saved-search-presets.create"}, alice);
        index();
        UUID preset = UUID.fromString(call(post(PRESETS), fx.jwtFor(alice),
                Map.of("name", "Mine", "criteria", Map.of("q", "zebra")), 201).path("presetId").asString());
        String cursor = search(alice, "q", "zebra", "size", 1).path("nextCursor").asString();
        assertThat(cursor).isNotBlank();

        // the other organisation, with its own zebra
        UUID otherTenant = fx.tenant();
        User stranger = fx.user(otherTenant, "Stranger");
        fx.role(otherTenant, "Admin-ish", new String[]{"dsr.search-documents", "docm.documents.view",
                "dsr.manage-saved-search-presets.create", "dsr.manage-saved-search-presets.update", "dsr.manage-saved-search-presets.delete"}, stranger);
        UUID otherType = fx.documentType(otherTenant, "Memo");
        fx.document(otherTenant, otherType, stranger, "Other zebra");
        backfill.run(otherTenant, "backfill");
        for (int i = 0; i < 20 && indexer.runOnce() > 0; i++) {
            // drain
        }
        engine.refresh(indexAdmin.writeAlias());
        var jwt = fx.jwtFor(stranger);

        // results: only its own
        JsonNode r = call(request("/api/v1/search", "q", "zebra"), jwt, null, 200);
        assertThat(titles(r)).containsExactly("Other zebra");
        assertThat(r.toString()).doesNotContain("acquisition").doesNotContain(mine.toString()).doesNotContain("confidentialzzz");
        assertThat(search(stranger, "q", "confidentialzzz").get("items")).isEmpty();

        // crafted filters built from this organisation's ids: dropped with a generic notice, never data
        JsonNode crafted = call(request("/api/v1/search", "q", "zebra", "type", reportType, "folderId", folder,
                "meta", field + ":eq:x"), jwt, null, 200);
        assertThat(titles(crafted)).containsExactly("Other zebra");
        assertThat(noticeCodes(crafted)).contains("FILTER_NO_LONGER_EXISTS");
        assertThat(crafted.toString()).doesNotContain("Finance").doesNotContain("Report").doesNotContain("invoice_no");

        // a cursor from another organisation is refused (and says nothing)
        JsonNode bad = call(request("/api/v1/search", "q", "zebra", "size", 1, "cursor", cursor), jwt, null, 400);
        assertThat(bad.toString()).doesNotContain("Zebra");

        // presets: this one's id is a plain 404 for the stranger, byte for byte like an unknown id
        call(get(PRESETS + "/" + preset), jwt, null, 404);
        call(put(PRESETS + "/" + preset), jwt, Map.of("name", "stolen"), 404);
        call(delete(PRESETS + "/" + preset), jwt, null, 404);
        call(post(PRESETS + "/" + preset + "/run"), jwt, null, 404);
        assertThat(call(get(PRESETS), jwt, null, 200).path("count").asInt()).isZero();
        assertThat(fx.count("select count(*) from saved_searches where tenant_id = ?", tenantId)).isEqualTo(1);

        // the picture of this organisation's document: not found for the stranger
        int thumbnail = mvc.perform(get("/api/v1/documents/" + mine + "/thumbnail").with(jwt)).andReturn().getResponse().getStatus();
        assertThat(thumbnail).isIn(403, 404);

        // the admin figures are the caller's own tenant's
        var strangerAdmin = fx.tenantAdminJwtFor(stranger);
        JsonNode status = call(get("/api/v1/admin/search/status"), strangerAdmin, null, 200);
        assertThat(status.path("pendingJobs").asLong()).isZero();
        assertThat(status.path("indexReady").asBoolean()).isTrue();
    }
}
