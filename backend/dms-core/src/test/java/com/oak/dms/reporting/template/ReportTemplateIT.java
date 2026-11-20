package com.oak.dms.reporting.template;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Report templates through the real application: who sees what, sharing, publishing and locking, soft delete with schedules, and
 * running a template - on a real database, with the real permission checks.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class ReportTemplateIT extends WorkflowApiSupport {

    private static final String[] MEMBER = {"docm.documents.view", "crg.generate-custom-reports.build", "crg.generate-custom-reports.run",
            "crg.manage-report-templates.create", "crg.manage-report-templates.update", "crg.manage-report-templates.delete"};
    private static final String[] PUBLISHER = {"docm.documents.view", "crg.generate-custom-reports.build", "crg.generate-custom-reports.run",
            "crg.manage-report-templates.create", "crg.manage-report-templates.update", "crg.manage-report-templates.delete",
            "crg.manage-report-templates.publish"};
    private static final String[] RUN_ONLY = {"docm.documents.view", "crg.generate-custom-reports.run"};

    /** Background work asks Keycloak for realm roles, live; the fixture users are not in Keycloak, so it answers "no elevated role". */
    @MockitoBean
    KeycloakUserService keycloak;

    private UUID tenantId;
    private UUID typeId;
    private User ana;
    private User ben;
    private User runner;
    private User publisher;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        typeId = fx.documentType(tenantId, "Contract");
        ana = fx.user(tenantId, "Ana");
        ben = fx.user(tenantId, "Ben");
        runner = fx.user(tenantId, "Runner");
        publisher = fx.user(tenantId, "Publisher");
        fx.role(tenantId, "Members " + UUID.randomUUID(), MEMBER, ana, ben);
        fx.role(tenantId, "Runners " + UUID.randomUUID(), RUN_ONLY, runner);
        fx.role(tenantId, "Publishers " + UUID.randomUUID(), PUBLISHER, publisher);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> definition(String... titleContains) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "documents");
        body.put("columns", List.of("title", "status"));
        List<Map<String, Object>> filters = new ArrayList<>();
        for (String text : titleContains) {
            filters.add(Map.of("field", "title", "operator", "contains", "values", List.of(text)));
        }
        body.put("filters", filters);
        body.put("sort", List.of(Map.of("field", "title", "direction", "asc")));
        return body;
    }

    private static Map<String, Object> template(String name, Map<String, Object> definition) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "for tests");
        body.put("definition", definition);
        return body;
    }

    private JsonNode create(User as, String name, Map<String, Object> definition, int expected) throws Exception {
        return postAs("/api/v1/reports/templates", as, template(name, definition), expected);
    }

    private String createId(User as, String name) throws Exception {
        return create(as, name, definition(), 201).get("id").asString();
    }

    private static Map<String, Object> grant(String type, UUID id, String level) {
        return Map.of("principalType", type, "principalId", id.toString(), "accessLevel", level);
    }

    private JsonNode share(User as, String id, List<Map<String, Object>> grants, int expected) throws Exception {
        return postAs("/api/v1/reports/templates/" + id + "/share", as, Map.of("grants", grants), expected);
    }

    private JsonNode publish(User as, String id, boolean locked, List<String> editable, List<Map<String, Object>> audience, int expected)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locked", locked);
        body.put("userEditableFilters", editable);
        body.put("audience", audience);
        return postAs("/api/v1/reports/templates/" + id + "/publish", as, body, expected);
    }

    private JsonNode getTemplate(User as, String id, int expected) throws Exception {
        return call(get("/api/v1/reports/templates/" + id), fx.jwtFor(as), null, expected);
    }

    private List<String> visibleNames(User as) throws Exception {
        List<String> names = new ArrayList<>();
        get200("/api/v1/reports/templates", as).forEach(t -> names.add(t.get("name").asString()));
        return names;
    }

    private JsonNode runTemplate(User as, String templateId, Object overrides, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId);
        body.put("format", "csv");
        if (overrides != null) {
            body.put("filterOverrides", overrides);
        }
        return postAs("/api/v1/reports/runs", as, body, expected);
    }

    private JsonNode await(User as, String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000;
        while (true) {
            JsonNode run = get200("/api/v1/reports/runs/" + runId, as);
            String status = run.get("status").asString();
            if (!status.equals("queued") && !status.equals("running")) {
                return run;
            }
            assertThat(System.currentTimeMillis()).as("run " + runId + " still " + status).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private String file(User as, String runId) throws Exception {
        MockHttpServletResponse response = getRaw("/api/v1/reports/runs/" + runId + "/download", fx.jwtFor(as));
        assertThat(response.getStatus()).isEqualTo(200);
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private long audits(String objectId, String action) {
        return fx.count("select count(*) from audit_events where object_id = ?::uuid and action = ?", objectId, action);
    }

    private UUID insertSchedule(String templateId, User owner) {
        UUID id = UUID.randomUUID();
        fx.jdbc().update("insert into report_schedules (schedule_id, tenant_id, template_id, owner_user_id, name, frequency, time_of_day, "
                + "time_zone, format, delivery_channels, status, next_run_at) values (?, ?, ?::uuid, ?, 'Weekly', 'daily', '08:00', 'UTC', "
                + "'csv', ARRAY['in_app'], 'active', now() + interval '1 day')", id, tenantId, templateId, owner.getUserId());
        return id;
    }

    // ------------------------------------------------------------------ create, read, update

    @Test
    void aTemplateIsSavedReadBackAndAuditedWithoutItsFilterValues() throws Exception {
        JsonNode created = create(ana, "Open contracts", definition("secret-client-name"), 201);
        String id = created.get("id").asString();

        assertThat(created.get("scope").asString()).isEqualTo("private");
        assertThat(created.get("access").asString()).isEqualTo("owner");
        JsonNode read = getTemplate(ana, id, 200);
        assertThat(read.get("definition").get("columns")).hasSize(2);
        assertThat(read.get("definition").get("filters").get(0).get("values").get(0).asString()).isEqualTo("secret-client-name");
        assertThat(audits(id, "REPORT_TEMPLATE_CREATED")).isEqualTo(1);
        String payload = fx.jdbc().queryForObject("select coalesce(new_value::text, '') from audit_events where object_id = ?::uuid "
                + "and action = 'REPORT_TEMPLATE_CREATED'", String.class, id);
        assertThat(payload).contains("title contains").doesNotContain("secret-client-name");
    }

    @Test
    void namesAreUniquePerOwnerIgnoringCase_butAnotherPersonMayUseTheSame() throws Exception {
        createId(ana, "Quarterly");

        assertThat(create(ana, "  quarterly ", definition(), 409).get("code").asString()).isEqualTo("TEMPLATE_NAME_TAKEN");
        create(ben, "Quarterly", definition(), 201);
    }

    @Test
    void aDefinitionTheCatalogueCannotRunIsRefusedWhenSaved() throws Exception {
        Map<String, Object> bad = definition();
        bad.put("columns", List.of("title", "noSuchField"));

        create(ana, "Broken", bad, 422);
        assertThat(visibleNames(ana)).doesNotContain("Broken");
    }

    @Test
    void aNameIsRequiredAndBounded() throws Exception {
        assertThat(create(ana, " ", definition(), 422).get("code").asString()).isEqualTo("TEMPLATE_NAME_INVALID");
        assertThat(create(ana, "x".repeat(151), definition(), 422).get("code").asString()).isEqualTo("TEMPLATE_NAME_INVALID");
    }

    @Test
    void theOrganisationsLimitPerPersonIsEnforced() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, max_templates_per_user) values (?, 2)", tenantId);
        createId(ana, "One");
        createId(ana, "Two");

        assertThat(create(ana, "Three", definition(), 409).get("code").asString()).isEqualTo("TEMPLATE_LIMIT_REACHED");
        create(ben, "Mine", definition(), 201);
    }

    @Test
    void theOwnerCanUpdateAndTheChangeIsAudited() throws Exception {
        String id = createId(ana, "Draft");

        JsonNode updated = call(put("/api/v1/reports/templates/" + id), fx.jwtFor(ana), template("Final", definition("x")), 200);

        assertThat(updated.get("name").asString()).isEqualTo("Final");
        assertThat(audits(id, "REPORT_TEMPLATE_UPDATED")).isEqualTo(1);
    }

    @Test
    void theTemplatesPermissionsAreChecked() throws Exception {
        assertThat(call(post("/api/v1/reports/templates"), fx.jwtFor(runner), template("Nope", definition()), 403)).isNotNull();
        String id = createId(ana, "Mine");
        call(delete("/api/v1/reports/templates/" + id), fx.jwtFor(runner), null, 403);
    }

    // ------------------------------------------------------------------ who sees what

    @Test
    void aPrivateTemplateIsInvisibleToEveryoneElse_theAnswerIsA404() throws Exception {
        String id = createId(ana, "Private one");

        assertThat(getTemplate(ben, id, 404).get("code").asString()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(getTemplate(ben, UUID.randomUUID().toString(), 404).get("code").asString()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(visibleNames(ben)).isEmpty();
        call(put("/api/v1/reports/templates/" + id), fx.jwtFor(ben), template("Hijack", definition()), 404);
        call(delete("/api/v1/reports/templates/" + id), fx.jwtFor(ben), null, 404);
        assertThat(runTemplate(ben, id, null, 404).get("code").asString()).isEqualTo("TEMPLATE_NOT_FOUND");
    }

    @Test
    void anotherOrganisationNeverSeesATemplate() throws Exception {
        String id = createId(ana, "Ours");
        UUID other = fx.tenant();
        User stranger = fx.user(other, "Stranger");
        fx.role(other, "Members " + UUID.randomUUID(), MEMBER, stranger);

        getTemplate(stranger, id, 404);
        assertThat(visibleNames(stranger)).isEmpty();
    }

    @Test
    void sharingToAUserLetsThemRunItButNotChangeItUnlessTheyMayEdit() throws Exception {
        String id = createId(ana, "Shared");

        JsonNode shared = share(ana, id, List.of(grant("user", ben.getUserId(), "run")), 200);

        assertThat(shared.get("scope").asString()).isEqualTo("shared");
        assertThat(visibleNames(ben)).containsExactly("Shared");
        assertThat(getTemplate(ben, id, 200).get("access").asString()).isEqualTo("run");
        assertThat(call(put("/api/v1/reports/templates/" + id), fx.jwtFor(ben), template("Changed", definition()), 403).get("code").asString())
                .isEqualTo("TEMPLATE_READ_ONLY");
        call(delete("/api/v1/reports/templates/" + id), fx.jwtFor(ben), null, 403);

        share(ana, id, List.of(grant("user", ben.getUserId(), "edit")), 200);
        assertThat(getTemplate(ben, id, 200).get("access").asString()).isEqualTo("edit");
        call(put("/api/v1/reports/templates/" + id), fx.jwtFor(ben), template("Changed", definition()), 200);
        assertThat(audits(id, "REPORT_TEMPLATE_SHARED")).isEqualTo(2);
    }

    @Test
    void sharingToARoleOrADepartmentReachesItsMembers() throws Exception {
        var team = fx.role(tenantId, "Analysts " + UUID.randomUUID(), RUN_ONLY, runner);
        var department = fx.department(tenantId, "Finance", ben);
        String viaRole = createId(ana, "For the role");
        String viaDepartment = createId(ana, "For the department");

        share(ana, viaRole, List.of(grant("role", team.getRoleId(), "run")), 200);
        share(ana, viaDepartment, List.of(grant("department", department.getDepartmentId(), "run")), 200);

        assertThat(visibleNames(runner)).containsExactly("For the role");
        assertThat(visibleNames(ben)).containsExactly("For the department");
        assertThat(visibleNames(publisher)).isEmpty();
    }

    @Test
    void anEmptyShareListMakesTheTemplatePrivateAgain() throws Exception {
        String id = createId(ana, "Briefly shared");
        share(ana, id, List.of(grant("user", ben.getUserId(), "run")), 200);
        assertThat(visibleNames(ben)).hasSize(1);

        JsonNode back = share(ana, id, List.of(), 200);

        assertThat(back.get("scope").asString()).isEqualTo("private");
        assertThat(visibleNames(ben)).isEmpty();
    }

    @Test
    void aGrantToSomeoneWhoDoesNotExistOrToAnotherOrganisationIsRefused() throws Exception {
        String id = createId(ana, "Careful");
        UUID otherTenant = fx.tenant();
        User foreign = fx.user(otherTenant, "Foreign");

        assertThat(share(ana, id, List.of(grant("user", UUID.randomUUID(), "run")), 422).get("code").asString())
                .isEqualTo("TEMPLATE_GRANT_UNKNOWN_PRINCIPAL");
        assertThat(share(ana, id, List.of(grant("user", foreign.getUserId(), "run")), 422).get("code").asString())
                .isEqualTo("TEMPLATE_GRANT_UNKNOWN_PRINCIPAL");
        assertThat(share(ana, id, List.of(Map.of("principalType", "group", "principalId", UUID.randomUUID().toString())), 422)
                .get("code").asString()).isEqualTo("TEMPLATE_GRANT_INVALID");
    }

    @Test
    void onlyTheOwnerChangesWhoItIsSharedWith() throws Exception {
        String id = createId(ana, "Mine");
        share(ana, id, List.of(grant("user", ben.getUserId(), "edit")), 200);

        assertThat(share(ben, id, List.of(), 403).get("code").asString()).isEqualTo("TEMPLATE_NOT_OWNER");
    }

    // ------------------------------------------------------------------ official templates

    @Test
    void publishingNeedsThePublishPermission() throws Exception {
        String id = createId(ana, "Mine");

        call(post("/api/v1/reports/templates/" + id + "/publish"), fx.jwtFor(ana), Map.of("locked", false), 403);
    }

    @Test
    void anOfficialTemplateHasNoOwnerAndIsVisibleToEveryoneWhoMayRunReports() throws Exception {
        String id = createId(publisher, "Monthly contracts");

        JsonNode official = publish(publisher, id, false, List.of(), List.of(), 200);

        assertThat(official.get("scope").asString()).isEqualTo("official");
        assertThat(official.get("ownerUserId").isNull()).isTrue();
        assertThat(fx.jdbc().queryForObject("select owner_user_id is null from report_templates where template_id = ?::uuid", Boolean.class, id)).isTrue();
        assertThat(visibleNames(runner)).containsExactly("Monthly contracts");
        assertThat(visibleNames(ana)).containsExactly("Monthly contracts");
        assertThat(getTemplate(runner, id, 200).get("access").asString()).isEqualTo("run");
        assertThat(audits(id, "REPORT_TEMPLATE_PUBLISHED")).isEqualTo(1);
    }

    @Test
    void anAudienceLimitsWhoSeesAnOfficialTemplate() throws Exception {
        var finance = fx.role(tenantId, "Finance " + UUID.randomUUID(), RUN_ONLY, runner);
        String id = createId(publisher, "Finance only");

        publish(publisher, id, false, List.of(), List.of(grant("role", finance.getRoleId(), "run")), 200);

        assertThat(visibleNames(runner)).containsExactly("Finance only");
        assertThat(visibleNames(ana)).isEmpty();
        getTemplate(ana, id, 404);
    }

    @Test
    void aPublisherAlwaysSeesAnOfficialTemplateEvenOutsideItsAudience() throws Exception {
        var finance = fx.role(tenantId, "Finance " + UUID.randomUUID(), RUN_ONLY, runner);
        String id = createId(publisher, "Finance only");
        publish(publisher, id, false, List.of(), List.of(grant("role", finance.getRoleId(), "run")), 200);

        assertThat(visibleNames(publisher)).containsExactly("Finance only");
        assertThat(getTemplate(publisher, id, 200).get("scope").asString()).isEqualTo("official");
        call(post("/api/v1/reports/templates/" + id + "/unpublish"), fx.jwtFor(publisher), null, 200);
        assertThat(visibleNames(runner)).isEmpty();
    }

    @Test
    void publishingAnOfficialTemplateAgainChangesItsAudienceAndLock() throws Exception {
        var finance = fx.role(tenantId, "Finance " + UUID.randomUUID(), RUN_ONLY, runner);
        String id = createId(publisher, "Re-published");
        publish(publisher, id, false, List.of(), List.of(grant("role", finance.getRoleId(), "run")), 200);
        assertThat(visibleNames(ana)).isEmpty();

        JsonNode again = publish(publisher, id, true, List.of(), List.of(), 200);

        assertThat(again.get("scope").asString()).isEqualTo("official");
        assertThat(again.get("locked").asBoolean()).isTrue();
        assertThat(visibleNames(ana)).containsExactly("Re-published");
        call(post("/api/v1/reports/templates/" + id + "/publish"), fx.jwtFor(ana), Map.of("locked", false), 403);
    }

    @Test
    void anOfficialAudienceCannotBeGivenEditAccess() throws Exception {
        String id = createId(publisher, "Nobody edits me");

        assertThat(publish(publisher, id, false, List.of(), List.of(grant("user", ana.getUserId(), "edit")), 422).get("code").asString())
                .isEqualTo("TEMPLATE_GRANT_INVALID");
    }

    @Test
    void officialNamesAreUniquePerOrganisation() throws Exception {
        String first = createId(publisher, "Shared name");
        publish(publisher, first, false, List.of(), List.of(), 200);
        User second = fx.user(tenantId, "Second");
        fx.role(tenantId, "Publishers " + UUID.randomUUID(), PUBLISHER, second);
        String clash = createId(second, "shared NAME");

        assertThat(publish(second, clash, false, List.of(), List.of(), 409).get("code").asString()).isEqualTo("TEMPLATE_NAME_TAKEN");
    }

    @Test
    void onlyAPublisherChangesOrDeletesAnOfficialTemplate() throws Exception {
        String id = createId(publisher, "Official");
        publish(publisher, id, false, List.of(), List.of(), 200);

        assertThat(call(put("/api/v1/reports/templates/" + id), fx.jwtFor(ana), template("Hacked", definition()), 403).get("code").asString())
                .isEqualTo("TEMPLATE_PUBLISH_REQUIRED");
        call(delete("/api/v1/reports/templates/" + id), fx.jwtFor(ana), null, 403);
        call(put("/api/v1/reports/templates/" + id), fx.jwtFor(publisher), template("Official v2", definition()), 200);
    }

    @Test
    void aLockedTemplateCannotBeCopied_anOpenOneCan() throws Exception {
        String locked = createId(publisher, "Locked");
        String open = createId(publisher, "Open");
        publish(publisher, locked, true, List.of(), List.of(), 200);
        publish(publisher, open, false, List.of(), List.of(), 200);

        assertThat(postAs("/api/v1/reports/templates/" + locked + "/duplicate", ana, null, 403).get("code").asString()).isEqualTo("TEMPLATE_LOCKED");
        JsonNode copy = postAs("/api/v1/reports/templates/" + open + "/duplicate", ana, null, 201);

        assertThat(copy.get("name").asString()).isEqualTo("Open (copy)");
        assertThat(copy.get("scope").asString()).isEqualTo("private");
        assertThat(copy.get("access").asString()).isEqualTo("owner");
        assertThat(postAs("/api/v1/reports/templates/" + open + "/duplicate", ana, null, 201).get("name").asString()).isEqualTo("Open (copy 2)");
        postAs("/api/v1/reports/templates/" + locked + "/duplicate", publisher, null, 201); // a publisher may
    }

    @Test
    void unpublishingGivesTheTemplateBackToTheCallerAsPrivate() throws Exception {
        String id = createId(publisher, "Back again");
        publish(publisher, id, true, List.of("title"), List.of(), 200);

        JsonNode back = postAs("/api/v1/reports/templates/" + id + "/unpublish", publisher, null, 200);

        assertThat(back.get("scope").asString()).isEqualTo("private");
        assertThat(back.get("locked").asBoolean()).isFalse();
        assertThat(back.get("ownerUserId").asString()).isEqualTo(publisher.getUserId().toString());
        assertThat(visibleNames(runner)).isEmpty();
    }

    // ------------------------------------------------------------------ deleting

    @Test
    void deleteIsSoft_andTheTemplateStopsExisting() throws Exception {
        String id = createId(ana, "Short lived");

        call(delete("/api/v1/reports/templates/" + id), fx.jwtFor(ana), null, 204);

        getTemplate(ana, id, 404);
        assertThat(visibleNames(ana)).isEmpty();
        assertThat(fx.jdbc().queryForObject("select deleted_at is not null from report_templates where template_id = ?::uuid", Boolean.class, id)).isTrue();
        assertThat(audits(id, "REPORT_TEMPLATE_DELETED")).isEqualTo(1);
        // The name is free again.
        create(ana, "Short lived", definition(), 201);
    }

    @Test
    void aTemplateWithSchedulesCannotBeDeleted_unlessTheyAreCancelledInTheSameCall() throws Exception {
        String id = createId(ana, "Scheduled");
        UUID schedule = insertSchedule(id, ana);

        JsonNode refused = call(delete("/api/v1/reports/templates/" + id), fx.jwtFor(ana), null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("TEMPLATE_HAS_SCHEDULES");
        assertThat(refused.get("details").get("schedules").asInt()).isEqualTo(1);
        assertThat(getTemplate(ana, id, 200).get("activeSchedules").asInt()).isEqualTo(1);

        call(delete("/api/v1/reports/templates/" + id + "?cancelSchedules=true"), fx.jwtFor(ana), null, 204);

        assertThat(fx.jdbc().queryForObject("select status from report_schedules where schedule_id = ?", String.class, schedule)).isEqualTo("cancelled");
        assertThat(audits(schedule.toString(), "REPORT_SCHEDULE_CANCELLED")).isEqualTo(1);
        getTemplate(ana, id, 404);
    }

    // ------------------------------------------------------------------ running a template

    @Test
    void aPersonWhoMayOnlyRunReportsRunsATemplateSharedWithThem_andThatIsAllTheyCanDo() throws Exception {
        fx.document(tenantId, typeId, ana, "Alpha contract");
        fx.document(tenantId, typeId, ana, "Beta memo");
        String id = create(ana, "Contracts only", definition("contract"), 201).get("id").asString();
        share(ana, id, List.of(grant("user", runner.getUserId(), "run")), 200);

        JsonNode run = await(runner, runTemplate(runner, id, null, 202).get("runId").asString());

        assertThat(run.get("status").asString()).isEqualTo("completed");
        assertThat(run.get("rowCount").asLong()).isEqualTo(1);
        String csv = file(runner, run.get("runId").asString());
        assertThat(csv).contains("Alpha contract").doesNotContain("Beta memo");
        assertThat(fx.jdbc().queryForObject("select template_id::text from report_runs where run_id = ?::uuid", String.class,
                run.get("runId").asString())).isEqualTo(id);
        assertThat(fx.jdbc().queryForObject("select definition_snapshot->>'title' from report_runs where run_id = ?::uuid", String.class,
                run.get("runId").asString())).isEqualTo("Contracts only");
        // ...but the same person still cannot run a definition of their own.
        assertThat(postAs("/api/v1/reports/runs", runner, Map.of("definition", definition(), "format", "csv"), 403).get("code").asString())
                .isEqualTo("REPORT_BUILD_REQUIRED");
    }

    @Test
    void aTemplateIsRunUnderTheRunnersOwnAccess_notTheOwners() throws Exception {
        fx.document(tenantId, typeId, ana, "Open contract");
        UUID minutes = fx.document(tenantId, typeId, ana, "Board minutes");
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", minutes);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) "
                + "values (?, ?, 'user', ?, 'manage')", tenantId, minutes, ana.getUserId());
        String id = createId(ana, "Everything");
        share(ana, id, List.of(grant("user", runner.getUserId(), "run")), 200);

        JsonNode byOwner = await(ana, runTemplate(ana, id, null, 202).get("runId").asString());
        JsonNode byRunner = await(runner, runTemplate(runner, id, null, 202).get("runId").asString());

        assertThat(byOwner.get("rowCount").asLong()).isEqualTo(2);
        assertThat(byRunner.get("rowCount").asLong()).isEqualTo(1);
        assertThat(file(runner, byRunner.get("runId").asString())).doesNotContain("Board minutes");
    }

    @Test
    void aTemplateTheCallerCannotSeeCannotBeRun_andGivingBothATemplateAndADefinitionIsRefused() throws Exception {
        String id = createId(ana, "Private");

        assertThat(runTemplate(ben, id, null, 404).get("code").asString()).isEqualTo("TEMPLATE_NOT_FOUND");
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("templateId", id);
        both.put("definition", definition());
        both.put("format", "csv");
        assertThat(postAs("/api/v1/reports/runs", ana, both, 422).get("code").asString()).isEqualTo("REPORT_RUN_INVALID");
    }

    @Test
    void aLockedTemplateTakesOnlyTheFilterChangesItsAdministratorAllowed() throws Exception {
        fx.document(tenantId, typeId, ana, "Alpha contract");
        fx.document(tenantId, typeId, ana, "Beta contract");
        String id = createId(publisher, "Locked contracts");
        publish(publisher, id, true, List.of("title"), List.of(), 200);

        List<Map<String, Object>> onlyBeta = List.of(Map.of("field", "title", "operator", "contains", "values", List.of("Beta")));
        JsonNode allowed = await(runner, runTemplate(runner, id, onlyBeta, 202).get("runId").asString());
        List<Map<String, Object>> notAllowed = List.of(Map.of("field", "status", "operator", "is_any_of", "values", List.of("draft")));
        JsonNode refused = runTemplate(runner, id, notAllowed, 422);

        assertThat(allowed.get("rowCount").asLong()).isEqualTo(1);
        assertThat(file(runner, allowed.get("runId").asString())).contains("Beta contract").doesNotContain("Alpha contract");
        assertThat(refused.get("code").asString()).isEqualTo("TEMPLATE_FILTER_NOT_EDITABLE");
    }

    @Test
    void aFieldTheAdministratorSwitchedOffIsWarnedAboutAndLeftOutOfTheRun() throws Exception {
        fx.document(tenantId, typeId, ana, "Alpha contract");
        Map<String, Object> withStatus = definition();
        withStatus.put("columns", List.of("title", "status"));
        String id = create(ana, "With status", withStatus, 201).get("id").asString();
        fx.jdbc().update("insert into report_field_settings (tenant_id, field_key, enabled) values (?, 'status', false)", tenantId);

        JsonNode read = getTemplate(ana, id, 200);
        JsonNode run = await(ana, runTemplate(ana, id, null, 202).get("runId").asString());

        assertThat(read.get("warnings")).hasSize(1);
        assertThat(read.get("warnings").get(0).asString()).contains("status");
        assertThat(run.get("status").asString()).isEqualTo("completed");
        String csv = file(ana, run.get("runId").asString());
        assertThat(csv).contains("Alpha contract");
        assertThat(csv).doesNotContain("draft");
    }

    @Test
    void aRetryOfATemplateRunStillBelongsToTheTemplate() throws Exception {
        String id = createId(ana, "Retried");
        UUID failed = UUID.randomUUID();
        fx.jdbc().update("insert into report_runs (run_id, tenant_id, template_id, requested_by, trigger, format, status, definition_snapshot, "
                        + "error_code, finished_at) values (?, ?, ?::uuid, ?, 'manual', 'csv', 'failed', cast(? as jsonb), 'REPORT_FAILED', now())",
                failed, tenantId, id, ana.getUserId(),
                "{\"definition\":{\"source\":\"documents\",\"columns\":[\"title\"],\"filters\":[],\"sort\":[]},\"zone\":\"UTC\",\"title\":\"Retried\"}");

        JsonNode retry = postAs("/api/v1/reports/runs/" + failed + "/retry", ana, null, 202);

        assertThat(fx.jdbc().queryForObject("select template_id::text from report_runs where run_id = ?::uuid", String.class,
                retry.get("runId").asString())).isEqualTo(id);
    }
}
