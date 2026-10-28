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
 * The reminder and escalation settings on the template and stage APIs (3.2, N-FR-006): the template's two
 * inactivity defaults and the stage's overrides (null = inherit) are saved and read back, validated, copied to a
 * new version - and actually take effect. The due-date reminder and the escalation target are the stage's own.
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

    private Map<String, Object> withDefaults(Map<String, Object> body, Integer inactivity, Integer escalateAfter) {
        body.put("defaultInactivityReminderHours", inactivity);
        body.put("defaultEscalateAfterReminderHours", escalateAfter);
        return body;
    }

    private Map<String, Object> updateBody(String name, Integer inactivity, Integer escalateAfter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("documentTypeId", w.contractTypeId());
        body.put("onDocumentChange", "flag_only");
        return withDefaults(body, inactivity, escalateAfter);
    }

    /** Escalate-after needs an escalation target, so a stage that sets one gets the department head. */
    private Map<String, Object> stage(Integer sla, Integer reminderBefore, Integer inactivity, Integer escalateAfter) {
        Map<String, Object> s = stageDef("Review", "any", "halt_immediately", sla, entry("user", w.nimal().getUserId()));
        if (escalateAfter != null) {
            s.put("escalationTarget", "department_head");
        }
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
        JsonNode created = adminPost(TEMPLATES, w.admin(), withDefaults(templateBody("DEF"), 48, 12), 201);
        UUID id = id(created, "templateId");
        for (JsonNode t : new JsonNode[]{created, read(id)}) {
            assertThat(t.get("defaultInactivityReminderHours").asInt()).isEqualTo(48);
            assertThat(t.get("defaultEscalateAfterReminderHours").asInt()).isEqualTo(12);
        }

        // Replaced as a whole.
        JsonNode replaced = adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("DEF approval", 72, 36), 200);
        assertThat(replaced.get("defaultInactivityReminderHours").asInt()).isEqualTo(72);
        assertThat(replaced.get("defaultEscalateAfterReminderHours").asInt()).isEqualTo(36);

        // Leaving them out clears them.
        JsonNode cleared = adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("DEF approval", null, null), 200);
        assertThat(cleared.get("defaultInactivityReminderHours").isNull()).isTrue();
        assertThat(cleared.get("defaultEscalateAfterReminderHours").isNull()).isTrue();
        assertThat(read(id).get("defaultInactivityReminderHours").isNull()).isTrue();
    }

    @Test
    void theRemovedTemplateDefaults_areNoLongerReturned() throws Exception {
        JsonNode t = read(create(templateBody("GONE")));
        for (String removed : new String[]{"defaultReminderBeforeHours", "defaultEscalationTarget",
                "defaultEscalationUserId"}) {
            assertThat(t.has(removed)).as(removed).isFalse();
        }
    }

    @Test
    void aTemplateCreatedWithoutThem_hasNoDefaults() throws Exception {
        UUID id = create(templateBody("PLAIN"));
        JsonNode t = read(id);
        assertThat(t.get("defaultInactivityReminderHours").isNull()).isTrue();
        assertThat(t.get("defaultEscalateAfterReminderHours").isNull()).isTrue();
    }

    @Test
    void stageOverrides_areSavedAndReadBack_nullMeansInherit() throws Exception {
        UUID id = create(withDefaults(templateBody("OVR"), 48, 24));

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
        for (String field : new String[]{"defaultInactivityReminderHours", "defaultEscalateAfterReminderHours"}) {
            Map<String, Object> body = templateBody("NEG-" + field.length());
            body.put(field, 0);
            adminPost(TEMPLATES, w.admin(), body, 400);
            body.put(field, -5);
            adminPost(TEMPLATES, w.admin(), body, 400);
        }
        UUID id = create(templateBody("POS"));
        adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", 0, null), 400);
        addStage(id, stage(null, null, 0, null), 400);
        addStage(id, stage(null, null, 5, 0), 400);
    }

    @Test
    void templateEscalateAfter_needsAnInactivityReminder() throws Exception {
        assertThat(code(adminPost(TEMPLATES, w.admin(), withDefaults(templateBody("EA1"), null, 24), 422)))
                .isEqualTo("WORKFLOW_INVALID_TEMPLATE_DEFAULTS");
        UUID id = create(templateBody("EA2"));
        assertThat(code(adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", null, 24), 422)))
                .isEqualTo("WORKFLOW_INVALID_TEMPLATE_DEFAULTS");
        adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", 48, 24), 200);
    }

    @Test
    void stageEscalateAfter_needsAnInactivityReminderItOwnsOrInherits() throws Exception {
        UUID noDefault = create(templateBody("SE1"));
        assertThat(code(addStage(noDefault, stage(null, null, null, 6), 422))).isEqualTo("WORKFLOW_INVALID_STAGE");
        addStage(noDefault, stage(null, null, 12, 6), 201);          // its own inactivity reminder

        UUID withDefault = create(withDefaults(templateBody("SE2"), 48, null));
        addStage(withDefault, stage(null, null, null, 6), 201);      // inherits the template's
    }

    @Test
    void stageEscalateAfter_needsAnEscalationTargetOnTheStage() throws Exception {
        UUID id = create(withDefaults(templateBody("SE3"), 48, 24));   // the template has no target to lend
        Map<String, Object> noTarget = stage(null, null, 12, 6);
        noTarget.put("escalationTarget", "none");
        assertThat(code(addStage(id, noTarget, 422))).isEqualTo("WORKFLOW_INVALID_STAGE");
        noTarget.remove("escalationTarget");
        assertThat(code(addStage(id, noTarget, 422))).isEqualTo("WORKFLOW_INVALID_STAGE");
        addStage(id, stage(null, null, 12, 6), 201);                 // department head
    }

    @Test
    void theTemplateInactivityDefault_cannotBeRemovedFromUnderAStageThatInheritsIt() throws Exception {
        UUID id = create(withDefaults(templateBody("RM"), 48, null));
        JsonNode added = addStage(id, stage(null, null, null, 6), 201);   // escalate-after, inactivity inherited

        assertThat(code(adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", null, null), 422)))
                .isEqualTo("WORKFLOW_INVALID_TEMPLATE_DEFAULTS");
        assertThat(read(id).get("defaultInactivityReminderHours").asInt()).isEqualTo(48);   // unchanged

        // Give the stage its own inactivity reminder and the default can go.
        adminPut(TEMPLATES + "/" + id + "/stages/" + id(added.get("stages").get(0), "stageId"), w.admin(),
                stage(null, null, 12, 6), 200);
        adminPut(TEMPLATES + "/" + id, w.admin(), updateBody("x", null, null), 200);
    }

    // ------------------------------------------------------------------ versions

    @Test
    void aNewVersion_copiesTheDefaultsAndTheStageOverrides() throws Exception {
        UUID id = create(withDefaults(templateBody("VER"), 48, 12));
        addStage(id, stage(72, 24, 36, 18), 201);
        activate(id);

        JsonNode v2 = adminPost(TEMPLATES + "/" + id + "/versions", w.admin(), null, 201);
        assertThat(v2.get("versionNumber").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asString()).isEqualTo("draft");
        assertThat(v2.get("defaultInactivityReminderHours").asInt()).isEqualTo(48);
        assertThat(v2.get("defaultEscalateAfterReminderHours").asInt()).isEqualTo(12);
        JsonNode s = v2.get("stages").get(0);
        assertThat(s.get("reminderBeforeHours").asInt()).isEqualTo(24);
        assertThat(s.get("inactivityReminderHours").asInt()).isEqualTo(36);
        assertThat(s.get("escalateAfterReminderHours").asInt()).isEqualTo(18);
    }

    // ------------------------------------------------------------------ end to end: the defaults take effect

    @Test
    void aStageWithOnlyTheTemplateDefaults_getsAnInactivityReminder() throws Exception {
        UUID id = create(withDefaults(templateBody("E2E"), 48, null));
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
    void aStageWithItsOwnTarget_escalatesAfterTheInactivityReminder_usingTheTemplateEscalateAfter() throws Exception {
        UUID id = create(withDefaults(templateBody("ESC"), 48, 24));
        Map<String, Object> own = stage(null, null, null, null);      // escalate-after inherited: 24 h
        own.put("escalationTarget", "named_user");
        own.put("escalationUserId", manager.getUserId());
        addStage(id, own, 201);
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
    void aStageEscalateAfterOfItsOwn_winsOverTheTemplateDefault() throws Exception {
        UUID id = create(withDefaults(templateBody("OWN"), 48, 24));
        Map<String, Object> own = stage(null, null, null, 6);         // 6 h, not the template's 24 h
        own.put("escalationTarget", "named_user");
        own.put("escalationUserId", manager.getUserId());
        addStage(id, own, 201);
        activate(id);
        UUID task = submittedTask(w.contractDocumentId());

        fx.jdbc().update("update workflow_tasks set inactivity_reminder_sent_at = now() - interval '7 hours'"
                + " where task_id = ?", task);
        runSlaUntil(task, "escalated_at");

        assertThat(fx.count("select count(*) from workflow_tasks where task_id = ? and escalated_to = ?", task,
                manager.getUserId())).isEqualTo(1);
    }

    @Test
    void aStageAtNone_neverEscalates_evenWithATemplateEscalateAfter() throws Exception {
        UUID id = create(withDefaults(templateBody("NONE"), 48, 24));
        addStage(id, stage(null, null, null, null), 201);             // no target, escalate-after only inherited
        activate(id);
        UUID task = submittedTask(w.contractDocumentId());

        fx.jdbc().update("update workflow_tasks set inactivity_reminder_sent_at = now() - interval '25 hours'"
                + " where task_id = ?", task);
        for (int i = 0; i < 5; i++) {
            slaService.runOnce();
        }
        assertThat(stamped(task, "escalated_at")).isZero();
    }
}
