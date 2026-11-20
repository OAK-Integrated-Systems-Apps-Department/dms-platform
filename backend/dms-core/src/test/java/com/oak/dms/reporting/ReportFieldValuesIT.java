package com.oak.dms.reporting;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The picker behind a report filter: paged, searchable, tenant-scoped, and never a way to read a typed field's values. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class ReportFieldValuesIT extends WorkflowApiSupport {

    @MockitoBean
    KeycloakUserService keycloak;

    private UUID tenantId;
    private User builder;
    private User viewer;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        builder = fx.user(tenantId, "Builder");
        viewer = fx.user(tenantId, "Viewer");
        fx.role(tenantId, "Builders " + UUID.randomUUID(), new String[]{"docm.documents.view", "crg.generate-custom-reports.build"}, builder);
        fx.role(tenantId, "Viewers " + UUID.randomUUID(), new String[]{"docm.documents.view"}, viewer);
    }

    private JsonNode values(User as, String key, String query, int expected) throws Exception {
        return call(get("/api/v1/reports/catalog/fields/" + key + "/values" + (query == null ? "" : "?" + query)), fx.jwtFor(as), null, expected);
    }

    private JsonNode search(String text) throws Exception {
        return call(get("/api/v1/reports/catalog/fields/documentType/values?q={q}", text), fx.jwtFor(builder), null, 200);
    }

    @Test
    void needsTheBuildOrRunPermission() throws Exception {
        values(viewer, "status", null, 403);
        values(builder, "status", null, 200);
    }

    @Test
    void fixedListsAreSearchable() throws Exception {
        JsonNode all = values(builder, "status", null, 200);
        assertThat(all.get("content").findValuesAsString("value")).contains("draft", "published", "archived");
        assertThat(all.get("hasMore").asBoolean()).isFalse();
        JsonNode some = values(builder, "approvalStatus", "q=approved", 200);
        assertThat(some.get("content").findValuesAsString("value")).containsExactly("approved");
    }

    @Test
    void unknownAndTypedFieldsAreRefused() throws Exception {
        assertThat(values(builder, "nope", null, 404).get("code").asString()).isEqualTo("REPORT_FIELD_NOT_FOUND");
        assertThat(values(builder, "title", null, 422).get("code").asString()).isEqualTo("REPORT_FIELD_HAS_NO_CHOICES");
    }

    @Test
    void manyChoicesArePagedWithoutRepeatsOrGaps() throws Exception {
        Set<String> made = new LinkedHashSet<>();
        for (int i = 0; i < 120; i++) {
            String name = "Kind %03d".formatted(i);
            fx.documentType(tenantId, name);
            made.add(name);
        }
        List<String> seen = new ArrayList<>();
        for (int page = 0; ; page++) {
            JsonNode p = values(builder, "documentType", "q=Kind&size=50&page=" + page, 200);
            assertThat(p.get("content").size()).isLessThanOrEqualTo(50);
            p.get("content").forEach(v -> seen.add(v.get("label").asString()));
            if (!p.get("hasMore").asBoolean()) {
                assertThat(page).isEqualTo(2);
                break;
            }
            assertThat(p.get("content").size()).isEqualTo(50);
            assertThat(page).isLessThan(5);
        }
        assertThat(seen).containsExactlyElementsOf(made);
        // an oversized page is capped
        assertThat(values(builder, "documentType", "q=Kind&size=5000", 200).get("content").size()).isEqualTo(50);
    }

    @Test
    void searchTreatsWildcardsAsPlainText() throws Exception {
        fx.documentType(tenantId, "100% Final");
        fx.documentType(tenantId, "Plain");
        fx.documentType(tenantId, "Under_score");
        assertThat(search("%").get("content").findValuesAsString("label")).containsExactly("100% Final");
        assertThat(search("_").get("content").findValuesAsString("label")).containsExactly("Under_score");
        assertThat(search("' or 1=1 --").get("content").size()).isZero();
    }

    @Test
    void onlyThisOrganisationsChoicesAreOffered() throws Exception {
        fx.documentType(tenantId, "Mine only");
        UUID otherTenant = fx.tenant();
        fx.documentType(otherTenant, "Theirs only");
        String labels = values(builder, "documentType", null, 200).toString();
        assertThat(labels).contains("Mine only").doesNotContain("Theirs only");
    }

    @Test
    void peopleAreOfferedByNameOnly() throws Exception {
        JsonNode people = values(builder, "owner", "q=builder", 200);
        assertThat(people.get("content").size()).isEqualTo(1);
        assertThat(people.get("content").get(0).get("value").asString()).isEqualTo(builder.getUserId().toString());
        assertThat(people.toString()).doesNotContain("@");
    }

    @Test
    void aHiddenFieldHasNoPicker() throws Exception {
        fx.jdbc().update("insert into report_field_settings (tenant_id, field_key, enabled, selectable, filterable, sortable, groupable, "
                + "default_selected, show_code) values (?, 'documentType', false, true, true, true, true, false, false)", tenantId);
        assertThat(values(builder, "documentType", null, 404).get("code").asString()).isEqualTo("REPORT_FIELD_NOT_FOUND");
    }
}
