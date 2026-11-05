package com.oak.dms.compliance;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 6.1 follow-ups: retention rules also limit a document's OLD VERSIONS (removed = hidden first, destroyed only after a grace
 * period, restorable until then), and the people concerned are notified of what a retention run did.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class VersionRetentionAndNotificationsIT extends WorkflowApiSupport {

    private static final String VIEW = "docm.documents.view";
    private static final String POLICY_VIEW = "crm.manage-retention-policies.view";
    private static final String POLICY_CREATE = "crm.manage-retention-policies.create";
    private static final String POLICY_UPDATE = "crm.manage-retention-policies.update";
    private static final String REVIEW = "crm.review-retention";
    private static final String CONFIGURE = "crm.configure-retention";
    private static final String PLACE_HOLD = "crm.place-legal-hold";
    private static final String MAX_VERSIONS = "dvc.configure-version-retention-rules.set-max-versions";
    private static final String VERSION_PERIOD = "dvc.configure-version-retention-rules.set-retention-period";

    private UUID tenantId;
    private UUID type;
    private User owner;
    private User admin;
    private UUID documentId;
    /** version_id of versions 1..5 (index 0 = version 1). */
    private final UUID[] version = new UUID[5];

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Contract");
        owner = fx.user(tenantId, "Olive");
        admin = fx.user(tenantId, "Adam");
        fx.role(tenantId, "Readers", new String[]{VIEW, "dvc.view-version-history.view"}, owner);
        fx.role(tenantId, "Retention admins", new String[]{VIEW, POLICY_VIEW, POLICY_CREATE, POLICY_UPDATE, REVIEW, CONFIGURE,
                PLACE_HOLD, MAX_VERSIONS, VERSION_PERIOD}, admin);
        documentId = fx.document(tenantId, type, owner, "Master agreement");
        version[0] = latestVersion();
        for (int i = 1; i < 5; i++) {
            version[i] = fx.newVersion(tenantId, documentId, owner);
        }
        for (UUID v : version) {
            fx.putVersionContent(v, "content".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ rules that limit old versions

    @Test
    void aMaximumCount_removesTheOldestVersions_butNeverTheCurrentOnes() throws Exception {
        rule("Keep two", 2, null, null);

        JsonNode result = runNow();
        assertThat(result.get("versionsRemoved").asInt()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(removed(version[i])).as("version %d is removed", i + 1).isTrue();
        }
        assertThat(removed(version[3])).isFalse();
        assertThat(removed(version[4])).isFalse(); // the current one
        // Removed = hidden, not destroyed: the file and the row are still there.
        assertThat(destroyed(version[0])).isFalse();
        assertThat(audit("DOCUMENT_VERSIONS_REMOVED")).isEqualTo(1);

        // Hidden for real: gone from the version history, and its content can no longer be fetched by id.
        JsonNode history = call(get("/api/v1/documents/" + documentId + "/versions"), fx.jwtFor(owner), null, 200);
        assertThat(history.toString()).doesNotContain(version[0].toString()).doesNotContain(version[2].toString())
                .contains(version[3].toString()).contains(version[4].toString());
        call(get("/api/v1/documents/" + documentId + "/versions/" + version[0] + "/content"), fx.jwtFor(owner), null, 404);

        // Running again changes nothing.
        assertThat(runNow().get("versionsRemoved").asInt()).isZero();
        assertThat(audit("DOCUMENT_VERSIONS_REMOVED")).isEqualTo(1);
    }

    @Test
    void aMaximumAge_removesOnlyVersionsOlderThanThat() throws Exception {
        for (int i = 0; i < 3; i++) {
            fx.jdbc().update("update document_versions set created_at = now() - interval '400 days' where version_id = ?", version[i]);
        }
        rule("A year", null, 1, "years");

        assertThat(runNow().get("versionsRemoved").asInt()).isEqualTo(3);
        assertThat(removed(version[3])).isFalse();
        assertThat(removed(version[4])).isFalse();
    }

    @Test
    void aRuleWithNoVersionLimits_removesNothing() throws Exception {
        rule("No limits", null, null, null);
        assertThat(runNow().get("versionsRemoved").asInt()).isZero();
        assertThat(fx.count("select count(*) from document_versions where document_id = ? and retention_removed_at is not null", documentId)).isZero();
    }

    @Test
    void aProtectedVersion_aLinkedVersion_andAnAdministratorsKeep_areNeverRemoved() throws Exception {
        fx.jdbc().update("update document_versions set is_protected = true where version_id = ?", version[0]);
        fx.jdbc().update("update document_versions set retention_keep = true where version_id = ?", version[1]);
        UUID other = fx.document(tenantId, type, owner, "Linked memo");
        UUID relType = UUID.randomUUID();
        fx.jdbc().update("insert into relationship_types (relationship_type_id, tenant_id, code, category, label, inverse_label, "
                + "directionality) values (?, ?, 'REFERENCES', 'reference', 'References', 'Referenced by', 'directional')", relType, tenantId);
        fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id, target_document_id, "
                + "target_version_id) values (?, ?, ?, ?, ?)", tenantId, relType, other, documentId, version[2]);
        rule("Keep one", 1, null, null);

        runNow();
        // 1 = protected, 2 = kept by an administrator, 3 = linked from another document, 5 = current: all stay. Only 4 goes.
        assertThat(removed(version[0])).isFalse();
        assertThat(removed(version[1])).isFalse();
        assertThat(removed(version[2])).isFalse();
        assertThat(removed(version[3])).isTrue();
        assertThat(removed(version[4])).isFalse();
    }

    @Test
    void aDocumentUnderLegalHold_keepsEveryVersion() throws Exception {
        rule("Keep one", 1, null, null);
        call(post("/api/v1/documents/" + documentId + "/legal-hold"), fx.jwtFor(admin), Map.of("reason", "Investigation"), 200);

        assertThat(runNow().get("versionsRemoved").asInt()).isZero();
        assertThat(fx.count("select count(*) from document_versions where document_id = ? and is_deleted", documentId)).isZero();
    }

    @Test
    void settingTheLimits_needsTheDvcPermissions_onTopOfTheRulePermission() throws Exception {
        User ruleOnly = fx.user(tenantId, "Rhea");
        fx.role(tenantId, "Rule editors", new String[]{VIEW, POLICY_VIEW, POLICY_CREATE, POLICY_UPDATE}, ruleOnly);

        // A rule with no version limits is fine without them...
        call(post("/api/v1/retention/policies"), fx.jwtFor(ruleOnly), body("Plain", null, null, null), 200);
        // ...but the maximum and the period each need their own permission.
        Map<String, Object> withMax = rule("Max", "document_type", type, 100, "years", 0, "years");
        withMax.put("maxVersions", 3);
        call(post("/api/v1/retention/policies"), fx.jwtFor(ruleOnly), withMax, 403);
        Map<String, Object> withAge = rule("Age", "document_type", type, 100, "years", 0, "years");
        withAge.put("versionAgeAmount", 2);
        withAge.put("versionAgeUnit", "years");
        call(post("/api/v1/retention/policies"), fx.jwtFor(ruleOnly), withAge, 403);
        assertThat(fx.count("select count(*) from retention_policies where tenant_id = ? and not is_deleted", tenantId)).isEqualTo(1);

        // Someone holding them can.
        JsonNode ok = call(post("/api/v1/retention/policies"), fx.jwtFor(admin), withMax, 200);
        assertThat(ok.get("maxVersions").asInt()).isEqualTo(3);
        // And changing an existing limit needs them too - while editing the rest of the rule does not.
        UUID id = UUID.fromString(ok.get("policyId").asString());
        Map<String, Object> renamed = rule("Max renamed", "document_type", type, 100, "years", 0, "years");
        renamed.put("maxVersions", 3);
        call(put("/api/v1/retention/policies/" + id), fx.jwtFor(ruleOnly), renamed, 200);
        renamed.put("maxVersions", 9);
        call(put("/api/v1/retention/policies/" + id), fx.jwtFor(ruleOnly), renamed, 403);
    }

    @Test
    void theLimitsAreValidated_andCanBeCleared() throws Exception {
        assertThat(call(post("/api/v1/retention/policies"), fx.jwtFor(admin), body("Zero", 0, null, null), 400)
                .get("code").asString()).isEqualTo("RETENTION_MAX_VERSIONS_INVALID");
        assertThat(call(post("/api/v1/retention/policies"), fx.jwtFor(admin), body("Age", null, 0, "years"), 400)
                .get("code").asString()).isEqualTo("RETENTION_VERSION_AGE_INVALID");
        assertThat(call(post("/api/v1/retention/policies"), fx.jwtFor(admin), body("Unit", null, 2, "fortnights"), 400)
                .get("code").asString()).isEqualTo("RETENTION_VERSION_AGE_INVALID");

        JsonNode created = call(post("/api/v1/retention/policies"), fx.jwtFor(admin), body("Both", 5, 2, "years"), 200);
        assertThat(created.get("maxVersions").asInt()).isEqualTo(5);
        assertThat(created.get("versionAgeAmount").asInt()).isEqualTo(2);
        JsonNode cleared = call(put("/api/v1/retention/policies/" + created.get("policyId").asString()), fx.jwtFor(admin),
                body("Both", null, null, null), 200);
        assertThat(cleared.get("maxVersions").isNull()).isTrue();
        assertThat(cleared.get("versionAgeAmount").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ grace period, destruction, restore

    @Test
    void aRemovedVersionIsDestroyedOnlyAfterTheGracePeriod() throws Exception {
        rule("Keep two", 2, null, null);
        runNow();
        saveSettings(90);
        age("40 days");

        assertThat(runNow().get("versionsDestroyed").asInt()).isZero(); // 40 days < 90: still restorable
        assertThat(destroyed(version[0])).isFalse();

        saveSettings(30);
        JsonNode result = runNow();
        assertThat(result.get("versionsDestroyed").asInt()).isEqualTo(3); // 40 days > 30
        for (int i = 0; i < 3; i++) {
            assertThat(destroyed(version[i])).as("version %d destroyed", i + 1).isTrue();
        }
        assertThat(destroyed(version[3])).isFalse();
        assertThat(audit("DOCUMENT_VERSIONS_DESTROYED")).isEqualTo(1);
    }

    @Test
    void aProtectedVersion_isNeverDestroyed_evenAfterTheGracePeriod() throws Exception {
        rule("Keep two", 2, null, null);
        runNow();
        age("400 days");
        fx.jdbc().update("update document_versions set is_protected = true where version_id = ?", version[0]);

        runNow();
        assertThat(destroyed(version[0])).isFalse(); // protected after it was removed: still safe
        assertThat(destroyed(version[1])).isTrue();
        assertThat(destroyed(version[2])).isTrue();
    }

    @Test
    void aDocumentUnderLegalHold_keepsItsRemovedVersions_untilTheHoldIsLifted() throws Exception {
        rule("Keep two", 2, null, null);
        runNow();
        age("400 days");
        call(post("/api/v1/documents/" + documentId + "/legal-hold"), fx.jwtFor(admin), Map.of("reason", "Investigation"), 200);

        assertThat(runNow().get("versionsDestroyed").asInt()).isZero();
        assertThat(destroyed(version[0])).isFalse();
        assertThat(destroyed(version[1])).isFalse();
    }

    @Test
    void anAdministratorCanRestoreARemovedVersion_whichThenStaysForGood() throws Exception {
        rule("Keep two", 2, null, null);
        runNow();

        JsonNode list = call(get("/api/v1/retention/versions"), fx.jwtFor(admin), null, 200).get("content");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("documentTitle").asString()).isEqualTo("Master agreement");
        assertThat(list.get(0).get("destroysAt").isNull()).isFalse();
        call(get("/api/v1/retention/versions"), fx.jwtFor(owner), null, 403);
        call(post("/api/v1/retention/versions/" + version[0] + "/restore"), fx.jwtFor(owner), null, 403);

        call(post("/api/v1/retention/versions/" + version[0] + "/restore"), fx.jwtFor(admin), null, 200);
        assertThat(removed(version[0])).isFalse();
        assertThat(audit("RETENTION_VERSION_RESTORED")).isEqualTo(1);

        // The next run does not remove it again - restoring exempts it.
        assertThat(runNow().get("versionsRemoved").asInt()).isZero();
        assertThat(removed(version[0])).isFalse();
        assertThat(call(get("/api/v1/retention/versions"), fx.jwtFor(admin), null, 200).get("content")).hasSize(2);
    }

    @Test
    void aVersionWhoseFilesAreDestroyed_cannotBeRestored() throws Exception {
        rule("Keep two", 2, null, null);
        runNow();
        age("400 days");
        runNow(); // destroyed

        assertThat(call(post("/api/v1/retention/versions/" + version[0] + "/restore"), fx.jwtFor(admin), null, 409)
                .get("code").asString()).isEqualTo("VERSION_NOT_RESTORABLE");
    }

    @Test
    void theGracePeriodIsASetting_withinLimits() throws Exception {
        assertThat(get200("/api/v1/retention/settings", admin).get("versionGraceDays").asInt()).isEqualTo(30);
        saveSettings(7);
        assertThat(get200("/api/v1/retention/settings", admin).get("versionGraceDays").asInt()).isEqualTo(7);
        call(put("/api/v1/retention/settings"), fx.jwtFor(admin), settings(-1), 400);
        call(put("/api/v1/retention/settings"), fx.jwtFor(admin), settings(4000), 400);
    }

    // ------------------------------------------------------------------ notifications

    @Test
    void whenDocumentsReachTheEndOfTheirRetention_reviewersAreTold_once() throws Exception {
        rule("Short", null, null, null, 1, "days", 1, "days");
        fx.jdbc().update("update documents set created_at = now() - interval '5 days', updated_at = now() - interval '5 days' "
                + "where document_id = ?", documentId);

        JsonNode result = runNow();
        assertThat(result.get("flagged").asInt()).isEqualTo(1);
        assertThat(notifications(admin, "retention.review.due")).isEqualTo(1);
        assertThat(notifications(owner, "retention.review.due")).isZero(); // owners are not reviewers

        runNow(); // already flagged: no repeat
        assertThat(notifications(admin, "retention.review.due")).isEqualTo(1);
    }

    @Test
    void anOwnerIsToldTheirDocumentWasArchived_andReviewersAreNotBotheredForIt() throws Exception {
        rule("Keep a bit", null, null, null, 1, "days", 30, "days");
        fx.jdbc().update("update documents set created_at = now() - interval '5 days', updated_at = now() - interval '5 days' "
                + "where document_id = ?", documentId);

        runNow();
        assertThat(notifications(owner, "retention.archived.owner")).isEqualTo(1);
        assertThat(notifications(admin, "retention.review.due")).isZero();
        assertThat(fx.jdbc().queryForObject("select title from notifications where recipient_user_id = ? and category_code = "
                + "'retention.archived.owner'", String.class, owner.getUserId())).contains("Master agreement");
    }

    @Test
    void aDocumentNoRuleCovers_tellsTheReviewers() throws Exception {
        UUID other = fx.documentType(tenantId, "Memo");
        call(post("/api/v1/retention/policies"), fx.jwtFor(admin),
                rule("Memos only", "document_type", other, 1, "years", 1, "years"), 200);

        runNow();
        assertThat(notifications(admin, "retention.no-rule")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private UUID latestVersion() {
        return fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?", UUID.class, documentId);
    }

    private JsonNode runNow() throws Exception {
        return call(post("/api/v1/retention/run"), fx.jwtFor(admin), null, 200);
    }

    private void saveSettings(int graceDays) throws Exception {
        call(put("/api/v1/retention/settings"), fx.jwtFor(admin), settings(graceDays), 200);
    }

    private static Map<String, Object> settings(int graceDays) {
        return Map.of("automationEnabled", true, "precedence", "longest", "whenNoPolicy", "flag", "requireStatusReason", false,
                "versionGraceDays", graceDays);
    }

    /** An organisation-wide rule: 100 years active (so no archiving) with the given version limits. */
    private void rule(String name, Integer maxVersions, Integer ageAmount, String ageUnit) throws Exception {
        call(post("/api/v1/retention/policies"), fx.jwtFor(admin), body(name, maxVersions, ageAmount, ageUnit), 200);
    }

    private void rule(String name, Integer maxVersions, Integer ageAmount, String ageUnit, int active, String activeUnit,
                      int archive, String archiveUnit) throws Exception {
        Map<String, Object> m = rule(name, "organisation", null, active, activeUnit, archive, archiveUnit);
        call(post("/api/v1/retention/policies"), fx.jwtFor(admin), m, 200);
    }

    private static Map<String, Object> body(String name, Integer maxVersions, Integer ageAmount, String ageUnit) {
        Map<String, Object> m = rule(name, "organisation", null, 100, "years", 0, "years");
        m.put("maxVersions", maxVersions);
        m.put("versionAgeAmount", ageAmount);
        m.put("versionAgeUnit", ageUnit);
        return m;
    }

    private static Map<String, Object> rule(String name, String scope, UUID scopeId, int active, String activeUnit, int archive,
                                            String archiveUnit) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("scopeType", scope);
        m.put("scopeId", scopeId);
        m.put("activeAmount", active);
        m.put("activeUnit", activeUnit);
        m.put("archiveAmount", archive);
        m.put("archiveUnit", archiveUnit);
        m.put("clockStart", "created");
        return m;
    }

    private boolean removed(UUID versionId) {
        return Boolean.TRUE.equals(fx.jdbc().queryForObject("select retention_removed_at is not null from document_versions "
                + "where version_id = ?", Boolean.class, versionId));
    }

    private boolean destroyed(UUID versionId) {
        return Boolean.TRUE.equals(fx.jdbc().queryForObject("select purged_at is not null from document_versions where version_id = ?",
                Boolean.class, versionId));
    }

    /** Pretend the retention removals happened this long ago. */
    private void age(String interval) {
        fx.jdbc().update("update document_versions set retention_removed_at = now() - interval '" + interval + "' "
                + "where document_id = ? and retention_removed_at is not null", documentId);
    }

    private long notifications(User recipient, String category) {
        return fx.count("select count(*) from notifications where tenant_id = ? and recipient_user_id = ? and category_code = ?",
                tenantId, recipient.getUserId(), category);
    }

    private long audit(String action) {
        return fx.count("select count(*) from audit_events where tenant_id = ? and action = ? and object_id = ?",
                tenantId, action, documentId);
    }
}
