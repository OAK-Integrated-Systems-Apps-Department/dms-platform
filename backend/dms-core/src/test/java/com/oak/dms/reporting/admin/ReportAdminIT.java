package com.oak.dms.reporting.admin;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The report administrator's endpoints through the real application: settings with ceilings, field overrides as a full replacement,
 * every schedule (pause, resume, cancel, reassign), and the metadata-only list of all runs.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class ReportAdminIT extends WorkflowApiSupport {

    private static final String[] SCHEDULER = {"docm.documents.view", "crg.generate-custom-reports.build", "crg.generate-custom-reports.run",
            "crg.manage-report-templates.create", "crg.manage-report-templates.update", "crg.manage-report-templates.delete",
            "crg.schedule-automated-reports.create-schedule", "crg.schedule-automated-reports.update-cancel-schedule"};
    private static final String[] ADMIN = {"docm.documents.view", "crg.generate-custom-reports.build", "crg.generate-custom-reports.run",
            "crg.configure-reports.manage"};
    private static final String[] AUDITOR = {"docm.documents.view", "crg.generate-custom-reports.run", "crg.view-all-runs.view"};
    private static final String[] RUN_ONLY = {"docm.documents.view", "crg.generate-custom-reports.run"};

    @MockitoBean
    KeycloakUserService keycloak;

    private UUID tenantId;
    private User ana;
    private User ben;
    private User admin;
    private User auditor;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        fx.documentType(tenantId, "Contract");
        ana = fx.user(tenantId, "Ana");
        ben = fx.user(tenantId, "Ben");
        admin = fx.user(tenantId, "Admin");
        auditor = fx.user(tenantId, "Auditor");
        fx.role(tenantId, "Schedulers " + UUID.randomUUID(), SCHEDULER, ana);
        fx.role(tenantId, "Runners " + UUID.randomUUID(), RUN_ONLY, ben);
        fx.role(tenantId, "Admins " + UUID.randomUUID(), ADMIN, admin);
        fx.role(tenantId, "Auditors " + UUID.randomUUID(), AUDITOR, auditor);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode getAs(String path, User as, int expected) throws Exception {
        return call(get(path), fx.jwtFor(as), null, expected);
    }

    private JsonNode putAs(String path, User as, Object body, int expected) throws Exception {
        return call(put(path), fx.jwtFor(as), body, expected);
    }

    private long audits(String action) {
        return fx.count("select count(*) from audit_events where tenant_id = ? and action = ?", tenantId, action);
    }

    private static Map<String, Object> definition() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "documents");
        body.put("columns", List.of("title", "status"));
        body.put("filters", List.of());
        body.put("sort", List.of(Map.of("field", "title", "direction", "asc")));
        return body;
    }

    private String template(User as) throws Exception {
        return postAs("/api/v1/reports/templates", as, Map.of("name", "T " + UUID.randomUUID(), "definition", definition()), 201)
                .get("id").asString();
    }

    private UUID schedule(User as, String templateId, List<Map<String, String>> recipients) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId);
        body.put("name", "S " + UUID.randomUUID());
        body.put("frequency", "daily");
        body.put("timeOfDay", "08:00");
        body.put("timeZone", "Asia/Colombo");
        body.put("format", "csv");
        body.put("deliveryChannels", List.of("in_app"));
        body.put("recipients", recipients);
        return UUID.fromString(postAs("/api/v1/reports/schedules", as, body, 201).get("id").asString());
    }

    private String status(UUID schedule) {
        return fx.jdbc().queryForObject("select status from report_schedules where schedule_id = ?", String.class, schedule);
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void everyAdminEndpointNeedsTheConfigurePermission() throws Exception {
        for (User nobody : List.of(ana, ben, auditor)) {
            getAs("/api/v1/reports/admin/settings", nobody, 403);
            putAs("/api/v1/reports/admin/settings", nobody, Map.of(), 403);
            getAs("/api/v1/reports/admin/fields", nobody, 403);
            putAs("/api/v1/reports/admin/fields", nobody, Map.of("overrides", List.of()), 403);
            getAs("/api/v1/reports/admin/schedules", nobody, 403);
            postAs("/api/v1/reports/admin/schedules/" + UUID.randomUUID() + "/pause", nobody, null, 403);
        }
        getAs("/api/v1/reports/admin/settings", admin, 200);
        getAs("/api/v1/reports/admin/runs", admin, 403); // configuring is not viewing everyone's runs
        getAs("/api/v1/reports/admin/runs", auditor, 200);
    }

    @Test
    void settingsCanBeReadAndSwitchedBackOnWhileReportsAreOff() throws Exception {
        JsonNode first = getAs("/api/v1/reports/admin/settings", admin, 200);
        assertThat(first.get("settings").get("enabled").asBoolean()).isTrue();
        assertThat(first.get("ceilings").get("maxRows").asInt()).isPositive();

        putAs("/api/v1/reports/admin/settings", admin, Map.of("enabled", false), 200);
        postAs("/api/v1/reports/templates", ana, Map.of("name", "x", "definition", definition()), 403);
        // the administrator is not locked out
        assertThat(getAs("/api/v1/reports/admin/settings", admin, 200).get("settings").get("enabled").asBoolean()).isFalse();
        getAs("/api/v1/reports/admin/fields", admin, 200);
        getAs("/api/v1/reports/admin/schedules", admin, 200);
        putAs("/api/v1/reports/admin/settings", admin, Map.of("enabled", true), 200);
        assertThat(audits("REPORT_SETTINGS_UPDATED")).isEqualTo(2);
    }

    @Test
    void settingsAboveTheCeilingAreRefusedPerFieldAndNothingIsAudited() throws Exception {
        int ceiling = getAs("/api/v1/reports/admin/settings", admin, 200).get("ceilings").get("maxRows").asInt();
        JsonNode refused = putAs("/api/v1/reports/admin/settings", admin, Map.of("maxRows", ceiling + 1), 422);
        assertThat(refused.get("code").asString()).isEqualTo("REPORT_SETTINGS_INVALID");
        assertThat(refused.toString()).contains("maxRows");
        assertThat(audits("REPORT_SETTINGS_UPDATED")).isZero();

        putAs("/api/v1/reports/admin/settings", admin, Map.of("maxRows", 500), 200);
        assertThat(getAs("/api/v1/reports/admin/settings", admin, 200).get("settings").get("maxRows").asInt()).isEqualTo(500);
        putAs("/api/v1/reports/admin/settings", admin, Map.of("maxRows", 500), 200); // unchanged: no new audit row
        assertThat(audits("REPORT_SETTINGS_UPDATED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ live sample

    private org.springframework.mock.web.MockHttpServletResponse sample(User as, String format, String body, int expected) throws Exception {
        return mvc.perform(post("/api/v1/reports/admin/settings/sample?format=" + format).with(fx.jwtFor(as))
                .contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(expected))
                .andReturn().getResponse();
    }

    @Test
    void aSampleShowsTheUnsavedSettingsAndNoOrganisationData() throws Exception {
        fx.document(tenantId, fx.documentType(tenantId, "Secret type"), ana, "Ana private payroll");
        var csv = sample(admin, "csv", "{\"csvDelimiter\":\";\",\"headerText\":\"ACME header\"}", 200);
        String text = csv.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("Supplier agreement").contains(";").doesNotContain("payroll").doesNotContain("Secret type");
        assertThat(csv.getHeader("Content-Disposition")).contains("sample-report.csv");
        var pdf = sample(admin, "pdf", "{}", 200);
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        // nothing was saved by asking for a sample
        assertThat(getAs("/api/v1/reports/admin/settings", admin, 200).get("settings").get("csvDelimiter").asString()).isNotEqualTo(";");
        assertThat(audits("REPORT_SETTINGS_UPDATED")).isZero();
    }

    @Test
    void aSampleRefusesBadSettingsAndOtherFormatsAndOutsiders() throws Exception {
        int ceiling = getAs("/api/v1/reports/admin/settings", admin, 200).get("ceilings").get("maxRows").asInt();
        sample(admin, "csv", "{\"maxRows\":" + (ceiling + 1) + "}", 422);
        sample(admin, "xlsx", "{}", 422);
        sample(ana, "csv", "{}", 403);
    }

    // ------------------------------------------------------------------ field overrides

    @Test
    void fieldOverridesAreReplacedAsAWholeAndAuditedOnlyWhenTheyChange() throws Exception {
        JsonNode all = getAs("/api/v1/reports/admin/fields", admin, 200);
        assertThat(all.size()).isGreaterThan(5);
        assertThat(all.findValuesAsString("key")).contains("title", "status", "owner");
        all.forEach(f -> assertThat(f.get("override").isNull()).isTrue());

        Map<String, Object> hideOwner = Map.of("fieldKey", "owner", "enabled", false);
        Map<String, Object> relabel = Map.of("fieldKey", "title", "labelOverride", "Document name");
        JsonNode saved = putAs("/api/v1/reports/admin/fields", admin, Map.of("overrides", List.of(hideOwner, relabel)), 200);
        assertThat(field(saved, "owner").get("override").get("enabled").asBoolean()).isFalse();
        assertThat(field(saved, "title").get("override").get("labelOverride").asString()).isEqualTo("Document name");
        assertThat(audits("REPORT_FIELD_SETTINGS_UPDATED")).isEqualTo(1);

        // the same list again changes nothing and is not audited
        putAs("/api/v1/reports/admin/fields", admin, Map.of("overrides", List.of(relabel, hideOwner)), 200);
        assertThat(audits("REPORT_FIELD_SETTINGS_UPDATED")).isEqualTo(1);

        // a field left out goes back to the code's defaults
        JsonNode replaced = putAs("/api/v1/reports/admin/fields", admin, Map.of("overrides", List.of(relabel)), 200);
        assertThat(field(replaced, "owner").get("override").isNull()).isTrue();
        assertThat(field(replaced, "title").get("override").get("labelOverride").asString()).isEqualTo("Document name");
        assertThat(fx.count("select count(*) from report_field_settings where tenant_id = ?", tenantId)).isEqualTo(1);
        assertThat(audits("REPORT_FIELD_SETTINGS_UPDATED")).isEqualTo(2);

        // an override that restates the code is not stored
        putAs("/api/v1/reports/admin/fields", admin, Map.of("overrides", List.of(Map.of("fieldKey", "status"))), 200);
        assertThat(fx.count("select count(*) from report_field_settings where tenant_id = ?", tenantId)).isZero();
    }

    @Test
    void invalidFieldOverridesAreRefusedWholeWithEveryProblemNamed() throws Exception {
        List<Map<String, Object>> bad = List.of(
                Map.of("fieldKey", "doesNotExist", "enabled", false),
                Map.of("fieldKey", "title", "labelOverride", "x".repeat(200)),
                Map.of("fieldKey", "status", "enabled", false, "defaultSelected", true));
        JsonNode refused = putAs("/api/v1/reports/admin/fields", admin, Map.of("overrides", bad), 422);
        assertThat(refused.get("code").asString()).isEqualTo("REPORT_FIELD_SETTINGS_INVALID");
        assertThat(refused.toString()).contains("doesNotExist", "title", "status");

        JsonNode twice = putAs("/api/v1/reports/admin/fields", admin,
                Map.of("overrides", List.of(Map.of("fieldKey", "title", "enabled", false), Map.of("fieldKey", "title", "enabled", false))), 422);
        assertThat(twice.toString()).contains("more than once");
        assertThat(fx.count("select count(*) from report_field_settings where tenant_id = ?", tenantId)).isZero();
        assertThat(audits("REPORT_FIELD_SETTINGS_UPDATED")).isZero();
    }

    @Test
    void aCatalogueWithNoColumnLeftIsRefused() throws Exception {
        List<Map<String, Object>> everything = new java.util.ArrayList<>();
        getAs("/api/v1/reports/admin/fields", admin, 200)
                .forEach(f -> everything.add(Map.of("fieldKey", f.get("key").asString(), "enabled", false)));
        JsonNode refused = putAs("/api/v1/reports/admin/fields", admin, Map.of("overrides", everything), 422);
        assertThat(refused.toString()).contains("At least one field");
    }

    @Test
    void aHiddenFieldIsGoneFromTheBuilderCatalogue() throws Exception {
        putAs("/api/v1/reports/admin/fields", admin, Map.of("overrides", List.of(Map.of("fieldKey", "owner", "enabled", false))), 200);
        JsonNode catalogue = getAs("/api/v1/reports/catalog", ana, 200);
        assertThat(catalogue.toString()).doesNotContain("\"key\":\"owner\"");
    }

    private static JsonNode field(JsonNode all, String key) {
        for (JsonNode f : all) {
            if (key.equals(f.get("key").asString())) {
                return f;
            }
        }
        throw new AssertionError("no field " + key);
    }

    // ------------------------------------------------------------------ schedules

    @Test
    void anAdministratorSeesEveryScheduleButNotItsRecipients() throws Exception {
        UUID s = schedule(ana, template(ana), List.of(Map.of("type", "user", "ref", ben.getUserId().toString())));
        JsonNode page = getAs("/api/v1/reports/admin/schedules", admin, 200);
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("id").asString()).isEqualTo(s.toString());
        assertThat(row.get("ownerUserId").asString()).isEqualTo(ana.getUserId().toString());
        assertThat(row.get("recipientCount").asLong()).isEqualTo(1);
        assertThat(page.toString()).doesNotContain(ben.getUserId().toString());
        assertThat(getAs("/api/v1/reports/admin/schedules?status=paused", admin, 200).get("content").size()).isZero();
        assertThat(getAs("/api/v1/reports/admin/schedules?ownerUserId=" + ben.getUserId(), admin, 200).get("content").size()).isZero();
    }

    @Test
    void pauseResumeAndCancelAnySchedule() throws Exception {
        UUID s = schedule(ana, template(ana), List.of());
        String base = "/api/v1/reports/admin/schedules/" + s;
        postAs(base + "/pause", admin, null, 204);
        assertThat(status(s)).isEqualTo("paused");
        assertThat(fx.jdbc().queryForObject("select pause_reason from report_schedules where schedule_id = ?", String.class, s)).isEqualTo("ADMIN");
        postAs(base + "/resume", admin, null, 204);
        assertThat(status(s)).isEqualTo("active");
        assertThat(fx.jdbc().queryForObject("select next_run_at is not null from report_schedules where schedule_id = ?", Boolean.class, s)).isTrue();
        postAs(base + "/cancel", admin, null, 204);
        assertThat(status(s)).isEqualTo("cancelled");
        assertThat(postAs(base + "/pause", admin, null, 409).get("code").asString()).isEqualTo("SCHEDULE_NOT_ACTIVE");
        assertThat(postAs("/api/v1/reports/admin/schedules/" + UUID.randomUUID() + "/pause", admin, null, 404).get("code").asString())
                .isEqualTo("SCHEDULE_NOT_FOUND");
        assertThat(audits("REPORT_SCHEDULE_PAUSED")).isEqualTo(1);
        assertThat(audits("REPORT_SCHEDULE_RESUMED")).isEqualTo(1);
        assertThat(audits("REPORT_SCHEDULE_CANCELLED")).isEqualTo(1);
    }

    @Test
    void aScheduleCannotBeResumedWhileItsOwnerIsGoneButCanBeReassigned() throws Exception {
        String templateId = template(ana);
        postAs("/api/v1/reports/templates/" + templateId + "/share", ana,
                Map.of("grants", List.of(Map.of("principalType", "user", "principalId", ben.getUserId().toString(), "accessLevel", "run"))), 200);
        UUID s = schedule(ana, templateId, List.of());
        fx.jdbc().update("update report_schedules set status = 'paused', pause_reason = 'OWNER_INACTIVE', next_run_at = null where schedule_id = ?", s);
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", ana.getUserId());

        String base = "/api/v1/reports/admin/schedules/" + s;
        assertThat(postAs(base + "/resume", admin, null, 409).get("code").asString()).isEqualTo("SCHEDULE_OWNER_INACTIVE");

        JsonNode moved = postAs(base + "/reassign", admin, Map.of("newOwnerUserId", ben.getUserId().toString()), 200);
        assertThat(moved.get("ownerUserId").asString()).isEqualTo(ben.getUserId().toString());
        assertThat(moved.get("status").asString()).isEqualTo("active");
        assertThat(audits("REPORT_SCHEDULE_UPDATED")).isEqualTo(1);
        String payload = fx.jdbc().queryForObject("select new_value::text from audit_events where action = 'REPORT_SCHEDULE_UPDATED' and tenant_id = ?",
                String.class, tenantId);
        assertThat(payload).contains("adminAction").contains("toUser");
    }

    @Test
    void reassignChecksTheNewOwnerTheWayCreatingChecked() throws Exception {
        String templateId = template(ana);
        UUID s = schedule(ana, templateId, List.of());
        String base = "/api/v1/reports/admin/schedules/" + s + "/reassign";
        User nobody = fx.user(tenantId, "Nobody");
        User gone = fx.user(tenantId, "Gone");
        fx.role(tenantId, "Gone runners " + UUID.randomUUID(), RUN_ONLY, gone);
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", gone.getUserId());

        assertThat(postAs(base, admin, Map.of(), 422).get("code").asString()).isEqualTo("REASSIGN_OWNER_REQUIRED");
        assertThat(postAs(base, admin, Map.of("newOwnerUserId", UUID.randomUUID().toString()), 422).get("code").asString())
                .isEqualTo("REASSIGN_OWNER_INACTIVE");
        assertThat(postAs(base, admin, Map.of("newOwnerUserId", gone.getUserId().toString()), 422).get("code").asString())
                .isEqualTo("REASSIGN_OWNER_INACTIVE");
        assertThat(postAs(base, admin, Map.of("newOwnerUserId", nobody.getUserId().toString()), 422).get("code").asString())
                .isEqualTo("REASSIGN_OWNER_CANNOT_RUN");
        // ben may run reports but cannot see Ana's private template
        assertThat(postAs(base, admin, Map.of("newOwnerUserId", ben.getUserId().toString()), 422).get("code").asString())
                .isEqualTo("REASSIGN_OWNER_NO_TEMPLATE_ACCESS");
        assertThat(fx.jdbc().queryForObject("select owner_user_id from report_schedules where schedule_id = ?", UUID.class, s)).isEqualTo(ana.getUserId());
        assertThat(audits("REPORT_SCHEDULE_UPDATED")).isZero();
    }

    @Test
    void aScheduleWithExternalRecipientsOnlyGoesToSomeoneWhoMayAdministerReports() throws Exception {
        User other = fx.user(tenantId, "Other");
        fx.role(tenantId, "Other schedulers " + UUID.randomUUID(), SCHEDULER, other);
        String templateId = postAs("/api/v1/reports/templates", ana, Map.of("name", "Shared " + UUID.randomUUID(), "definition", definition()), 201)
                .get("id").asString();
        postAs("/api/v1/reports/templates/" + templateId + "/share", ana, Map.of("grants", List.of(
                Map.of("principalType", "user", "principalId", other.getUserId().toString(), "accessLevel", "run"),
                Map.of("principalType", "user", "principalId", admin.getUserId().toString(), "accessLevel", "run"))), 200);
        UUID s = schedule(ana, templateId, List.of());
        // an external recipient is stored by hash; add it directly, as only an administrator could when creating
        fx.jdbc().update("insert into report_schedule_recipients (schedule_id, tenant_id, recipient_type, recipient_ref) values (?, '" + tenantId + "', 'email', 'partner@example.com')", s);

        String base = "/api/v1/reports/admin/schedules/" + s + "/reassign";
        assertThat(postAs(base, admin, Map.of("newOwnerUserId", other.getUserId().toString()), 422).get("code").asString())
                .isEqualTo("REASSIGN_OWNER_CANNOT_RUN");
        postAs(base, admin, Map.of("newOwnerUserId", admin.getUserId().toString()), 200);
    }

    // ------------------------------------------------------------------ runs

    @Test
    void allRunsAreListedAsMetadataOnlyAndCannotBeDownloaded() throws Exception {
        JsonNode started = postAs("/api/v1/reports/runs", ana, Map.of("definition", definition(), "format", "csv"), 202);
        String runId = started.get("runId").asString();
        JsonNode page = getAs("/api/v1/reports/admin/runs", auditor, 200);
        assertThat(page.get("content").size()).isEqualTo(1);
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("runId").asString()).isEqualTo(runId);
        assertThat(row.get("requestedBy").asString()).isEqualTo(ana.getUserId().toString());
        for (String leaked : List.of("filters", "definition", "filePath", "downloadUrl", "storageKey")) {
            assertThat(row.has(leaked)).as(leaked).isFalse();
        }
        assertThat(getAs("/api/v1/reports/admin/runs?requestedBy=" + ben.getUserId(), auditor, 200).get("content").size()).isZero();
        assertThat(getAs("/api/v1/reports/admin/runs?status=failed", auditor, 200).get("content").size()).isZero();
        // an administrator's view-all permission is no way to someone else's file
        call(get("/api/v1/reports/runs/" + runId + "/download"), fx.jwtFor(auditor), null, 404);
        call(post("/api/v1/reports/runs/" + runId + "/cancel"), fx.jwtFor(auditor), null, 404);
    }

    // ------------------------------------------------------------------ follow-ups

    @Test
    void reassigningTellsTheNewOwner_withoutNamesOrAddressesInTheNotice() throws Exception {
        String templateId = template(ana);
        postAs("/api/v1/reports/templates/" + templateId + "/share", ana,
                Map.of("grants", List.of(Map.of("principalType", "user", "principalId", ben.getUserId().toString(), "accessLevel", "run"))), 200);
        UUID s = schedule(ana, templateId, List.of());

        postAs("/api/v1/reports/admin/schedules/" + s + "/reassign", admin, Map.of("newOwnerUserId", ben.getUserId().toString()), 200);

        assertThat(fx.count("select count(*) from notifications where recipient_user_id = ? and category_code = 'report.schedule-assigned'",
                ben.getUserId())).isEqualTo(1);
        assertThat(fx.count("select count(*) from notifications where recipient_user_id = ? and category_code = 'report.schedule-assigned'",
                ana.getUserId())).as("the previous owner is not sent this notice").isZero();
        // Handing it to the person who already owns it changes nothing and tells nobody.
        postAs("/api/v1/reports/admin/schedules/" + s + "/reassign", admin, Map.of("newOwnerUserId", ben.getUserId().toString()), 200);
        assertThat(fx.count("select count(*) from notifications where recipient_user_id = ? and category_code = 'report.schedule-assigned'",
                ben.getUserId())).isEqualTo(1);
    }

    @Test
    void theFieldListCountsOfficialTemplatesAndLiveSchedulesThatUseEachField() throws Exception {
        String official = template(ana);
        fx.jdbc().update("update report_templates set scope = 'official', owner_user_id = null where template_id = ?", UUID.fromString(official));
        schedule(ana, official, List.of());
        UUID cancelled = schedule(ana, official, List.of());
        fx.jdbc().update("update report_schedules set status = 'cancelled' where schedule_id = ?", cancelled);
        String privateOne = template(ana);   // another person's private template is not counted as official
        schedule(ana, privateOne, List.of());

        JsonNode fields = getAs("/api/v1/reports/admin/fields", admin, 200);

        JsonNode title = field(fields, "title");
        assertThat(title.get("officialTemplatesUsing").asInt()).isEqualTo(1);
        assertThat(title.get("schedulesUsing").asInt()).as("two live schedules, the cancelled one is not counted").isEqualTo(2);
        JsonNode unused = field(fields, "owner");
        assertThat(unused.get("officialTemplatesUsing").asInt()).isZero();
        assertThat(unused.get("schedulesUsing").asInt()).isZero();
        assertThat(fields.toString()).as("counts only: no template names").doesNotContain("\"T ");
    }

    @Test
    void theCatalogueStatesTheFailureRuleSchedulesFollow() throws Exception {
        assertThat(getAs("/api/v1/reports/catalog", ana, 200).get("settings").get("pauseAfterConsecutiveFailures").asInt()).isEqualTo(5);
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, pause_after_consecutive_failures) values (?, 2)", tenantId);
        assertThat(getAs("/api/v1/reports/catalog", ana, 200).get("settings").get("pauseAfterConsecutiveFailures").asInt()).isEqualTo(2);
    }

    @Test
    void thePickersSearchPeopleAndRolesByNameOnServer_withOnlyTheConfigurePermission() throws Exception {
        User anna = fx.user(tenantId, "Annabel Zed");
        User inactive = fx.user(tenantId, "Annabel Gone");
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", inactive.getUserId());
        fx.role(tenantId, "Zed Auditors", RUN_ONLY, anna);

        JsonNode people = getAs("/api/v1/reports/admin/principals?type=user&q=annabel", admin, 200);
        assertThat(people).hasSize(1);
        assertThat(people.get(0).get("value").asString()).isEqualTo(anna.getUserId().toString());
        JsonNode roles = call(get("/api/v1/reports/admin/principals?type=role&q={q}", "zed aud"), fx.jwtFor(admin), null, 200);
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).get("label").asString()).startsWith("Zed Auditors");
        assertThat(call(get("/api/v1/reports/admin/principals?type=user&q={q}", "%"), fx.jwtFor(admin), null, 200)).as("a wildcard is literal").isEmpty();
        assertThat(getAs("/api/v1/reports/admin/principals?type=team", admin, 422).get("code").asString()).isEqualTo("REPORT_PRINCIPAL_TYPE");
        getAs("/api/v1/reports/admin/principals?type=user", ana, 403);
    }
}
