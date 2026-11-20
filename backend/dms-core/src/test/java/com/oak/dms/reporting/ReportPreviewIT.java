package com.oak.dms.reporting;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The catalogue and the preview through the real application: security annotations, JSON binding, the real access service, the
 * repeatable-read snapshot and the administrator's settings taking effect on the next request (brief section 7: access,
 * cross-tenant, settings effects).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class ReportPreviewIT extends WorkflowApiSupport {

    static final String[] BUILDER = {"docm.documents.view", "crg.generate-custom-reports.build", "crg.generate-custom-reports.run"};
    static final String[] RUN_ONLY = {"docm.documents.view", "crg.generate-custom-reports.run"};
    static final String[] BUILD_WITHOUT_VIEW = {"crg.generate-custom-reports.build"};

    private UUID tenantId;
    private UUID typeId;
    private User owner;
    private User builder;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        typeId = fx.documentType(tenantId, "Contract");
        owner = fx.user(tenantId, "Owner");
        builder = fx.user(tenantId, "Builder");
        fx.role(tenantId, "Builder " + UUID.randomUUID(), BUILDER, owner, builder);
    }

    private static Map<String, Object> definition(List<String> columns, Object... filtersAndSort) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "documents");
        body.put("columns", columns);
        body.put("filters", filtersAndSort.length == 0 ? List.of() : filtersAndSort[0]);
        if (filtersAndSort.length > 1) {
            body.put("sort", filtersAndSort[1]);
        }
        return body;
    }

    private static Map<String, Object> filter(String field, String operator, String... values) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", field);
        f.put("operator", operator);
        f.put("values", List.of(values));
        return f;
    }

    private JsonNode preview(User as, Map<String, Object> body, int expected) throws Exception {
        return postAs("/api/v1/reports/preview", as, body, expected);
    }

    private static List<String> firstColumn(JsonNode response) {
        List<String> values = new ArrayList<>();
        response.get("sample").forEach(row -> values.add(row.get(0).asString()));
        return values;
    }

    private void restrictToOwner(UUID document) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", document);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) "
                + "values (?, ?, 'user', ?, 'manage')", tenantId, document, owner.getUserId());
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void aBuilderPreviewsTheirDocuments_withTotalColumnsAndSampleInTheRequestedOrder() throws Exception {
        fx.document(tenantId, typeId, owner, "Alpha contract");
        fx.document(tenantId, typeId, owner, "Beta contract");
        fx.document(tenantId, typeId, owner, "Gamma contract");

        JsonNode response = preview(builder, definition(List.of("title", "documentType", "owner", "approvalStatus"),
                List.of(filter("title", "contains", "contract")), List.of(Map.of("field", "title", "direction", "desc"))), 200);

        assertThat(response.get("total").asLong()).isEqualTo(3);
        assertThat(firstColumn(response)).containsExactly("Gamma contract", "Beta contract", "Alpha contract");
        assertThat(response.get("columns").get(1).get("label").asString()).isEqualTo("Document type");
        assertThat(response.get("sample").get(0).get(1).asString()).isEqualTo("Contract");
        assertThat(response.get("sample").get(0).get(2).asString()).isEqualTo("Owner");
        assertThat(response.get("sample").get(0).get(3).asString()).isEqualTo("Not submitted");
        assertThat(response.get("timedOut").asBoolean()).isFalse();
        assertThat(response.get("resolvedFilters").get(0).asString()).isEqualTo("Title: contains \"contract\"");
        assertThat(response.get("limitedToAccess").asBoolean()).isFalse();
    }

    @Test
    void theSampleIsAtMostFiftyRows_butTheTotalIsTheWholeCount() throws Exception {
        for (int i = 0; i < 53; i++) {
            fx.document(tenantId, typeId, owner, "Bulk " + i);
        }

        JsonNode response = preview(builder, definition(List.of("title")), 200);

        assertThat(response.get("total").asLong()).isEqualTo(53);
        assertThat(response.get("sample")).hasSize(50);
    }

    @Test
    void templatesAndRecycleBinDocumentsNeverAppear() throws Exception {
        fx.document(tenantId, typeId, owner, "Kept");
        UUID binned = fx.document(tenantId, typeId, owner, "Binned");
        UUID template = fx.document(tenantId, typeId, owner, "Template");
        fx.jdbc().update("update documents set is_deleted = true where document_id = ?", binned);
        fx.jdbc().update("update documents set is_template = true where document_id = ?", template);

        JsonNode response = preview(builder, definition(List.of("title")), 200);

        assertThat(firstColumn(response)).containsExactly("Kept");
        assertThat(response.get("total").asLong()).isEqualTo(1);
    }

    @Test
    void archivedDocumentsAreLeftOutUnlessTheDefinitionOrTheTenantSettingAsksForThem() throws Exception {
        UUID archived = fx.document(tenantId, typeId, owner, "Old");
        fx.document(tenantId, typeId, owner, "Current");
        fx.jdbc().update("update documents set lifecycle_state = 'archived' where document_id = ?", archived);

        assertThat(firstColumn(preview(builder, definition(List.of("title")), 200))).containsExactly("Current");

        Map<String, Object> inactive = definition(List.of("title"));
        inactive.put("includeInactive", true);
        assertThat(firstColumn(preview(builder, inactive, 200))).containsExactlyInAnyOrder("Old", "Current");
    }

    // ------------------------------------------------------------------ access

    @Test
    void aRestrictedDocumentIsAbsentFromRowsAndTotal_andNoHiddenCountIsEverReturned() throws Exception {
        fx.document(tenantId, typeId, owner, "Open document");
        UUID minutes = fx.document(tenantId, typeId, owner, "Board minutes");
        restrictToOwner(minutes);

        JsonNode asBuilder = preview(builder, definition(List.of("title")), 200);
        JsonNode asOwner = preview(owner, definition(List.of("title")), 200);

        assertThat(firstColumn(asBuilder)).containsExactly("Open document");
        assertThat(asBuilder.get("total").asLong()).as("the total counts only what they can see").isEqualTo(1);
        assertThat(asBuilder.get("limitedToAccess").asBoolean()).isTrue();
        assertThat(asBuilder.toString()).doesNotContainIgnoringCase("hidden").doesNotContain("Board minutes");
        assertThat(firstColumn(asOwner)).containsExactlyInAnyOrder("Open document", "Board minutes");
        assertThat(asOwner.get("total").asLong()).isEqualTo(2);
    }

    @Test
    void aFilterCannotBeUsedToProbeForARestrictedDocument() throws Exception {
        UUID minutes = fx.document(tenantId, typeId, owner, "Board minutes");
        restrictToOwner(minutes);

        JsonNode probe = preview(builder, definition(List.of("title"), List.of(filter("title", "equals", "Board minutes"))), 200);

        assertThat(probe.get("total").asLong()).isZero();
        assertThat(probe.get("sample")).isEmpty();
    }

    @Test
    void anotherOrganisationsDocumentsNeverAppear() throws Exception {
        fx.document(tenantId, typeId, owner, "Ours");
        UUID otherTenant = fx.tenant();
        User stranger = fx.user(otherTenant, "Stranger");
        fx.document(otherTenant, fx.documentType(otherTenant, "Contract"), stranger, "Theirs");

        JsonNode response = preview(builder, definition(List.of("title")), 200);

        assertThat(firstColumn(response)).containsExactly("Ours");
        assertThat(response.get("total").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void previewNeedsTheBuildPermission() throws Exception {
        User reader = fx.user(tenantId, "Reader");
        fx.role(tenantId, "Runner " + UUID.randomUUID(), RUN_ONLY, reader);

        JsonNode denied = preview(reader, definition(List.of("title")), 403);

        assertThat(denied.get("status").asInt()).isEqualTo(403);
    }

    @Test
    void theBuildGrantAloneIsNotAWayToSeeDocuments() throws Exception {
        User noView = fx.user(tenantId, "NoView");
        fx.role(tenantId, "Build only " + UUID.randomUUID(), BUILD_WITHOUT_VIEW, noView);
        fx.document(tenantId, typeId, owner, "Secret");

        JsonNode denied = preview(noView, definition(List.of("title")), 403);

        assertThat(denied.get("code").asString()).isEqualTo("REPORT_DOCUMENT_ACCESS_REQUIRED");
        assertThat(denied.toString()).doesNotContain("Secret");
    }

    @Test
    void catalogueNeedsBuildOrRun_andNobodyElseGetsIt() throws Exception {
        User reader = fx.user(tenantId, "Reader");
        fx.role(tenantId, "Viewer only " + UUID.randomUUID(), new String[]{"docm.documents.view"}, reader);
        User runner = fx.user(tenantId, "Runner");
        fx.role(tenantId, "Runner " + UUID.randomUUID(), RUN_ONLY, runner);

        call(get("/api/v1/reports/catalog"), fx.jwtFor(reader), null, 403);
        JsonNode asRunner = get200("/api/v1/reports/catalog", runner);

        assertThat(asRunner.get("capabilities").get("run").asBoolean()).isTrue();
        assertThat(asRunner.get("capabilities").get("build").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ the catalogue

    @Test
    void theCatalogueListsFieldsOperatorsLimitsAndWhatTheCallerMayDo() throws Exception {
        JsonNode catalog = get200("/api/v1/reports/catalog", builder);

        List<String> keys = new ArrayList<>();
        catalog.get("fields").forEach(f -> keys.add(f.get("key").asString()));
        assertThat(keys).contains("title", "documentType", "department", "owner", "status", "approvalStatus", "workflowStage",
                "createdAt", "createdBy", "modifiedAt", "modifiedBy", "lifecycleState", "folderPath", "versionNumber",
                "pageCount", "fileSize", "fileType", "legalHold", "retentionDue");
        JsonNode created = catalog.get("fields").get(keys.indexOf("createdAt"));
        List<String> operators = new ArrayList<>();
        created.get("operators").forEach(o -> operators.add(o.asString()));
        assertThat(operators).containsExactly("on", "before", "after", "between", "is_empty", "relative");
        assertThat(catalog.get("fields").get(keys.indexOf("folderPath")).get("filterable").asBoolean()).isFalse();
        assertThat(catalog.get("settings").get("limits").get("maxColumns").asInt()).isEqualTo(40);
        assertThat(catalog.get("settings").get("limits").get("maxSortKeys").asInt()).isEqualTo(3);
        assertThat(catalog.get("settings").get("allowedFormats").toString()).contains("csv");
        assertThat(catalog.get("capabilities").get("build").asBoolean()).isTrue();
        assertThat(catalog.get("capabilities").get("configure").asBoolean()).isFalse();
        assertThat(catalog.get("relativeDateOptions").toString()).contains("last_30_days");
    }

    // ------------------------------------------------------------------ validation

    @Test
    void aDefinitionThatIsNotValidIsRefusedWithAStableCodeAndTheOffendingField() throws Exception {
        JsonNode unknown = preview(builder, definition(List.of("title", "password_hash")), 422);
        assertThat(unknown.get("code").asString()).isEqualTo("REPORT_FIELD_UNAVAILABLE");
        assertThat(unknown.get("details").has("columns[1]")).isTrue();

        JsonNode operator = preview(builder, definition(List.of("title"), List.of(filter("pageCount", "contains", "1"))), 422);
        assertThat(operator.get("code").asString()).isEqualTo("REPORT_OPERATOR_INVALID");

        JsonNode value = preview(builder, definition(List.of("title"), List.of(filter("createdAt", "on", "yesterday-ish"))), 422);
        assertThat(value.get("code").asString()).isEqualTo("REPORT_VALUE_INVALID");

        Map<String, Object> badZone = definition(List.of("title"));
        badZone.put("timeZone", "Mars/Olympus");
        assertThat(preview(builder, badZone, 422).get("code").asString()).isEqualTo("REPORT_TIMEZONE_INVALID");
    }

    @Test
    void anEmptyDefinitionUsesTheOrganisationsDefaultColumns() throws Exception {
        fx.document(tenantId, typeId, owner, "Anything");

        JsonNode response = preview(builder, definition(List.of()), 200);

        assertThat(response.get("columns")).isNotEmpty();
        assertThat(response.get("total").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the administrator's settings take effect on the next request

    @Test
    void turningReportsOffForTheOrganisationRefusesTheNextRequest_andOnlyThatOrganisation() throws Exception {
        fx.document(tenantId, typeId, owner, "Doc");
        UUID otherTenant = fx.tenant();
        User otherBuilder = fx.user(otherTenant, "OtherBuilder");
        fx.role(otherTenant, "Builder " + UUID.randomUUID(), BUILDER, otherBuilder);

        fx.jdbc().update("insert into tenant_report_settings (tenant_id, enabled) values (?, false)", tenantId);

        assertThat(preview(builder, definition(List.of("title")), 403).get("code").asString()).isEqualTo("REPORTS_DISABLED");
        assertThat(call(get("/api/v1/reports/catalog"), fx.jwtFor(builder), null, 403).get("code").asString())
                .isEqualTo("REPORTS_DISABLED");
        preview(otherBuilder, definition(List.of("title")), 200);
    }

    @Test
    void aFieldTheAdministratorSwitchedOffDisappearsFromTheCatalogueAndCannotBeUsed() throws Exception {
        fx.jdbc().update("insert into report_field_settings (tenant_id, field_key, enabled) values (?, 'owner', false)", tenantId);

        JsonNode catalog = get200("/api/v1/reports/catalog", builder);
        List<String> keys = new ArrayList<>();
        catalog.get("fields").forEach(f -> keys.add(f.get("key").asString()));

        assertThat(keys).doesNotContain("owner").contains("title");
        assertThat(preview(builder, definition(List.of("title", "owner")), 422).get("code").asString())
                .isEqualTo("REPORT_FIELD_UNAVAILABLE");
        assertThat(preview(builder, definition(List.of("title"), List.of(filter("owner", "is_empty"))), 422).get("code").asString())
                .isEqualTo("REPORT_FIELD_UNAVAILABLE");
    }

    @Test
    void anAdministratorCanRelabelAField_andTheCatalogueAndTheSampleHeaderFollow() throws Exception {
        fx.jdbc().update("insert into report_field_settings (tenant_id, field_key, label_override) values (?, 'status', 'Draft state')",
                tenantId);
        fx.document(tenantId, typeId, owner, "Doc");

        JsonNode response = preview(builder, definition(List.of("title", "status")), 200);

        assertThat(response.get("columns").get(1).get("label").asString()).isEqualTo("Draft state");
    }
}
