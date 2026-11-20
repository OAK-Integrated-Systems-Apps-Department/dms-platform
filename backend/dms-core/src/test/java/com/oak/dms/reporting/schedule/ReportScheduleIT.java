package com.oak.dms.reporting.schedule;

import com.oak.dms.common.mail.BrandedMailSender;
import com.oak.dms.reporting.delivery.EmailReportDelivery;
import com.oak.dms.reporting.delivery.ReportDeliveryChannel;
import com.oak.dms.reporting.delivery.ReportDeliveryService;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.repository.ReportScheduleRepository;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Schedules and delivery through the real application: validation, the scheduler job, every check made when the time comes, one file
 * per person under their own access, the delivery channels with retries, and the audit trail - on a real database with real SMTP.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class ReportScheduleIT extends WorkflowApiSupport {

    private static final String[] SCHEDULER = {"docm.documents.view", "crg.generate-custom-reports.build", "crg.generate-custom-reports.run",
            "crg.manage-report-templates.create", "crg.manage-report-templates.update", "crg.manage-report-templates.delete",
            "crg.schedule-automated-reports.create-schedule", "crg.schedule-automated-reports.update-cancel-schedule"};
    private static final String[] ADMIN = {"docm.documents.view", "crg.generate-custom-reports.build", "crg.generate-custom-reports.run",
            "crg.manage-report-templates.create", "crg.manage-report-templates.update", "crg.manage-report-templates.delete",
            "crg.schedule-automated-reports.create-schedule", "crg.schedule-automated-reports.update-cancel-schedule",
            "crg.configure-reports.manage"};
    private static final String[] RUN_ONLY = {"docm.documents.view", "crg.generate-custom-reports.run"};
    private static final String[] VIEW_ONLY = {"docm.documents.view"};

    /** A background job asks Keycloak for realm roles, live; the fixture users are not in Keycloak, so it answers "no elevated role". */
    @MockitoBean
    KeycloakUserService keycloak;
    @MockitoSpyBean
    BrandedMailSender mailSender;
    @Autowired
    ReportScheduleJob job;
    @Autowired
    ReportScheduleDispatcher dispatcher;
    @Autowired
    com.oak.dms.reporting.run.ReportRunWorker worker;
    @Autowired
    ReportScheduleRepository schedules;
    @Autowired
    ReportDeliveryService deliveries;
    @Autowired
    EmailReportDelivery emailDelivery;
    @Autowired
    ReportSettingsService settingsService;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    com.oak.dms.reporting.ReportProperties properties;

    private UUID tenantId;
    private UUID typeId;
    private User ana;     // the schedule owner
    private User ben;     // an internal recipient
    private User admin;   // may add external addresses
    private String templateId;

    @BeforeEach
    void world() throws Exception {
        tenantId = fx.tenant();
        typeId = fx.documentType(tenantId, "Contract");
        ana = fx.user(tenantId, "Ana");
        ben = fx.user(tenantId, "Ben");
        admin = fx.user(tenantId, "Admin");
        fx.role(tenantId, "Schedulers " + UUID.randomUUID(), SCHEDULER, ana);
        fx.role(tenantId, "Runners " + UUID.randomUUID(), RUN_ONLY, ben);
        fx.role(tenantId, "Admins " + UUID.randomUUID(), ADMIN, admin);
        templateId = postAs("/api/v1/reports/templates", ana, Map.of("name", "Contracts", "definition", definition()), 201).get("id").asString();
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> definition() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "documents");
        body.put("columns", List.of("title", "status"));
        body.put("filters", List.of());
        body.put("sort", List.of(Map.of("field", "title", "direction", "asc")));
        return body;
    }

    private Map<String, Object> schedule(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId);
        body.put("name", name);
        body.put("frequency", "daily");
        body.put("timeOfDay", "08:00");
        body.put("timeZone", "Asia/Colombo");
        body.put("format", "csv");
        body.put("deliveryChannels", List.of("in_app"));
        body.put("recipients", List.of());
        return body;
    }

    private static Map<String, Object> recipient(String type, Object ref) {
        return Map.of("type", type, "ref", ref.toString());
    }

    /** A schedule on a template the given person owns - someone else's private template is not theirs to schedule. */
    private Map<String, Object> scheduleFor(User as, String name) throws Exception {
        Map<String, Object> body = schedule(name);
        body.put("templateId", postAs("/api/v1/reports/templates", as, Map.of("name", "Own " + UUID.randomUUID(), "definition", definition()), 201)
                .get("id").asString());
        return body;
    }

    private JsonNode createSchedule(User as, Map<String, Object> body, int expected) throws Exception {
        return postAs("/api/v1/reports/schedules", as, body, expected);
    }

    private UUID scheduleId(User as, Map<String, Object> body) throws Exception {
        return UUID.fromString(createSchedule(as, body, 201).get("id").asString());
    }

    private JsonNode getSchedule(User as, UUID id, int expected) throws Exception {
        return call(get("/api/v1/reports/schedules/" + id), fx.jwtFor(as), null, expected);
    }

    private void due(UUID schedule, String ago) {
        fx.jdbc().update("update report_schedules set next_run_at = now() - interval '" + ago + "' where schedule_id = ?", schedule);
    }

    private String scheduleStatus(UUID schedule) {
        return fx.jdbc().queryForObject("select status from report_schedules where schedule_id = ?", String.class, schedule);
    }

    private List<Map<String, Object>> runsOf(UUID schedule) {
        return fx.jdbc().queryForList("select run_id, status, delivery_scope, for_user_id, error_code, delivered, delivery_note, trigger, "
                + "scheduled_for from report_runs where schedule_id = ? order by created_at, run_id", schedule);
    }

    /** Waits until the schedule's runs have finished and every delivery that is due has been settled. */
    private void settle(UUID schedule) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000;
        while (true) {
            long open = fx.count("select count(*) from report_runs where schedule_id = ? and (status in ('queued','running') "
                    + "or (status in ('completed','failed') and delivered is null))", schedule);
            long due = fx.count("select count(*) from report_run_deliveries d join report_runs r on r.run_id = d.run_id "
                    + "where r.schedule_id = ? and d.status = 'pending' and d.next_attempt_at <= now()", schedule);
            if (open == 0 && due == 0) {
                return;
            }
            assertThat(System.currentTimeMillis()).as("schedule " + schedule + " still working: open=" + open + " due=" + due).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private void fireAndSettle(UUID schedule) throws Exception {
        due(schedule, "1 minute");
        assertThat(job.runOnce()).as("the job handled the due schedule").isGreaterThanOrEqualTo(1);
        settle(schedule);
    }

    private long notifications(User to, String category) {
        return fx.count("select count(*) from notifications where recipient_user_id = ? and category_code = ?", to.getUserId(), category);
    }

    private long audits(String objectId, String action) {
        return fx.count("select count(*) from audit_events where object_id = ?::uuid and action = ?", objectId, action);
    }

    private List<String> deliveryStatuses(UUID runId) {
        return fx.jdbc().queryForList("select channel || ':' || status from report_run_deliveries where run_id = ? order by channel", String.class, runId);
    }

    private String download(User as, Object runId) throws Exception {
        MockHttpServletResponse response = getRaw("/api/v1/reports/runs/" + runId + "/download", fx.jwtFor(as));
        assertThat(response.getStatus()).isEqualTo(200);
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static void collect(Multipart multipart, List<BodyPart> out) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getContent() instanceof Multipart nested) {
                collect(nested, out);
            } else {
                out.add(part);
            }
        }
    }

    private static List<BodyPart> attachmentsOf(MimeMessage mail) throws Exception {
        List<BodyPart> parts = new ArrayList<>();
        collect((Multipart) mail.getContent(), parts);
        return parts.stream().filter(p -> {
            try {
                return Part.ATTACHMENT.equals(p.getDisposition());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private List<MimeMessage> reportMails(String address) throws Exception {
        List<MimeMessage> mails = new ArrayList<>();
        for (MimeMessage m : mailTo(address)) {
            if (subjectOf(m).startsWith("Report:")) {
                mails.add(m);
            }
        }
        return mails;
    }

    private void restrictToOwner(UUID document) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", document);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) "
                + "values (?, ?, 'user', ?, 'manage')", tenantId, document, ana.getUserId());
    }

    // ------------------------------------------------------------------ creating a schedule

    @Test
    void aScheduleIsCreatedWithItsNextRunWorkedOutAndAuditedWithCountsOnly() throws Exception {
        Map<String, Object> body = schedule("Daily contracts");
        body.put("recipients", List.of(recipient("user", ben.getUserId())));

        JsonNode created = createSchedule(ana, body, 201);

        assertThat(created.get("status").asString()).isEqualTo("active");
        assertThat(created.get("templateName").asString()).isEqualTo("Contracts");
        assertThat(OffsetDateTime.parse(created.get("nextRunAt").asString())).isAfter(OffsetDateTime.now());
        assertThat(created.get("recipients")).hasSize(1);
        String id = created.get("id").asString();
        assertThat(audits(id, "REPORT_SCHEDULE_CREATED")).isEqualTo(1);
        String payload = fx.jdbc().queryForObject("select new_value::text from audit_events where object_id = ?::uuid and action = 'REPORT_SCHEDULE_CREATED'",
                String.class, id);
        assertThat(payload).contains("\"userRecipients\": 1").doesNotContain(ben.getUserId().toString()).doesNotContain(ben.getEmail());
    }

    @Test
    void everyProblemIsReportedByFieldInOneAnswer() throws Exception {
        Map<String, Object> body = schedule("");
        body.put("frequency", "weekly");
        body.put("daysOfWeek", List.of(9));
        body.put("timeZone", "Mars/Olympus");
        body.put("format", "docx");
        body.put("deliveryChannels", List.of("sms"));
        body.remove("timeOfDay");

        JsonNode refused = createSchedule(ana, body, 422);

        assertThat(refused.get("code").asString()).isEqualTo("SCHEDULE_INVALID");
        JsonNode problems = refused.get("details");
        assertThat(problems.propertyNames()).contains("name", "daysOfWeek", "timeZone", "format", "deliveryChannels", "timeOfDay");
    }

    @Test
    void monthlyNeedsADayAndTheEndCannotPrecedeTheStart() throws Exception {
        Map<String, Object> body = schedule("Monthly");
        body.put("frequency", "monthly");
        body.put("dayOfMonth", 32);
        body.put("startDate", "2026-06-10");
        body.put("endDate", "2026-06-01");

        JsonNode problems = createSchedule(ana, body, 422).get("details");

        assertThat(problems.propertyNames()).contains("dayOfMonth", "endDate");
    }

    @Test
    void aScheduleThatWouldNeverRunIsRefused() throws Exception {
        Map<String, Object> body = schedule("Past");
        body.put("endDate", "2020-01-01");

        assertThat(createSchedule(ana, body, 422).get("code").asString()).isEqualTo("SCHEDULE_NO_FUTURE_RUN");
    }

    @Test
    void theTemplateMustBeOneTheOwnerCanSee() throws Exception {
        Map<String, Object> body = schedule("Not mine");
        body.put("templateId", UUID.randomUUID().toString());

        assertThat(createSchedule(ana, body, 404).get("code").asString()).isEqualTo("TEMPLATE_NOT_FOUND");
    }

    @Test
    void attachmentsNeedTheOrganisationsPermissionAndEmail() throws Exception {
        Map<String, Object> body = schedule("Attached");
        body.put("deliveryMode", "attachment");
        body.put("deliveryChannels", List.of("email"));

        assertThat(createSchedule(ana, body, 422).get("details").propertyNames()).contains("deliveryMode");

        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allow_attachments) values (?, true)", tenantId);
        createSchedule(ana, body, 201);
        body.put("deliveryChannels", List.of("in_app"));
        assertThat(createSchedule(ana, body, 422).get("details").propertyNames()).contains("deliveryMode");
    }

    @Test
    void aChannelTheOrganisationSwitchedOffCannotBeChosen() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allowed_delivery_channels) values (?, ARRAY['in_app'])", tenantId);
        Map<String, Object> body = schedule("Mail");
        body.put("deliveryChannels", List.of("email"));

        assertThat(createSchedule(ana, body, 422).get("details").propertyNames()).contains("deliveryChannels");
    }

    @Test
    void recipientsMustExist_beUnique_andFitTheLimit() throws Exception {
        Map<String, Object> body = schedule("Recipients");
        body.put("recipients", List.of(recipient("user", UUID.randomUUID())));
        assertThat(createSchedule(ana, body, 422).get("details").propertyNames()).contains("recipients");

        fx.jdbc().update("insert into tenant_report_settings (tenant_id, max_recipients_per_schedule) values (?, 2)", tenantId);
        User c = fx.user(tenantId, "Cee");
        body.put("recipients", List.of(recipient("user", ben.getUserId()), recipient("user", ben.getUserId()), recipient("user", c.getUserId())));
        // Ben twice counts once: two people, within the limit of two.
        JsonNode created = createSchedule(ana, body, 201);
        assertThat(created.get("recipients")).hasSize(2);

        body.put("recipients", List.of(recipient("user", ben.getUserId()), recipient("user", c.getUserId()), recipient("user", admin.getUserId())));
        assertThat(createSchedule(ana, body, 422).get("details").propertyNames()).contains("recipients");
    }

    @Test
    void thePerPersonAndPerOrganisationLimitsAreEnforced() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, max_schedules_per_user, max_schedules_per_tenant) values (?, 1, 2)", tenantId);
        UUID first = scheduleId(ana, schedule("One"));

        assertThat(createSchedule(ana, schedule("Two"), 409).get("code").asString()).isEqualTo("SCHEDULE_LIMIT_REACHED");
        scheduleId(admin, scheduleFor(admin, "Admins"));
        User third = fx.user(tenantId, "Third");
        fx.role(tenantId, "Schedulers " + UUID.randomUUID(), SCHEDULER, third);
        assertThat(createSchedule(third, scheduleFor(third, "Over"), 409).get("code").asString()).isEqualTo("SCHEDULE_TENANT_LIMIT_REACHED");

        // Cancelling frees the place.
        call(delete("/api/v1/reports/schedules/" + first), fx.jwtFor(ana), null, 204);
        scheduleId(ana, schedule("Again"));
    }

    @Test
    void externalAddressesNeedEverythingToBeSwitchedOn() throws Exception {
        Map<String, Object> body = scheduleFor(admin, "External");
        body.put("deliveryChannels", List.of("email"));
        body.put("deliveryMode", "attachment");
        body.put("recipients", List.of(recipient("email", "Client@Partner.example")));

        // Off by default.
        assertThat(createSchedule(admin, body, 422).get("details").propertyNames()).contains("recipients");

        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allow_external_recipients, external_domain_allowlist, allow_attachments) "
                + "values (?, true, ARRAY['partner.example'], true)", tenantId);
        // Allowed - but not for someone without the configure permission.
        Map<String, Object> byAna = new LinkedHashMap<>(body);
        byAna.put("templateId", templateId);
        assertThat(createSchedule(ana, byAna, 422).get("details").get("recipients").asString()).contains("administrator");
        // A domain that is not on the list.
        Map<String, Object> elsewhere = new LinkedHashMap<>(body);
        elsewhere.put("recipients", List.of(recipient("email", "someone@elsewhere.example")));
        assertThat(createSchedule(admin, elsewhere, 422).get("details").get("recipients").asString()).contains("allowed domains");
        // An address, not an attachment-free link: external needs email with attachments.
        Map<String, Object> inApp = new LinkedHashMap<>(body);
        inApp.put("deliveryChannels", List.of("in_app"));
        inApp.put("deliveryMode", "link");
        assertThat(createSchedule(admin, inApp, 422).get("details").propertyNames()).contains("recipients");

        JsonNode ok = createSchedule(admin, body, 201);
        assertThat(ok.get("recipients").get(0).get("ref").asString()).isEqualTo("client@partner.example"); // normalised
    }

    // ------------------------------------------------------------------ owning, changing, pausing

    @Test
    void aScheduleIsTheOwnersAlone_everyoneElseGetsA404() throws Exception {
        UUID id = scheduleId(ana, schedule("Mine"));

        assertThat(getSchedule(ben, id, 404).get("code").asString()).isEqualTo("SCHEDULE_NOT_FOUND");
        assertThat(getSchedule(ana, UUID.randomUUID(), 404).get("code").asString()).isEqualTo("SCHEDULE_NOT_FOUND");
        assertThat(get200("/api/v1/reports/schedules", ben)).isEmpty();
        assertThat(get200("/api/v1/reports/schedules", ana)).hasSize(1);
    }

    @Test
    void pauseResumeAndCancelAreAuditedAndKeepTheHistory() throws Exception {
        UUID id = scheduleId(ana, schedule("Lifecycle"));

        JsonNode paused = postAs("/api/v1/reports/schedules/" + id + "/pause", ana, null, 200);
        assertThat(paused.get("status").asString()).isEqualTo("paused");
        assertThat(paused.get("pauseReason").asString()).isEqualTo("USER");
        // A paused schedule is not picked up, however late it is.
        due(id, "1 hour");
        assertThat(job.runOnce()).isZero();
        assertThat(runsOf(id)).isEmpty();

        JsonNode resumed = postAs("/api/v1/reports/schedules/" + id + "/resume", ana, null, 200);
        assertThat(resumed.get("status").asString()).isEqualTo("active");
        assertThat(OffsetDateTime.parse(resumed.get("nextRunAt").asString())).as("worked out from now, not made up").isAfter(OffsetDateTime.now());

        call(delete("/api/v1/reports/schedules/" + id), fx.jwtFor(ana), null, 204);
        assertThat(scheduleStatus(id)).isEqualTo("cancelled");
        assertThat(get200("/api/v1/reports/schedules", ana)).isEmpty();
        assertThat(postAs("/api/v1/reports/schedules/" + id + "/pause", ana, null, 409).get("code").asString()).isEqualTo("SCHEDULE_NOT_ACTIVE");
        assertThat(audits(id.toString(), "REPORT_SCHEDULE_PAUSED")).isEqualTo(1);
        assertThat(audits(id.toString(), "REPORT_SCHEDULE_RESUMED")).isEqualTo(1);
        assertThat(audits(id.toString(), "REPORT_SCHEDULE_CANCELLED")).isEqualTo(1);
    }

    @Test
    void updatingAScheduleChecksItAgainAndMovesTheNextRun() throws Exception {
        UUID id = scheduleId(ana, schedule("Changing"));
        Map<String, Object> changed = schedule("Changed");
        changed.put("frequency", "weekly");
        changed.put("daysOfWeek", List.of(1, 3));

        JsonNode updated = call(put("/api/v1/reports/schedules/" + id), fx.jwtFor(ana), changed, 200);

        assertThat(updated.get("name").asString()).isEqualTo("Changed");
        assertThat(updated.get("daysOfWeek")).hasSize(2);
        assertThat(audits(id.toString(), "REPORT_SCHEDULE_UPDATED")).isEqualTo(1);
        changed.put("format", "docx");
        call(put("/api/v1/reports/schedules/" + id), fx.jwtFor(ana), changed, 422);
    }

    // ------------------------------------------------------------------ the scheduler

    @Test
    void aDueScheduleRunsOnce_deliversToTheOwner_andMovesOn() throws Exception {
        fx.document(tenantId, typeId, ana, "Alpha contract");
        UUID id = scheduleId(ana, schedule("Daily"));

        fireAndSettle(id);

        List<Map<String, Object>> runs = runsOf(id);
        assertThat(runs).hasSize(1);
        Map<String, Object> run = runs.get(0);
        assertThat(run.get("status")).isEqualTo("completed");
        assertThat(run.get("delivery_scope")).isEqualTo("owner");
        assertThat(run.get("trigger")).isEqualTo("scheduled");
        assertThat(run.get("delivered")).isEqualTo(true);
        assertThat(deliveryStatuses((UUID) run.get("run_id"))).containsExactly("in_app:sent");
        assertThat(notifications(ana, "report.ready")).isEqualTo(1);
        assertThat(download(ana, run.get("run_id"))).contains("Alpha contract");
        // Moved on: next run is in the future, the slot just fired is recorded.
        assertThat(OffsetDateTime.parse(getSchedule(ana, id, 200).get("nextRunAt").asString())).isAfter(OffsetDateTime.now());
        assertThat(getSchedule(ana, id, 200).get("lastRunAt").isNull()).isFalse();
        assertThat(audits(run.get("run_id").toString(), "REPORT_GENERATED")).isEqualTo(1);
        assertThat(audits(run.get("run_id").toString(), "REPORT_DELIVERED")).isEqualTo(1);
        // Nothing else is due: a second poll does nothing.
        assertThat(job.runOnce()).isZero();
        assertThat(runsOf(id)).hasSize(1);
    }

    @Test
    void everyoneGetsTheReportUnderTheirOwnAccess_neverSomeoneElses() throws Exception {
        fx.document(tenantId, typeId, ana, "Open contract");
        restrictToOwner(fx.document(tenantId, typeId, ana, "Board minutes"));
        Map<String, Object> body = schedule("Per person");
        body.put("recipients", List.of(recipient("user", ana.getUserId()), recipient("user", ben.getUserId())));
        UUID id = scheduleId(ana, body);

        fireAndSettle(id);

        List<Map<String, Object>> runs = runsOf(id);
        assertThat(runs).hasSize(2).allSatisfy(r -> assertThat(r.get("delivery_scope")).isEqualTo("recipient"));
        Object anasRun = runs.stream().filter(r -> ana.getUserId().equals(r.get("for_user_id"))).findFirst().orElseThrow().get("run_id");
        Object bensRun = runs.stream().filter(r -> ben.getUserId().equals(r.get("for_user_id"))).findFirst().orElseThrow().get("run_id");
        assertThat(download(ana, anasRun)).contains("Open contract").contains("Board minutes");
        String forBen = download(ben, bensRun);
        assertThat(forBen).contains("Open contract").doesNotContain("Board minutes");
        // Nobody can fetch the other person's file.
        call(get("/api/v1/reports/runs/" + bensRun + "/download"), fx.jwtFor(ana), null, 404);
        call(get("/api/v1/reports/runs/" + anasRun + "/download"), fx.jwtFor(ben), null, 404);
        assertThat(notifications(ana, "report.ready")).isEqualTo(1);
        assertThat(notifications(ben, "report.ready")).isEqualTo(1);
        // Ben never held the owner's run: the schedule is not his.
        assertThat(get200("/api/v1/reports/runs", ben).get("content")).hasSize(1);
    }

    @Test
    void aRoleAndADepartmentAreExpandedToTheirActivePeopleWhenTheReportRuns() throws Exception {
        User c = fx.user(tenantId, "Cee");
        User gone = fx.user(tenantId, "Gone");
        var team = fx.role(tenantId, "Analysts " + UUID.randomUUID(), RUN_ONLY, c, gone);
        var finance = fx.department(tenantId, "Finance", ben);
        Map<String, Object> body = schedule("Expanded");
        body.put("recipients", List.of(recipient("role", team.getRoleId()), recipient("department", finance.getDepartmentId())));
        UUID id = scheduleId(ana, body);
        // Between saving and running, one of them leaves and another joins.
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", gone.getUserId());
        User late = fx.user(tenantId, "Late");
        fx.grant(late, team);

        fireAndSettle(id);

        List<Object> recipients = runsOf(id).stream().map(r -> r.get("for_user_id")).toList();
        assertThat(recipients).containsExactlyInAnyOrder(c.getUserId(), ben.getUserId(), late.getUserId());
        assertThat(runsOf(id)).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("completed"));
    }

    @Test
    void aRecipientWhoCannotReceiveItIsListedInTheDeliveryLog_andTheOthersStillGetTheirs() throws Exception {
        User disabled = fx.user(tenantId, "Disabled");
        fx.role(tenantId, "Runners " + UUID.randomUUID(), RUN_ONLY, disabled);
        User viewer = fx.user(tenantId, "Viewer");
        fx.role(tenantId, "Viewers " + UUID.randomUUID(), VIEW_ONLY, viewer);
        Map<String, Object> body = schedule("Mixed");
        body.put("recipients", List.of(recipient("user", ben.getUserId()), recipient("user", disabled.getUserId()),
                recipient("user", viewer.getUserId())));
        UUID id = scheduleId(ana, body);
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", disabled.getUserId());

        fireAndSettle(id);

        Map<Object, Map<String, Object>> byPerson = new LinkedHashMap<>();
        runsOf(id).forEach(r -> byPerson.put(r.get("for_user_id"), r));
        assertThat(byPerson.get(ben.getUserId()).get("status")).isEqualTo("completed");
        assertThat(byPerson.get(disabled.getUserId()).get("status")).isEqualTo("skipped");
        assertThat(byPerson.get(disabled.getUserId()).get("error_code")).isEqualTo("RECIPIENT_INACTIVE");
        assertThat(byPerson.get(viewer.getUserId()).get("error_code")).isEqualTo("RECIPIENT_NO_PERMISSION");
        assertThat(deliveryStatuses((UUID) byPerson.get(viewer.getUserId()).get("run_id"))).containsExactly("in_app:skipped");
        assertThat(notifications(ben, "report.ready")).isEqualTo(1);
        assertThat(notifications(viewer, "report.ready")).isZero();
    }

    @Test
    void twoSchedulersGettingHoldOfTheSameSlotCreateEachRunOnce() throws Exception {
        UUID id = scheduleId(ana, schedule("Raced"));
        OffsetDateTime slot = OffsetDateTime.now().minusMinutes(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);

        List<UUID> first = tx.execute(s -> dispatcher.fire(schedules.findById(id).orElseThrow(), slot, true));
        List<UUID> second = tx.execute(s -> dispatcher.fire(schedules.findById(id).orElseThrow(), slot, true));

        first.forEach(worker::start); // the commit hook that normally does this is bypassed here
        assertThat(first).hasSize(1);
        assertThat(second).as("the same slot, the same delivery target: nothing new").isEmpty();
        assertThat(runsOf(id)).hasSize(1);
        settle(id);
    }

    @Test
    void afterDowntimeItRunsOnceForWhatItMissed_ifTheSlotIsRecentEnough() throws Exception {
        UUID id = scheduleId(ana, schedule("Late but fine"));
        due(id, "3 hours"); // within the default window of 24 hours

        assertThat(job.runOnce()).isEqualTo(1);
        settle(id);

        assertThat(runsOf(id)).hasSize(1);
        assertThat(runsOf(id).get(0).get("status")).isEqualTo("completed");
        assertThat(OffsetDateTime.parse(getSchedule(ana, id, 200).get("nextRunAt").asString())).isAfter(OffsetDateTime.now());
    }

    @Test
    void aSlotMissedForTooLongIsSkippedAndRecorded_notRunLate() throws Exception {
        UUID id = scheduleId(ana, schedule("Too late"));
        due(id, "3 days");

        assertThat(job.runOnce()).isEqualTo(1);

        List<Map<String, Object>> runs = runsOf(id);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("status")).isEqualTo("skipped");
        assertThat(runs.get(0).get("error_code")).isEqualTo("MISSED_WINDOW");
        assertThat(OffsetDateTime.parse(getSchedule(ana, id, 200).get("nextRunAt").asString())).as("moved past the missed days").isAfter(OffsetDateTime.now());
        assertThat(audits(id.toString(), "REPORT_SCHEDULE_RUN_SKIPPED")).isEqualTo(1);
        assertThat(getSchedule(ana, id, 200).get("consecutiveFailures").asInt()).as("a missed slot is not a failure").isZero();
    }

    @Test
    void theScheduleEndsAfterItsEndDate() throws Exception {
        UUID id = scheduleId(ana, schedule("Ending"));
        // Its last slot is due now and its end date has just passed: after this run there is nothing left to wait for.
        due(id, "1 minute");
        fx.jdbc().update("update report_schedules set end_date = current_date - 1 where schedule_id = ?", id);

        job.runOnce();
        settle(id);

        assertThat(scheduleStatus(id)).isEqualTo("ended");
        assertThat(getSchedule(ana, id, 200).get("nextRunAt").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ checks made when the time comes

    private void assertSkippedAs(UUID id, String code) {
        List<Map<String, Object>> runs = runsOf(id);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("status")).isEqualTo("skipped");
        assertThat(runs.get(0).get("error_code")).isEqualTo(code);
    }

    @Test
    void aDeletedTemplateSkipsTheRunAndTellsTheOwner() throws Exception {
        UUID id = scheduleId(ana, schedule("Orphan"));
        fx.jdbc().update("update report_templates set deleted_at = now() where template_id = ?::uuid", templateId);

        due(id, "1 minute");
        job.runOnce();

        assertSkippedAs(id, "TEMPLATE_UNAVAILABLE");
        assertThat(notifications(ana, "report.failed")).isEqualTo(1);
        assertThat(getSchedule(ana, id, 200).get("consecutiveFailures").asInt()).isEqualTo(1);
        assertThat(getSchedule(ana, id, 200).get("lastFailureCode").asString()).isEqualTo("TEMPLATE_UNAVAILABLE");
        assertThat(audits(id.toString(), "REPORT_SCHEDULE_RUN_SKIPPED")).isEqualTo(1);
    }

    @Test
    void aTemplateTheOwnerCanNoLongerSeeSkipsTheRun() throws Exception {
        // Someone else's template, shared to Ana, then un-shared.
        String hers = postAs("/api/v1/reports/templates", admin, Map.of("name", "Admins", "definition", definition()), 201).get("id").asString();
        postAs("/api/v1/reports/templates/" + hers + "/share", admin,
                Map.of("grants", List.of(Map.of("principalType", "user", "principalId", ana.getUserId().toString(), "accessLevel", "run"))), 200);
        Map<String, Object> body = schedule("Borrowed");
        body.put("templateId", hers);
        UUID id = scheduleId(ana, body);
        postAs("/api/v1/reports/templates/" + hers + "/share", admin, Map.of("grants", List.of()), 200);

        due(id, "1 minute");
        job.runOnce();

        assertSkippedAs(id, "TEMPLATE_UNAVAILABLE");
    }

    @Test
    void aFieldTheAdministratorSwitchedOffSkipsTheRun() throws Exception {
        UUID id = scheduleId(ana, schedule("Fields"));
        fx.jdbc().update("insert into report_field_settings (tenant_id, field_key, enabled) values (?, 'status', false)", tenantId);

        due(id, "1 minute");
        job.runOnce();

        assertSkippedAs(id, "FIELDS_UNAVAILABLE");
    }

    @Test
    void anOwnerWhoLostThePermissionToRunReportsSkipsTheRun() throws Exception {
        UUID id = scheduleId(ana, schedule("Lost"));
        fx.jdbc().update("delete from role_permissions where role_id in (select role_id from user_roles where user_id = ?) "
                + "and permission_id = (select permission_id from permissions where permission_code = 'crg.generate-custom-reports.run' "
                + "and tenant_id is null)", ana.getUserId());

        due(id, "1 minute");
        job.runOnce();

        assertSkippedAs(id, "OWNER_PERMISSION_LOST");
    }

    @Test
    void schedulingSwitchedOffSkipsTheRun_andReportsOffAltogetherToo() throws Exception {
        UUID id = scheduleId(ana, schedule("Switched"));
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, scheduling_enabled) values (?, false)", tenantId);

        due(id, "1 minute");
        job.runOnce();

        assertSkippedAs(id, "SCHEDULING_DISABLED");
        fx.jdbc().update("update tenant_report_settings set scheduling_enabled = true, enabled = false where tenant_id = ?", tenantId);
        due(id, "1 minute");
        job.runOnce();
        assertThat(runsOf(id).stream().map(r -> r.get("error_code"))).containsExactlyInAnyOrder("SCHEDULING_DISABLED", "REPORTS_DISABLED");
    }

    @Test
    void aFormatTheOrganisationNoLongerAllowsSkipsTheRun() throws Exception {
        UUID id = scheduleId(ana, schedule("Csv"));
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allowed_formats, default_format) values (?, ARRAY['pdf'], 'pdf')", tenantId);

        due(id, "1 minute");
        job.runOnce();

        assertSkippedAs(id, "FORMAT_NOT_ALLOWED");
    }

    @Test
    void repeatedFailuresPauseTheSchedule_andTheOwnerAndAdministratorsAreTold() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, pause_after_consecutive_failures, failure_admin_user_ids) "
                + "values (?, 2, ARRAY[?::uuid])", tenantId, admin.getUserId());
        UUID id = scheduleId(ana, schedule("Failing"));
        fx.jdbc().update("update report_templates set deleted_at = now() where template_id = ?::uuid", templateId);

        due(id, "1 minute");
        job.runOnce();
        assertThat(scheduleStatus(id)).as("one failure is not enough").isEqualTo("active");
        due(id, "1 minute");
        job.runOnce();

        assertThat(scheduleStatus(id)).isEqualTo("paused_failed");
        JsonNode paused = getSchedule(ana, id, 200);
        assertThat(paused.get("pauseReason").asString()).isEqualTo("TEMPLATE_UNAVAILABLE");
        assertThat(notifications(ana, "report.schedule-paused")).isEqualTo(1);
        assertThat(notifications(admin, "report.schedule-paused")).isEqualTo(1);
        assertThat(notifications(admin, "report.failed")).as("administrators hear about each failure too").isEqualTo(2);
        assertThat(audits(id.toString(), "REPORT_SCHEDULE_PAUSED")).isEqualTo(1);
        // Paused means paused: nothing more happens, however late.
        due(id, "1 hour");
        assertThat(job.runOnce()).isZero();
        assertThat(runsOf(id)).hasSize(2);
    }

    @Test
    void aSuccessfulRunEndsTheStreakOfFailures() throws Exception {
        UUID id = scheduleId(ana, schedule("Recovering"));
        fx.jdbc().update("update report_templates set deleted_at = now() where template_id = ?::uuid", templateId);
        due(id, "1 minute");
        job.runOnce();
        assertThat(getSchedule(ana, id, 200).get("consecutiveFailures").asInt()).isEqualTo(1);
        fx.jdbc().update("update report_templates set deleted_at = null where template_id = ?::uuid", templateId);

        fireAndSettle(id);

        assertThat(getSchedule(ana, id, 200).get("consecutiveFailures").asInt()).isZero();
        assertThat(scheduleStatus(id)).isEqualTo("active");
    }

    @Test
    void aSchedulePausedForFailuresStaysPausedUntilItIsResumedOnPurpose() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, pause_after_consecutive_failures) values (?, 1)", tenantId);
        UUID id = scheduleId(ana, schedule("Stays paused"));
        fx.jdbc().update("update report_templates set deleted_at = now() where template_id = ?::uuid", templateId);
        due(id, "1 minute");
        job.runOnce();
        assertThat(scheduleStatus(id)).isEqualTo("paused_failed");
        fx.jdbc().update("update report_templates set deleted_at = null where template_id = ?::uuid", templateId);

        JsonNode resumed = postAs("/api/v1/reports/schedules/" + id + "/resume", ana, null, 200);

        assertThat(resumed.get("status").asString()).isEqualTo("active");
        assertThat(resumed.get("consecutiveFailures").asInt()).isZero();
        assertThat(resumed.get("pauseReason").isNull()).isTrue();
    }

    @Test
    void whenTheOwnerLeavesTheirSchedulesPauseAtOnce_andAdministratorsAreToldNotTheOwner() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, failure_admin_user_ids) values (?, ARRAY[?::uuid])", tenantId, admin.getUserId());
        UUID id = scheduleId(ana, schedule("Orphaned"));
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", ana.getUserId());

        job.runOnce(); // nothing is due - the owner leaving is enough

        assertThat(scheduleStatus(id)).isEqualTo("paused");
        assertThat(fx.jdbc().queryForObject("select pause_reason from report_schedules where schedule_id = ?", String.class, id)).isEqualTo("OWNER_INACTIVE");
        assertThat(notifications(admin, "report.schedule-paused")).isEqualTo(1);
        assertThat(notifications(ana, "report.schedule-paused")).isZero();
        assertThat(runsOf(id)).isEmpty();
    }

    // ------------------------------------------------------------------ run now

    @Test
    void runNowDeliversLikeAScheduledRun_withoutMovingTheNextRun() throws Exception {
        fx.document(tenantId, typeId, ana, "Alpha contract");
        UUID id = scheduleId(ana, schedule("Now"));
        String before = getSchedule(ana, id, 200).get("nextRunAt").asString();

        JsonNode started = postAs("/api/v1/reports/schedules/" + id + "/run-now", ana, null, 202);
        settle(id);

        assertThat(started.get("runIds")).hasSize(1);
        assertThat(runsOf(id).get(0).get("trigger")).isEqualTo("manual");
        assertThat(runsOf(id).get(0).get("status")).isEqualTo("completed");
        assertThat(notifications(ana, "report.ready")).isEqualTo(1);
        assertThat(OffsetDateTime.parse(getSchedule(ana, id, 200).get("nextRunAt").asString())).isEqualTo(OffsetDateTime.parse(before));
        // Run again straight away: a manual run has no slot, so it is not a duplicate.
        postAs("/api/v1/reports/schedules/" + id + "/run-now", ana, null, 202);
        settle(id);
        assertThat(runsOf(id)).hasSize(2);
    }

    @Test
    void runNowIsTheOwnersAndSaysWhyItCannotRun() throws Exception {
        UUID id = scheduleId(ana, schedule("Careful"));

        postAs("/api/v1/reports/schedules/" + id + "/run-now", ben, null, 404);
        fx.jdbc().update("update report_templates set deleted_at = now() where template_id = ?::uuid", templateId);
        JsonNode refused = postAs("/api/v1/reports/schedules/" + id + "/run-now", ana, null, 422);

        assertThat(refused.get("code").asString()).isEqualTo("SCHEDULE_CANNOT_RUN");
        assertThat(runsOf(id)).as("a refusal leaves no skipped run behind").isEmpty();
    }

    // ------------------------------------------------------------------ the history

    @Test
    void theHistoryShowsEachRunWithCountsOfItsDeliveries() throws Exception {
        UUID id = scheduleId(ana, schedule("History"));
        fireAndSettle(id);

        JsonNode history = call(get("/api/v1/reports/schedules/" + id + "/runs"), fx.jwtFor(ana), null, 200);

        JsonNode entry = history.get("content").get(0);
        assertThat(entry.get("run").get("status").asString()).isEqualTo("completed");
        assertThat(entry.get("sent").asInt()).isEqualTo(1);
        assertThat(entry.get("failed").asInt()).isZero();
        call(get("/api/v1/reports/schedules/" + id + "/runs"), fx.jwtFor(ben), null, 404);
    }

    // ------------------------------------------------------------------ email

    @Test
    void anEmailGoesWithALinkByDefault_notTheFile() throws Exception {
        fx.document(tenantId, typeId, ana, "Alpha contract");
        Map<String, Object> body = schedule("Mail link");
        body.put("deliveryChannels", List.of("email"));
        UUID id = scheduleId(ana, body);

        fireAndSettle(id);

        List<MimeMessage> mails = reportMails(ana.getEmail());
        assertThat(mails).hasSize(1);
        UUID runId = (UUID) runsOf(id).get(0).get("run_id");
        assertThat(bodyOf(mails.get(0))).contains("/reports/runs/" + runId);
        assertThat(attachmentsOf(mails.get(0))).isEmpty();
        assertThat(deliveryStatuses(runId)).containsExactly("email:sent");
        assertThat(fx.jdbc().queryForObject("select delivery_mode from report_run_deliveries where run_id = ?", String.class, runId)).isEqualTo("link");
    }

    @Test
    void anAttachmentIsSentWhenTheOrganisationAllowsIt() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allow_attachments) values (?, true)", tenantId);
        fx.document(tenantId, typeId, ana, "Alpha contract");
        Map<String, Object> body = schedule("Mail file");
        body.put("deliveryChannels", List.of("email"));
        body.put("deliveryMode", "attachment");
        UUID id = scheduleId(ana, body);

        fireAndSettle(id);

        List<MimeMessage> mails = reportMails(ana.getEmail());
        assertThat(mails).hasSize(1);
        List<BodyPart> files = attachmentsOf(mails.get(0));
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName()).endsWith(".csv");
        assertThat(new String(files.get(0).getInputStream().readAllBytes(), StandardCharsets.UTF_8)).contains("Alpha contract");
        UUID runId = (UUID) runsOf(id).get(0).get("run_id");
        assertThat(fx.jdbc().queryForObject("select delivery_mode from report_run_deliveries where run_id = ?", String.class, runId)).isEqualTo("attachment");
    }

    @Test
    void aFileTooBigToAttachFallsBackToTheLinkAndSaysSo() {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allow_attachments, max_attachment_mb) values (?, true, 1)", tenantId);
        ReportRun big = ReportRun.builder().tenantId(tenantId).requestedBy(ana.getUserId()).triggerType("scheduled").format("csv")
                .fileObjectKey("x").fileSizeBytes(5L * 1024 * 1024).storageLocationId(UUID.randomUUID()).build();

        ReportDeliveryChannel.Outcome outcome = emailDelivery.deliver(new ReportDeliveryChannel.Delivery(big, "Big", settingsService.forTenant(tenantId),
                new ReportDeliveryChannel.Target(ana.getUserId(), null), "attachment", true));

        assertThat(outcome.status()).isEqualTo(ReportDeliveryChannel.Outcome.Status.SENT);
        assertThat(outcome.mode()).isEqualTo("link");
        assertThat(outcome.note()).isEqualTo("LINK_FALLBACK_TOO_LARGE");
    }

    @Test
    void attachmentsSwitchedOffAfterTheScheduleWasSavedFallBackToTheLink() {
        ReportRun run = ReportRun.builder().tenantId(tenantId).requestedBy(ana.getUserId()).triggerType("scheduled").format("csv")
                .fileObjectKey("x").fileSizeBytes(10L).storageLocationId(UUID.randomUUID()).build();

        ReportDeliveryChannel.Outcome outcome = emailDelivery.deliver(new ReportDeliveryChannel.Delivery(run, "Small", settingsService.forTenant(tenantId),
                new ReportDeliveryChannel.Target(ana.getUserId(), null), "attachment", true));

        assertThat(outcome.mode()).isEqualTo("link");
        assertThat(outcome.note()).isEqualTo("LINK_FALLBACK_NOT_ALLOWED");
    }

    @Test
    void anExternalAddressGetsTheFileAttachedUnderTheOwnersAccess_andNeverIsStoredInTheClear() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allow_external_recipients, external_domain_allowlist, allow_attachments) "
                + "values (?, true, ARRAY['partner.example'], true)", tenantId);
        fx.document(tenantId, typeId, admin, "Open contract");
        UUID secret = fx.document(tenantId, typeId, admin, "Board minutes");
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", secret);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level) "
                + "values (?, ?, 'user', ?, 'manage')", tenantId, secret, admin.getUserId());
        Map<String, Object> body = scheduleFor(admin, "To the client");
        body.put("deliveryChannels", List.of("email"));
        body.put("deliveryMode", "attachment");
        body.put("recipients", List.of(recipient("email", "client@partner.example")));
        UUID id = scheduleId(admin, body);

        fireAndSettle(id);

        List<Map<String, Object>> runs = runsOf(id);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("delivery_scope")).isEqualTo("external");
        List<MimeMessage> mails = reportMails("client@partner.example");
        assertThat(mails).hasSize(1);
        String file = new String(attachmentsOf(mails.get(0)).get(0).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(file).as("evaluated under the OWNER's access").contains("Open contract").contains("Board minutes");
        UUID runId = (UUID) runs.get(0).get("run_id");
        assertThat(deliveryStatuses(runId)).containsExactly("email:sent");
        // The address is nowhere in the tables an export or an audit view could reach.
        assertThat(fx.jdbc().queryForObject("select count(*) from report_run_deliveries where run_id = ? and recipient_ref_hash like '%partner%'",
                Long.class, runId)).isZero();
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and (new_value::text ilike '%partner.example%' "
                + "or coalesce(previous_value::text, '') ilike '%partner.example%')", tenantId)).isZero();
        assertThat(fx.jdbc().queryForObject("select new_value->>'recipientKind' from audit_events where object_id = ?::uuid and action = 'REPORT_DELIVERED'",
                String.class, runId.toString())).isEqualTo("external");
        assertThat(fx.jdbc().queryForObject("select severity from audit_events where object_id = ?::uuid and action = 'REPORT_DELIVERED'",
                String.class, runId.toString())).isEqualTo("MEDIUM");
    }

    @Test
    void externalDeliveryIsRefusedAtSendTimeIfTheOrganisationChangedItsMind() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, allow_external_recipients, external_domain_allowlist, allow_attachments) "
                + "values (?, true, ARRAY['partner.example'], true)", tenantId);
        Map<String, Object> body = scheduleFor(admin, "To the client");
        body.put("deliveryChannels", List.of("email"));
        body.put("deliveryMode", "attachment");
        body.put("recipients", List.of(recipient("email", "client@partner.example")));
        UUID id = scheduleId(admin, body);
        fx.jdbc().update("update tenant_report_settings set allow_external_recipients = false where tenant_id = ?", tenantId);

        fireAndSettle(id);

        assertThat(reportMails("client@partner.example")).isEmpty();
        UUID runId = (UUID) runsOf(id).get(0).get("run_id");
        assertThat(fx.jdbc().queryForObject("select error_code from report_run_deliveries where run_id = ?", String.class, runId)).isEqualTo("EXTERNAL_NOT_ALLOWED");
        assertThat(fx.jdbc().queryForObject("select status from report_run_deliveries where run_id = ?", String.class, runId)).isEqualTo("failed");
    }

    // ------------------------------------------------------------------ retries

    @Test
    void aFailingMailServerIsRetriedAfterOneFiveAndFifteenMinutes_thenTheOwnerGetsOneSummary() throws Exception {
        Map<String, Object> body = schedule("Flaky");
        body.put("deliveryChannels", List.of("in_app", "email"));
        UUID id = scheduleId(ana, body);
        doThrow(new MailSendException("down")).when(mailSender).send(any(), any(), any(), any(), any(), anyList());

        fireAndSettle(id);

        UUID runId = (UUID) runsOf(id).get(0).get("run_id");
        assertThat(runsOf(id).get(0).get("status")).as("the run is fine; only a delivery is not").isEqualTo("completed");
        assertThat(deliveryStatuses(runId)).containsExactly("email:pending", "in_app:sent");
        assertThat(waitMinutes(runId)).isBetween(0.5, 1.5);
        int[] expected = {5, 15};
        for (int wait : expected) {
            fx.jdbc().update("update report_run_deliveries set next_attempt_at = now() where run_id = ? and channel = 'email'", runId);
            deliveries.attemptRun(runId);
            assertThat(deliveryStatuses(runId)).containsExactly("email:pending", "in_app:sent");
            assertThat(waitMinutes(runId)).isBetween(wait - 0.5, wait + 0.5);
        }
        assertThat(notifications(ana, "report.failed")).as("no summary while it is still being retried").isZero();

        fx.jdbc().update("update report_run_deliveries set next_attempt_at = now() where run_id = ? and channel = 'email'", runId);
        deliveries.attemptRun(runId);

        assertThat(deliveryStatuses(runId)).containsExactly("email:failed", "in_app:sent");
        assertThat(fx.jdbc().queryForObject("select attempts from report_run_deliveries where run_id = ? and channel = 'email'", Integer.class, runId)).isEqualTo(4);
        assertThat(fx.jdbc().queryForObject("select error_code from report_run_deliveries where run_id = ? and channel = 'email'", String.class, runId))
                .isEqualTo("MAIL_SEND_FAILED");
        assertThat(notifications(ana, "report.failed")).isEqualTo(1);
        // Nothing more is tried, and the summary is not repeated.
        deliveries.attemptRun(runId);
        deliveries.poll();
        assertThat(notifications(ana, "report.failed")).isEqualTo(1);
    }

    @Test
    void aDeliveryThatWorksOnARetryIsDeliveredOnceAndNoSummaryIsSent() throws Exception {
        Map<String, Object> body = schedule("Recovers");
        body.put("deliveryChannels", List.of("email"));
        UUID id = scheduleId(ana, body);
        doThrow(new MailSendException("down")).doCallRealMethod().when(mailSender).send(any(), any(), any(), any(), any(), anyList());

        fireAndSettle(id);
        UUID runId = (UUID) runsOf(id).get(0).get("run_id");
        assertThat(deliveryStatuses(runId)).containsExactly("email:pending");
        fx.jdbc().update("update report_run_deliveries set next_attempt_at = now() where run_id = ?", runId);
        deliveries.attemptRun(runId);

        assertThat(deliveryStatuses(runId)).containsExactly("email:sent");
        assertThat(reportMails(ana.getEmail())).hasSize(1);
        assertThat(fx.jdbc().queryForObject("select attempts from report_run_deliveries where run_id = ?", Integer.class, runId)).isEqualTo(2);
        assertThat(notifications(ana, "report.failed")).isZero();
        assertThat(audits(runId.toString(), "REPORT_DELIVERED")).isEqualTo(1);
        verify(mailSender, times(2)).send(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void aRecipientWithNoEmailAddressFailsAtOnceWithoutRetries() throws Exception {
        Map<String, Object> body = schedule("No address");
        body.put("deliveryChannels", List.of("email"));
        UUID id = scheduleId(ana, body);
        fx.jdbc().update("update users set email = '' where user_id = ?", ana.getUserId());

        fireAndSettle(id);

        UUID runId = (UUID) runsOf(id).get(0).get("run_id");
        assertThat(deliveryStatuses(runId)).containsExactly("email:failed");
        assertThat(fx.jdbc().queryForObject("select error_code from report_run_deliveries where run_id = ?", String.class, runId)).isEqualTo("NO_EMAIL_ADDRESS");
        assertThat(fx.jdbc().queryForObject("select attempts from report_run_deliveries where run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(notifications(ana, "report.failed")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ empty and failed runs

    private UUID insertFinishedScheduledRun(UUID schedule, String status, String errorCode) {
        UUID runId = UUID.randomUUID();
        fx.jdbc().update("insert into report_runs (run_id, tenant_id, template_id, schedule_id, requested_by, delivery_scope, trigger, format, "
                        + "status, definition_snapshot, scheduled_for, row_count, error_code, error_message, finished_at) "
                        + "values (?, ?, ?::uuid, ?, ?, 'owner', 'scheduled', 'csv', ?, cast(? as jsonb), now() - interval '1 minute', 0, ?, ?, now())",
                runId, tenantId, templateId, schedule, ana.getUserId(), status,
                "{\"definition\":{\"source\":\"documents\",\"columns\":[\"title\"],\"filters\":[],\"sort\":[]},\"zone\":\"UTC\",\"title\":\"Contracts\"}",
                errorCode, errorCode == null ? null : "The report could not be produced.");
        return runId;
    }

    @Test
    void anEmptyScheduledReportIsDeliveredSayingNoDataFound() throws Exception {
        UUID id = scheduleId(ana, schedule("Empty"));
        UUID runId = insertFinishedScheduledRun(id, "completed", null);

        assertThat(deliveries.prepare(runId)).isTrue();
        deliveries.attemptRun(runId);

        assertThat(deliveryStatuses(runId)).containsExactly("in_app:sent");
        assertThat(fx.jdbc().queryForObject("select body from notifications where recipient_user_id = ? and category_code = 'report.ready'", String.class,
                ana.getUserId())).contains("No data found");
        assertThat(fx.jdbc().queryForObject("select link_url from notifications where recipient_user_id = ? and category_code = 'report.ready'", String.class,
                ana.getUserId())).contains("/reports/runs/");
    }

    @Test
    void anEmptyScheduledReportIsNotSentIfTheOrganisationChoseToSkipThose() throws Exception {
        fx.jdbc().update("insert into tenant_report_settings (tenant_id, skip_empty_scheduled_reports) values (?, true)", tenantId);
        UUID id = scheduleId(ana, schedule("Skip empty"));
        UUID runId = insertFinishedScheduledRun(id, "completed", null);

        deliveries.prepare(runId);
        deliveries.attemptRun(runId);

        Map<String, Object> run = fx.jdbc().queryForMap("select status, delivered, delivery_note from report_runs where run_id = ?", runId);
        assertThat(run.get("status")).isEqualTo("completed");
        assertThat(run.get("delivered")).isEqualTo(false);
        assertThat(run.get("delivery_note")).isEqualTo("EMPTY");
        assertThat(deliveryStatuses(runId)).isEmpty();
        assertThat(notifications(ana, "report.ready")).isZero();
    }

    @Test
    void aFailedScheduledRunNotifiesTheOwnerAndCountsAgainstTheSchedule() throws Exception {
        UUID id = scheduleId(ana, schedule("Fails"));
        UUID runId = insertFinishedScheduledRun(id, "failed", "REPORT_FAILED");

        deliveries.prepare(runId);

        assertThat(notifications(ana, "report.failed")).isEqualTo(1);
        assertThat(getSchedule(ana, id, 200).get("consecutiveFailures").asInt()).isEqualTo(1);
        assertThat(fx.jdbc().queryForObject("select delivery_note from report_runs where run_id = ?", String.class, runId)).isEqualTo("RUN_FAILED");
    }

    @Test
    void preparingARunTwiceDeliversItOnce() throws Exception {
        UUID id = scheduleId(ana, schedule("Once"));
        UUID runId = insertFinishedScheduledRun(id, "completed", null);

        assertThat(deliveries.prepare(runId)).isTrue();
        assertThat(deliveries.prepare(runId)).as("already claimed").isFalse();
        deliveries.attemptRun(runId);
        deliveries.attemptRun(runId);

        assertThat(notifications(ana, "report.ready")).isEqualTo(1);
        assertThat(deliveryStatuses(runId)).containsExactly("in_app:sent");
    }

    @Test
    void theDeliveryPollerAdoptsAFinishedRunWhoseServerDiedBeforePreparingIt() throws Exception {
        UUID id = scheduleId(ana, schedule("Orphaned run"));
        UUID runId = insertFinishedScheduledRun(id, "completed", null);

        deliveries.poll();

        assertThat(deliveryStatuses(runId)).containsExactly("in_app:sent");
        assertThat(notifications(ana, "report.ready")).isEqualTo(1);
    }

    @Test
    void aScheduleCancelledWhileItsRunWasInFlightIsNotDelivered() throws Exception {
        UUID id = scheduleId(ana, schedule("Cancelled meanwhile"));
        UUID runId = insertFinishedScheduledRun(id, "completed", null);
        fx.jdbc().update("update report_schedules set status = 'cancelled' where schedule_id = ?", id);

        deliveries.prepare(runId);

        assertThat(deliveryStatuses(runId)).isEmpty();
        assertThat(fx.jdbc().queryForObject("select delivery_note from report_runs where run_id = ?", String.class, runId)).isEqualTo("SCHEDULE_CANCELLED");
        assertThat(notifications(ana, "report.ready")).isZero();
    }

    private double waitMinutes(UUID runId) {
        return fx.jdbc().queryForObject("select extract(epoch from (next_attempt_at - now())) / 60 from report_run_deliveries "
                + "where run_id = ? and channel = 'email'", Double.class, runId);
    }

    // ------------------------------------------------------------------ one email, hourly

    @Test
    void whenInAppAndEmailAreBothChosen_thePersonGetsOneEmail_theReportOne() throws Exception {
        Map<String, Object> body = schedule("Both channels");
        body.put("deliveryChannels", List.of("in_app", "email"));
        UUID id = scheduleId(ana, body);

        fireAndSettle(id);

        UUID runId = (UUID) runsOf(id).get(0).get("run_id");
        assertThat(deliveryStatuses(runId)).containsExactly("email:sent", "in_app:sent");
        assertThat(reportMails(ana.getEmail())).as("the report email").hasSize(1);
        assertThat(notifications(ana, "report.ready")).as("the inbox entry is still written").isEqualTo(1);
        // The platform's own "report ready" email is not sent as well: it is recorded as sent by the other channel.
        assertThat(fx.jdbc().queryForObject("""
                select count(*) from notification_deliveries d join notifications n on n.notification_id = d.notification_id
                where n.recipient_user_id = ? and n.category_code = 'report.ready' and d.channel = 'email'
                  and d.status = 'skipped' and d.skip_reason = 'sent_by_other_channel'""", Long.class, ana.getUserId())).isEqualTo(1);
        assertThat(fx.jdbc().queryForObject("""
                select count(*) from notification_deliveries d join notifications n on n.notification_id = d.notification_id
                where n.recipient_user_id = ? and n.category_code = 'report.ready' and d.channel = 'email' and d.status = 'pending'""",
                Long.class, ana.getUserId())).isZero();
    }

    @Test
    void inAppOnly_stillLetsThePlatformEmailFollowThePersonsOwnPreference() throws Exception {
        UUID id = scheduleId(ana, schedule("In-app only"));

        fireAndSettle(id);

        assertThat(fx.jdbc().queryForObject("""
                select count(*) from notification_deliveries d join notifications n on n.notification_id = d.notification_id
                where n.recipient_user_id = ? and n.category_code = 'report.ready' and d.channel = 'email'
                  and d.skip_reason = 'sent_by_other_channel'""", Long.class, ana.getUserId())).isZero();
    }

    @Test
    void aPersonListedDirectlyAndThroughARole_getsOneRunAndOneEmail() throws Exception {
        var team = fx.role(tenantId, "Readers " + UUID.randomUUID(), RUN_ONLY, ben);
        Map<String, Object> body = schedule("Twice listed");
        body.put("deliveryChannels", List.of("email"));
        body.put("recipients", List.of(recipient("user", ben.getUserId()), recipient("role", team.getRoleId())));
        UUID id = scheduleId(ana, body);

        fireAndSettle(id);

        assertThat(runsOf(id)).hasSize(1);
        assertThat(reportMails(ben.getEmail())).hasSize(1);
    }

    @Test
    void anHourlyScheduleIsRefusedUnlessTheOrganisationAllowsIt_thenFiresEveryHour() throws Exception {
        Map<String, Object> body = schedule("Hourly");
        body.put("frequency", "hourly");
        body.put("timeOfDay", "00:20");
        assertThat(createSchedule(ana, body, 422).get("code").asString()).isEqualTo("SCHEDULE_HOURLY_NOT_ALLOWED");

        boolean before = properties.isAllowHourlySchedules();
        properties.setAllowHourlySchedules(true);
        try {
            fx.jdbc().update("insert into tenant_report_settings (tenant_id, min_schedule_interval) values (?, 'hourly')", tenantId);
            JsonNode created = createSchedule(ana, body, 201);
            OffsetDateTime next = OffsetDateTime.parse(created.get("nextRunAt").asString());
            assertThat(next).isAfter(OffsetDateTime.now()).isBefore(OffsetDateTime.now().plusMinutes(61));
            UUID id = UUID.fromString(created.get("id").asString());

            fireAndSettle(id);
            assertThat(runsOf(id)).hasSize(1);
            OffsetDateTime after = OffsetDateTime.parse(getSchedule(ana, id, 200).get("nextRunAt").asString());
            assertThat(after).isAfter(OffsetDateTime.now()).isBefore(OffsetDateTime.now().plusMinutes(61));

            // The organisation goes back to daily: the existing hourly schedule is skipped with a clear reason, not run.
            fx.jdbc().update("update tenant_report_settings set min_schedule_interval = 'daily' where tenant_id = ?", tenantId);
            due(id, "1 minute");
            job.runOnce();
            assertThat(fx.jdbc().queryForObject("select count(*) from audit_events where action = 'REPORT_SCHEDULE_RUN_SKIPPED' "
                    + "and tenant_id = ? and new_value::text like '%FREQUENCY_NOT_ALLOWED%'", Long.class, tenantId)).isEqualTo(1);
        } finally {
            properties.setAllowHourlySchedules(before);
        }
    }
}
