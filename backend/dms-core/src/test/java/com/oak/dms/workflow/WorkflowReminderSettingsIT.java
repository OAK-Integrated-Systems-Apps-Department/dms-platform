package com.oak.dms.workflow;

import com.oak.dms.user.User;
import com.oak.dms.workflow.service.WorkflowSlaService;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The reminder and escalation settings on the template and stage APIs (3.2, N-FR-006): template defaults and
 * stage overrides (null = inherit) are saved and read back, validated, copied to a new version - and actually
 * take effect: a stage with only the template's defaults gets its inactivity reminder, escalation target and
 * due-date reminder.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowReminderSettingsIT extends WorkflowApiSupport {

    private static final String TEMPLATES = "/api/v1/workflow/templates";

    @Autowired
    private WorkflowSlaService slaService;

    private ContractWorld w;
    private User manager;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
        manager = fx.user(w.tenantId(), "Manager");
        fx.role(w.tenantId(), "Managers " + UUID.randomUUID(), WorkflowFixtures.APPROVER_PERMISSIONS, manager);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> templateBody(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", code + " approval");
        body.put("code", code);
        body.put("documentTypeId", w.contractTypeId());
        return body;
    }

    private Map<String, Object> withDefaults(Map<String, Object> body, Integer reminderBefore, Integer inactivity,
                                             Integer escalateAfter, String target, UUID user) {
        body.put("defaultReminderBeforeHours", reminderBefore);
        body.put("defaultInactivityReminderHours", inactivity);
        body.put("defaultEscalateAfterReminderHours", escalateAfter);
        body.put("defaultEscalationTarget", target);
        body.put("defaultEscalationUserId", user);
        return body;
    }

    private Map<String, Object> updateBody(String name, Integer reminderBefore, Integer inactivity, Integer escalateAfter,
                                           String target, UUID user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("documentTypeId", w.contractTypeId());
        body.put("onDocumentChange", "flag_only");
        return withDefaults(body, reminderBefore, inactivity, escalateAfter, target, user);
    }

    private Map<String, Object> stage(Integer sla, Integer reminderBefore, Integer inactivity, Integer escalateAfter) {
        Map<String, Object> s = stageDef("Review", "any", "halt_immediately", sla, entry("user", w.nimal().getUserId()));
        s.put("reminderBeforeHours", reminderBefore);
        s.put("inactivityReminderHours", inactivity);
        s.put("escalateAfterReminderHours", escalateAfter);
        return s;
    }

    private JsonNode read(UUID templateId) throws Exception {
        return call(get(TEMPLATES + "/" + templateId), fx.tenantAdminJwtFor(w.admin()), null, 200);
    }

    private UUID create(Map<String, Object> body) throws Exception {
        return id(adminPost(TEMPLATES, w.admin(), body, 201), "templateId");
    }

    private JsonNode addStage(UUID templateId, Map<String, Object> stage, int expected) throws Exception {
        return adminPost(TEMPLATES + "/" + templateId + "/stages", w.admin(), stage, expected);
    }

    private static String code(JsonNode error) {
        return error.get("code").asString();
    }

    private UUID activate(UUID templateId) throws Exception {
        adminPost(TEMPLATES + "/" + templateId + "/activate", w.admin(), null, 200);
        return templateId;
    }

    private long stamped(UUID task, String column) {
        return fx.count("select count(*) from workflow_tasks where task_id = ? and " + column + " is not null", task);
    }

    /** The suite shares one DB, so the scan's batch may be full of other tests' due tasks - drain until ours fires. */
    private void runSlaUntil(UUID task, String column) {
        for (int i = 0; i < 40 && stamped(task, column) == 0; i++) {
            slaService.runOnce();
        }
    }

    private UUID submittedTask(UUID documentId) throws Exception {
        return taskId(submit(w.chamath(), documentId, 201), 1, w.nimal().getDisplayName());
    }

    private long notifications(User user, String category) {
        return fx.count("select count(*) from notifications where recipient_user_id = ? and category_code = ?",
                user.getUserId(), category);
    }

    // ------------------------------------------------------------------ save and read back

    @Test
    void templateDefaults_areSavedReadBackReplacedAndCleared() throws Exception {
        JsonNode created = adminPost(TEMPLATES, w.admin(), withDefaults(templateBody("DEF"), 24, 48, 12,
                "named_user", manager.getUserId()), 201);
        UUID id = id(created, "templateId");
        for (JsonNode t : new JsonNode[]{created, read(id)}) {
            assertThat(t.get("defaultReminderBeforeHours").asInt()).isEqualTo(24);
            assertThat(t.get("defaultInactivityReminderHours").asInt()).isEqualTo(48);
            assertThat(t.get("defaultEscalateAfterReminderHours").asInt()).isEqualTo(12);
            assertThat(t.get("defaultEscalationTarget").asString()).isEqualTo("named_user");
            assertThat(t.get("defaultEscalationUserId").asString()).isEqualTo(manager.getUserId().toString());
        }

        // Replaced as a whole; a target other than named_user drops the user.
        JsonNode replaced = adminPut(TEMPLATES + "/" + id, w.admin(),
                updateBody("DEF approval", 10, 72, 36, "department_head", manager.getUserId()), 200);
        assertThat(replaced.get("defaultReminderBeforeHours").asInt()).isEqualTo(10);
        assertThat(replaced.get("defaultInactivityReminderHours").asInt()).isEqualTo(72);
        assertThat(replaced.get("defaultEscalateAfterReminderHours").asInt()).isEqualTo(36);
        assertThat(replaced.get("defaultEscalationTarget").asString()).isEqualTo("department_head");
        assertThat(replaced.get("defaultEscalationUserId").isNull()).isTrue();

        // Leaving them out clears them.
        JsonNode cleared = adminPut(TEMPLATES + "/" + id, w.admin(),
                updateBody("DEF approval", null, null, null, null, null), 200);
        assertThat(cleared.get("defaultReminderBeforeHours").isNull()).isTrue();
        assertThat(cleared.get("defaultInactivityReminderHours").isNull()).isTrue();
        assertThat(cleared.get("defaultEscalateAfterReminderHours").isNull()).isTrue();
        assertThat(cleared.get("defaultEscalationTarget").asString()).isEqualTo("none");
        assertThat(read(id).get("defaultEscalationUserId").isNull()).isTrue();
    }

    @Test
    void aTemplateCreatedWithoutThem_hasNoDefaults() throws Exception {
        UUID id = create(templateBody("PLAIN"));
        JsonNode t = read(id);
        assertThat(t.get("defaultReminderBeforeHours").isNull()).isTrue();
        assertThat(t.get("defaultInactivityReminderHours").isNull()).isTrue();
        assertThat(t.get("defaultEscalateAfterReminderHours").isNull()).isTrue();
        assertThat(t.get("defaultEscalationTarget").asString()).isEqualTo("none");
    }

    @Test
    void stageOverrides_areSavedAndReadBack_nullMeansInherit() throws Exception {
        UUID id = create(withDefaults(templateBody("OVR"), null, 48, 24, "none", null));

        JsonNode added = addStage(id, stage(null, null, 12, 6), 201);
        JsonNode s = added.get("stages").get(0);
        assertThat(s.get("inactivityReminderHours").asInt()).isEqualTo(12);
        assertThat(s.get("escalateAfterReminderHours").asInt()).isEqualTo(6);
        UUID stageId = id(s, "stageId");

        // A stage left at null inherits - the template's values are not copied onto it.
        JsonNode inherited = adminPut(TEMPLATES + "/" + id + "/stages/" + stageId, w.admin(),
                stage(null, null, null, null), 200).get("stages").get(0);
        assertThat(inherited.get("inactivityReminderHours").isNull()).isTrue();
        assertThat(inherited.get("escalateAfterReminderHours").isNull()).isTrue();
        assertThat(read(id).get("stages").get(0).get("inactivityReminderHours").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ validation

    @Test
    void hoursMustBePositive() throws Exception {
        for (String field : new String[]{"defaultReminderBeforeHours", "defaultInactivityReminderHours",
                "defaultEscalateAfterReminderHours"}) {
            Map<String, Object> body = templateBody("NEG-" + field.length());
            body.put(field, 0);
            adminPost(TEMPLATES, w.admin(), body, 400);
            body.put(field, -5);
            adminPost(TEMPLATES, w.admin(), body, 400);
        }
        UUID id = create(templateBody("POS"));
        adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", null, 0, null, null, null), 400);
        addStage(id, stage(null, null, 0, null), 400);
        addStage(id, stage(null, null, 5, 0), 400);
    }

    @Test
    void templateEscalateAfter_needsAnInactivityReminder() throws Exception {
        assertThat(code(adminPost(TEMPLATES, w.admin(),
                withDefaults(templateBody("EA1"), null, null, 24, "none", null), 422)))
                .isEqualTo("WORKFLOW_INVALID_TEMPLATE_DEFAULTS");
        UUID id = create(templateBody("EA2"));
        assertThat(code(adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", null, null, 24, null, null), 422)))
                .isEqualTo("WORKFLOW_INVALID_TEMPLATE_DEFAULTS");
        adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", null, 48, 24, null, null), 200);
    }

    @Test
    void namedUserDefault_needsAnActiveUser() throws Exception {
        assertThat(code(adminPost(TEMPLATES, w.admin(),
                withDefaults(templateBody("NU1"), null, 48, 24, "named_user", null), 422)))
                .isEqualTo("WORKFLOW_INVALID_TEMPLATE_DEFAULTS");
        assertThat(code(adminPost(TEMPLATES, w.admin(),
                withDefaults(templateBody("NU2"), null, 48, 24, "named_user", UUID.randomUUID()), 422)))
                .isEqualTo("WORKFLOW_INVALID_ASSIGNEE");
        // Another tenant's user is "not found" too.
        User foreigner = fx.user(fx.tenant(), "Foreigner");
        assertThat(code(adminPost(TEMPLATES, w.admin(),
                withDefaults(templateBody("NU3"), null, 48, 24, "named_user", foreigner.getUserId()), 422)))
                .isEqualTo("WORKFLOW_INVALID_ASSIGNEE");
    }

    @Test
    void stageEscalateAfter_needsAnInactivityReminderItOwnsOrInherits() throws Exception {
        UUID noDefault = create(templateBody("SE1"));
        assertThat(code(addStage(noDefault, stage(null, null, null, 6), 422))).isEqualTo("WORKFLOW_INVALID_STAGE");
        addStage(noDefault, stage(null, null, 12, 6), 201);          // its own inactivity reminder

        UUID withDefault = create(withDefaults(templateBody("SE2"), null, 48, null, "none", null));
        addStage(withDefault, stage(null, null, null, 6), 201);      // inherits the template's
    }

    @Test
    void theTemplateInactivityDefault_cannotBeRemovedFromUnderAStageThatInheritsIt() throws Exception {
        UUID id = create(withDefaults(templateBody("RM"), null, 48, null, "none", null));
        JsonNode added = addStage(id, stage(null, null, null, 6), 201);   // escalate-after, inactivity inherited

        assertThat(code(adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", null, null, null, null, null), 422)))
                .isEqualTo("WORKFLOW_INVALID_TEMPLATE_DEFAULTS");
        assertThat(read(id).get("defaultInactivityReminderHours").asInt()).isEqualTo(48);   // unchanged

        // Give the stage its own inactivity reminder and the default can go.
        adminPut(TEMPLATES + "/" + id + "/stages/" + id(added.get("stages").get(0), "stageId"), w.admin(),
                stage(null, null, 12, 6), 200);
        adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", null, null, null, null, null), 200);
    }

    // ------------------------------------------------------------------ versions

    @Test
    void aNewVersion_copiesTheDefaultsAndTheStageOverrides() throws Exception {
        UUID id = create(withDefaults(templateBody("VER"), 24, 48, 12, "named_user", manager.getUserId()));
        addStage(id, stage(72, 24, 36, 18), 201);
        activate(id);

        JsonNode v2 = adminPost(TEMPLATES + "/" + id + "/versions", w.admin(), null, 201);
        assertThat(v2.get("versionNumber").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asString()).isEqualTo("draft");
        assertThat(v2.get("defaultReminderBeforeHours").asInt()).isEqualTo(24);
        assertThat(v2.get("defaultInactivityReminderHours").asInt()).isEqualTo(48);
        assertThat(v2.get("defaultEscalateAfterReminderHours").asInt()).isEqualTo(12);
        assertThat(v2.get("defaultEscalationTarget").asString()).isEqualTo("named_user");
        assertThat(v2.get("defaultEscalationUserId").asString()).isEqualTo(manager.getUserId().toString());
        JsonNode s = v2.get("stages").get(0);
        assertThat(s.get("reminderBeforeHours").asInt()).isEqualTo(24);
        assertThat(s.get("inactivityReminderHours").asInt()).isEqualTo(36);
        assertThat(s.get("escalateAfterReminderHours").asInt()).isEqualTo(18);
    }

    // ------------------------------------------------------------------ end to end: the defaults take effect

    @Test
    void aStageWithOnlyTheTemplateDefaults_getsAnInactivityReminder() throws Exception {
        UUID id = create(withDefaults(templateBody("E2E"), null, 48, null, "none", null));
        addStage(id, stage(null, null, null, null), 201);            // nothing of its own
        activate(id);
        UUID task = submittedTask(w.contractDocumentId());

        // No activity for 49 hours - past the template's 48-hour default.
        assertThat(fx.jdbc().update("update workflow_tasks set last_activity_at = now() - interval '49 hours'"
                + " where task_id = ?", task)).isEqualTo(1);
        runSlaUntil(task, "inactivity_reminder_sent_at");

        assertThat(stamped(task, "inactivity_reminder_sent_at")).isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_actions where task_id = ? and action_type = 'reminded'"
                + " and detail ->> 'reason' = 'inactivity'", task)).isEqualTo(1);
        assertThat(notifications(w.nimal(), "workflow.task.reminder")).isEqualTo(1);
    }

    @Test
    void aStageWithoutATarget_escalatesToTheTemplateDefaultTarget_afterTheInactivityReminder() throws Exception {
        UUID id = create(withDefaults(templateBody("ESC"), null, 48, 24, "named_user", manager.getUserId()));
        addStage(id, stage(null, null, null, null), 201);            // escalation target none: inherits
        activate(id);
        UUID task = submittedTask(w.contractDocumentId());

        // The inactivity reminder went out 25 hours ago; the template says escalate 24 hours after it.
        assertThat(fx.jdbc().update("update workflow_tasks set inactivity_reminder_sent_at = now() - interval '25 hours'"
                + " where task_id = ?", task)).isEqualTo(1);
        runSlaUntil(task, "escalated_at");

        assertThat(stamped(task, "escalated_at")).isEqualTo(1);
        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and escalated_to = ?", task,
                manager.getUserId())).isEqualTo(1);
        assertThat(notifications(manager, "workflow.task.escalated")).isEqualTo(1);
    }

    @Test
    void aStageTargetOfItsOwn_winsOverTheTemplateDefault() throws Exception {
        User other = fx.user(w.tenantId(), "Other");
        fx.role(w.tenantId(), "Others " + UUID.randomUUID(), WorkflowFixtures.APPROVER_PERMISSIONS, other);
        UUID id = create(withDefaults(templateBody("OWN"), null, 48, 24, "named_user", manager.getUserId()));
        Map<String, Object> own = stage(null, null, null, null);
        own.put("escalationTarget", "named_user");
        own.put("escalationUserId", other.getUserId());
        addStage(id, own, 201);
        activate(id);
        UUID task = submittedTask(w.contractDocumentId());

        fx.jdbc().update("update workflow_tasks set inactivity_reminder_sent_at = now() - interval '25 hours'"
                + " where task_id = ?", task);
        runSlaUntil(task, "escalated_at");

        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and escalated_to = ?", task,
                other.getUserId())).isEqualTo(1);
        assertThat(notifications(manager, "workflow.task.escalated")).isZero();
    }

    @Test
    void theDueDateReminderDefault_isInheritedOnlyWhenShorterThanTheStageSla() throws Exception {
        // Default: remind 24 hours before the due time.
        UUID longSla = create(withDefaults(templateBody("DUE1"), 24, null, null, "none", null));
        addStage(longSla, stage(48, null, null, null), 201);         // SLA 48 h > 24 h: inherits
        activate(longSla);
        UUID inherits = submittedTask(w.contractDocumentId());
        fx.jdbc().update("update workflow_tasks set due_at = now() + interval '23 hours' where task_id = ?", inherits);
        runSlaUntil(inherits, "reminder_sent_at");
        assertThat(stamped(inherits, "reminder_sent_at")).isEqualTo(1);

        // A stage whose SLA (12 h) is shorter than the 24 h default would be "already due" on creation: no reminder.
        UUID invoiceDoc = fx.document(w.tenantId(), w.invoiceTypeId(), w.chamath(), "Invoice 7");
        Map<String, Object> body = templateBody("DUE2");
        body.put("documentTypeId", w.invoiceTypeId());
        UUID shortSla = create(withDefaults(body, 24, null, null, "none", null));
        addStage(shortSla, stage(12, null, null, null), 201);
        activate(shortSla);
        UUID skipped = taskId(submit(w.chamath(), invoiceDoc, 201), 1, w.nimal().getDisplayName());
        fx.jdbc().update("update workflow_tasks set due_at = now() + interval '11 hours' where task_id = ?", skipped);
        for (int i = 0; i < 5; i++) {
            slaService.runOnce();
        }
        assertThat(stamped(skipped, "reminder_sent_at")).isZero();
    }
}
