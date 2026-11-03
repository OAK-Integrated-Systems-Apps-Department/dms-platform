package com.oak.dms.compliance;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 6.1 Records Retention & Compliance Management, end to end over HTTP and the real database: manual Active/Inactive
 * (CRM-FR-001..005), the rules an administrator defines (006, 008, 010, 011), the automatic archive and review flag
 * (007, 012), and legal hold's hold over all of it (013..017), with every step audited (018).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RecordsRetentionIT extends WorkflowApiSupport {

    private static final String VIEW = "docm.documents.view";
    private static final String MARK_ACTIVE = "crm.change-document-status.mark-active";
    private static final String MARK_INACTIVE = "crm.change-document-status.mark-inactive";
    private static final String VIEW_ARCHIVE = "crm.view-archive";
    private static final String POLICY_VIEW = "crm.manage-retention-policies.view";
    private static final String POLICY_CREATE = "crm.manage-retention-policies.create";
    private static final String POLICY_UPDATE = "crm.manage-retention-policies.update";
    private static final String POLICY_DELETE = "crm.manage-retention-policies.delete";
    private static final String REVIEW = "crm.review-retention";
    private static final String CONFIGURE = "crm.configure-retention";
    private static final String PLACE_HOLD = "crm.place-legal-hold";
    private static final String LIFT_HOLD = "crm.lift-legal-hold";
    private static final String DELETE = "docm.documents.delete";

    @Autowired
    RetentionJob job;

    private UUID tenantId;
    private UUID type;
    private User owner;      // ordinary user: can see documents
    private User records;    // records manager: marks documents, sees the archive
    private User admin;      // administrator: rules, settings, review, legal hold
    private UUID documentId;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Invoice");
        owner = fx.user(tenantId, "Olive");
        records = fx.user(tenantId, "Rita");
        admin = fx.user(tenantId, "Adam");
        fx.role(tenantId, "Readers", new String[]{VIEW}, owner);
        fx.role(tenantId, "Records managers", new String[]{VIEW, MARK_ACTIVE, MARK_INACTIVE, VIEW_ARCHIVE}, records);
        fx.role(tenantId, "Retention admins", new String[]{VIEW, MARK_ACTIVE, MARK_INACTIVE, VIEW_ARCHIVE, POLICY_VIEW,
                POLICY_CREATE, POLICY_UPDATE, POLICY_DELETE, REVIEW, CONFIGURE, PLACE_HOLD, LIFT_HOLD, DELETE}, admin);
        documentId = fx.document(tenantId, type, owner, "Supplier invoice 1041");
    }

    // ------------------------------------------------------------------ status indicator and manual switch

    @Test
    void everyDocumentShowsAStatus_activeUntilSomeoneChangesIt() throws Exception {
        JsonNode row = listed(owner, "").get(0);
        assertThat(row.get("lifecycleState").asString()).isEqualTo("active");
        assertThat(get200("/api/v1/documents/" + documentId, owner).get("lifecycleState").asString()).isEqualTo("active");
    }

    @Test
    void markingInactive_hidesItFromEveryNormalListing_andTheArchiveViewShowsIt() throws Exception {
        status(records, "inactive", "Invoice paid", 200);

        assertThat(titles(listed(owner, ""))).doesNotContain("Supplier invoice 1041");
        assertThat(titles(listed(records, ""))).doesNotContain("Supplier invoice 1041");
        assertThat(titles(listedArchive(records))).contains("Supplier invoice 1041");
        assertThat(listedArchive(records).get(0).get("lifecycleState").asString()).isEqualTo("inactive");
        // Searching does not bring it back either.
        assertThat(titles(listed(owner, "q=1041"))).isEmpty();
        // It is still reachable by its link for people who can open it.
        assertThat(get200("/api/v1/documents/" + documentId, owner).get("lifecycleState").asString()).isEqualTo("inactive");
        assertThat(audit("DOCUMENT_STATUS_CHANGED")).isEqualTo(1);
    }

    @Test
    void markingActiveAgain_bringsItBack_andIsAudited() throws Exception {
        status(records, "inactive", null, 200);
        status(records, "active", "Supplier dispute", 200);

        assertThat(titles(listed(owner, ""))).contains("Supplier invoice 1041");
        assertThat(titles(listedArchive(records))).doesNotContain("Supplier invoice 1041");
        assertThat(audit("DOCUMENT_STATUS_CHANGED")).isEqualTo(2);
    }

    @Test
    void theArchiveViewIsOnlyForPeopleAllowedToSeeIt() throws Exception {
        call(get("/api/v1/documents?view=archive&all=true"), fx.jwtFor(owner), null, 403);
        call(get("/api/v1/documents?view=archive&all=true"), fx.jwtFor(records), null, 200);
    }

    @Test
    void eachDirectionNeedsItsOwnPermission_andAnUnauthorisedUserIsDenied() throws Exception {
        status(owner, "inactive", null, 403);
        User onlyActivate = fx.user(tenantId, "Ola");
        fx.role(tenantId, "Only activate", new String[]{VIEW, MARK_ACTIVE}, onlyActivate);
        status(onlyActivate, "inactive", null, 403);
        assertThat(lifecycleState()).isEqualTo("active");
    }

    @Test
    void aStatusOtherThanActiveOrInactive_isRefused_andSwitchingToTheSameStateSaysSo() throws Exception {
        assertThat(status(records, "archived", null, 400).get("code").asString()).isEqualTo("STATUS_INVALID");
        assertThat(status(records, "active", null, 409).get("code").asString()).isEqualTo("DOCUMENT_STATUS_UNCHANGED");
    }

    @Test
    void anOrganisationCanRequireAReasonForEveryStatusChange() throws Exception {
        saveSettings(true, "longest", "flag", true);
        assertThat(status(records, "inactive", " ", 400).get("code").asString()).isEqualTo("STATUS_REASON_REQUIRED");
        status(records, "inactive", "Paid and filed", 200);
        assertThat(lifecycleReason()).isEqualTo("Paid and filed");
    }

    // ------------------------------------------------------------------ legal hold (013 .. 017)

    @Test
    void aDocumentUnderLegalHold_cannotBeSwitchedEitherWay_andTheMessageNamesTheHold() throws Exception {
        placeHold("Supplier litigation 2026");
        JsonNode refused = status(records, "inactive", null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_ON_LEGAL_HOLD");
        assertThat(refused.get("message").asString()).contains("legal hold").contains("Supplier litigation 2026");
        assertThat(lifecycleState()).isEqualTo("active");

        // Even a document already inactive stays exactly as it is.
        liftHold();
        status(records, "inactive", null, 200);
        placeHold("Again");
        assertThat(status(records, "active", null, 409).get("code").asString()).isEqualTo("DOCUMENT_ON_LEGAL_HOLD");
        assertThat(lifecycleState()).isEqualTo("inactive");
    }

    @Test
    void aDocumentUnderLegalHold_cannotBeDeleted_byAnyoneIncludingAnAdministrator() throws Exception {
        placeHold("Investigation");
        User deleter = fx.user(tenantId, "Dee");
        fx.role(tenantId, "Deleters", new String[]{VIEW, DELETE}, deleter);
        // The hold refuses the delete for everyone, an administrator with every CRM permission included.
        call(delete("/api/v1/documents/" + documentId), fx.jwtFor(admin), null, 409);
        call(delete("/api/v1/documents/" + documentId), fx.jwtFor(deleter), null, 409);
        assertThat(fx.count("select count(*) from documents where document_id = ? and not is_deleted", documentId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the rules (006, 008, 010, 011)

    @Test
    void anAdministratorDefinesRulesForEachScope_andTheyRunSideBySide() throws Exception {
        UUID dept = fx.department(tenantId, "Finance").getDepartmentId();
        JsonNode byType = createPolicy(admin, rule("Invoices", "document_type", type, 1, "years", 6, "years", "HMRC"), 200);
        createPolicy(admin, rule("Finance dept", "department", dept, 2, "years", 5, "years", null), 200);
        createPolicy(admin, rule("Default", "organisation", null, 3, "months", 0, "days", "Company policy"), 200);

        JsonNode all = get200("/api/v1/retention/policies", admin);
        assertThat(all).hasSize(3);
        assertThat(byType.get("scopeName").asString()).isEqualTo("Invoice");
        assertThat(byType.get("framework").asString()).isEqualTo("HMRC");
        assertThat(audit("RETENTION_POLICY_CREATED", null)).isEqualTo(3);
    }

    @Test
    void oneRulePerScope_andBadValuesAreRefusedWithAReason() throws Exception {
        createPolicy(admin, rule("Invoices", "document_type", type, 1, "years", 1, "years", null), 200);
        assertThat(createPolicy(admin, rule("Again", "document_type", type, 2, "years", 1, "years", null), 409)
                .get("code").asString()).isEqualTo("RETENTION_SCOPE_TAKEN");
        assertThat(createPolicy(admin, rule("Zero", "organisation", null, 0, "years", 1, "years", null), 400)
                .get("code").asString()).isEqualTo("RETENTION_ACTIVE_PERIOD_INVALID");
        assertThat(createPolicy(admin, rule("Unit", "organisation", null, 1, "fortnights", 1, "years", null), 400)
                .get("code").asString()).isEqualTo("RETENTION_ACTIVE_PERIOD_INVALID");
        assertThat(createPolicy(admin, rule("Neg", "organisation", null, 1, "years", -1, "years", null), 400)
                .get("code").asString()).isEqualTo("RETENTION_ARCHIVE_PERIOD_INVALID");
        assertThat(createPolicy(admin, rule("Scope", "galaxy", null, 1, "years", 1, "years", null), 400)
                .get("code").asString()).isEqualTo("RETENTION_SCOPE_INVALID");
        assertThat(createPolicy(admin, rule("No target", "folder", null, 1, "years", 1, "years", null), 400)
                .get("code").asString()).isEqualTo("RETENTION_SCOPE_REQUIRED");
        call(post("/api/v1/retention/policies"), fx.jwtFor(admin),
                rule("Ghost", "document_type", UUID.randomUUID(), 1, "years", 1, "years", null), 404);
    }

    @Test
    void rulesNeedTheirPermissions_andAreChangeableAndDeletable() throws Exception {
        createPolicy(owner, rule("Nope", "organisation", null, 1, "years", 1, "years", null), 403);
        call(get("/api/v1/retention/policies"), fx.jwtFor(owner), null, 403);

        JsonNode created = createPolicy(admin, rule("Default", "organisation", null, 1, "years", 1, "years", null), 200);
        UUID id = UUID.fromString(created.get("policyId").asString());
        JsonNode changed = call(put("/api/v1/retention/policies/" + id), fx.jwtFor(admin),
                rule("Default", "organisation", null, 7, "years", 3, "years", "Companies Act 2006"), 200);
        assertThat(changed.get("activeAmount").asInt()).isEqualTo(7);
        assertThat(changed.get("framework").asString()).isEqualTo("Companies Act 2006");
        call(delete("/api/v1/retention/policies/" + id), fx.jwtFor(admin), null, 200);
        assertThat(get200("/api/v1/retention/policies", admin)).isEmpty();
        assertThat(audit("RETENTION_POLICY_UPDATED", null)).isEqualTo(1);
        assertThat(audit("RETENTION_POLICY_DELETED", null)).isEqualTo(1);
    }

    @Test
    void theSettingsHaveDefaults_andAreChangeable_withTheirOwnPermission() throws Exception {
        JsonNode defaults = get200("/api/v1/retention/settings", admin);
        assertThat(defaults.get("automationEnabled").asBoolean()).isTrue();
        assertThat(defaults.get("precedence").asString()).isEqualTo("longest");
        assertThat(defaults.get("whenNoPolicy").asString()).isEqualTo("flag");
        assertThat(defaults.get("requireStatusReason").asBoolean()).isFalse();

        saveSettings(false, "most_specific", "ignore", true);
        JsonNode saved = get200("/api/v1/retention/settings", admin);
        assertThat(saved.get("precedence").asString()).isEqualTo("most_specific");
        assertThat(saved.get("automationEnabled").asBoolean()).isFalse();

        call(put("/api/v1/retention/settings"), fx.jwtFor(owner),
                Map.of("automationEnabled", true, "precedence", "longest", "whenNoPolicy", "flag", "requireStatusReason", false), 403);
        call(put("/api/v1/retention/settings"), fx.jwtFor(admin),
                Map.of("automationEnabled", true, "precedence", "shortest", "whenNoPolicy", "flag", "requireStatusReason", false), 400);
        assertThat(audit("RETENTION_SETTINGS_CHANGED", null)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the automatic archive (007, 008, 012, 015)

    @Test
    void whenTheActivePeriodElapses_theDocumentMovesToArchive_onceAndWithoutAnyoneActing() throws Exception {
        createPolicy(admin, rule("Invoices", "document_type", type, 1, "days", 30, "days", "HMRC"), 200);
        age(documentId, 2);

        JsonNode result = runNow();
        assertThat(result.get("archived").asInt()).isEqualTo(1);
        assertThat(lifecycleState()).isEqualTo("archived");
        assertThat(titles(listed(owner, ""))).doesNotContain("Supplier invoice 1041");
        assertThat(titles(listedArchive(records))).contains("Supplier invoice 1041");
        assertThat(listedArchive(records).get(0).get("lifecycleState").asString()).isEqualTo("archived");
        assertThat(audit("DOCUMENT_AUTO_ARCHIVED")).isEqualTo(1);

        // Running again changes nothing: no second archive, no second audit row.
        assertThat(runNow().get("archived").asInt()).isZero();
        assertThat(audit("DOCUMENT_AUTO_ARCHIVED")).isEqualTo(1);
    }

    @Test
    void aDocumentNotYetDue_isLeftAlone_andShowsWhenItWillBeArchived() throws Exception {
        createPolicy(admin, rule("Invoices", "document_type", type, 1, "years", 6, "years", null), 200);
        runNow();
        assertThat(lifecycleState()).isEqualTo("active");

        JsonNode panel = get200("/api/v1/documents/" + documentId + "/retention", owner);
        assertThat(panel.get("policyName").asString()).isEqualTo("Invoices");
        assertThat(panel.get("activePeriod").asString()).isEqualTo("1 years");
        assertThat(panel.get("archiveDueAt").isNull()).isFalse();
        assertThat(panel.get("reviewDueAt").isNull()).isFalse();
        assertThat(panel.get("legalHold").asBoolean()).isFalse();
    }

    @Test
    void aDocumentSwitchedOffByHand_isStillArchivedWhenItsPeriodEnds() throws Exception {
        createPolicy(admin, rule("Invoices", "document_type", type, 1, "days", 30, "days", null), 200);
        status(records, "inactive", null, 200);
        age(documentId, 2);
        runNow();
        assertThat(lifecycleState()).isEqualTo("archived");
    }

    @Test
    void whenTheTotalRetentionHasElapsed_itIsFlaggedForAnAdministrator_neverDeleted() throws Exception {
        createPolicy(admin, rule("Short", "document_type", type, 1, "days", 1, "days", null), 200);
        age(documentId, 5);

        JsonNode result = runNow();
        assertThat(result.get("archived").asInt()).isEqualTo(1);
        assertThat(result.get("flagged").asInt()).isEqualTo(1);
        assertThat(audit("DOCUMENT_RETENTION_FLAGGED")).isEqualTo(1);
        assertThat(fx.count("select count(*) from documents where document_id = ? and not is_deleted", documentId)).isEqualTo(1);

        JsonNode queue = get200("/api/v1/retention/review", admin).get("content");
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).get("title").asString()).isEqualTo("Supplier invoice 1041");
        call(get("/api/v1/retention/review"), fx.jwtFor(owner), null, 403);
        // Flagged once - a second run does not flag it again.
        assertThat(runNow().get("flagged").asInt()).isZero();
        assertThat(audit("DOCUMENT_RETENTION_FLAGGED")).isEqualTo(1);
    }

    @Test
    void anAdministratorCanExtendRetention_insteadOfProceeding_andNeedsAReason() throws Exception {
        flagged();
        call(post("/api/v1/documents/" + documentId + "/retention/extend"), fx.jwtFor(admin),
                Map.of("amount", 2, "unit", "years", "reason", " "), 400);
        call(post("/api/v1/documents/" + documentId + "/retention/extend"), fx.jwtFor(owner),
                Map.of("amount", 2, "unit", "years", "reason", "x"), 403);

        JsonNode view = call(post("/api/v1/documents/" + documentId + "/retention/extend"), fx.jwtFor(admin),
                Map.of("amount", 2, "unit", "years", "reason", "Supplier dispute unresolved"), 200);
        assertThat(view.get("reviewFlaggedAt").isNull()).isTrue();
        assertThat(view.get("extendedUntil").isNull()).isFalse();
        assertThat(get200("/api/v1/retention/review", admin).get("content")).isEmpty();
        assertThat(audit("DOCUMENT_RETENTION_EXTENDED")).isEqualTo(1);

        // Not flagged any more, so it cannot be extended again.
        assertThat(call(post("/api/v1/documents/" + documentId + "/retention/extend"), fx.jwtFor(admin),
                Map.of("amount", 1, "unit", "years", "reason", "again"), 409).get("code").asString())
                .isEqualTo("DOCUMENT_NOT_AWAITING_REVIEW");
        // And a later run leaves it unflagged: the extension holds.
        runNow();
        assertThat(get200("/api/v1/retention/review", admin).get("content")).isEmpty();
    }

    @Test
    void disposal_sendsItToTheRecycleBin_butOnlyForAFlaggedDocument_withAReason() throws Exception {
        call(post("/api/v1/documents/" + documentId + "/retention/dispose"), fx.jwtFor(admin), Map.of("reason", "x"), 409); // not flagged
        flagged();
        call(post("/api/v1/documents/" + documentId + "/retention/dispose"), fx.jwtFor(admin), Map.of("reason", ""), 400);
        call(post("/api/v1/documents/" + documentId + "/retention/dispose"), fx.jwtFor(owner), Map.of("reason", "x"), 403);

        call(post("/api/v1/documents/" + documentId + "/retention/dispose"), fx.jwtFor(admin),
                Map.of("reason", "Retention ended; no dispute"), 200);
        assertThat(fx.count("select count(*) from documents where document_id = ? and is_deleted and purged_at is null",
                documentId)).isEqualTo(1); // in the recycle bin, not destroyed
        assertThat(audit("DOCUMENT_RETENTION_DISPOSED")).isEqualTo(1);
    }

    @Test
    void disposalIsRefusedWhileAnotherDocumentIsLinkedToIt_orItIsUnderHold() throws Exception {
        flagged();
        UUID other = fx.document(tenantId, type, owner, "Linked credit note");
        UUID relType = UUID.randomUUID();
        fx.jdbc().update("insert into relationship_types (relationship_type_id, tenant_id, code, category, label, inverse_label, "
                + "directionality) values (?, ?, 'REFERENCES', 'reference', 'References', 'Referenced by', 'directional')", relType, tenantId);
        fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id, target_document_id) "
                + "values (?, ?, ?, ?)", tenantId, relType, other, documentId);

        JsonNode refused = call(post("/api/v1/documents/" + documentId + "/retention/dispose"), fx.jwtFor(admin),
                Map.of("reason", "Retention ended"), 409);
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_REFERENCED");
        assertThat(refused.get("message").asString()).contains("linked to 1 other document");

        fx.jdbc().update("update document_relationships set removed_at = now(), removed_reason = 'user_removed' where target_document_id = ?", documentId);
        placeHold("Investigation");
        assertThat(call(post("/api/v1/documents/" + documentId + "/retention/dispose"), fx.jwtFor(admin),
                Map.of("reason", "Retention ended"), 409).get("code").asString()).isEqualTo("DOCUMENT_ON_LEGAL_HOLD");
    }

    @Test
    void legalHoldSuspendsArchival_andLiftingItCompletesThePendingTransitionAtOnce() throws Exception {
        createPolicy(admin, rule("Invoices", "document_type", type, 1, "days", 30, "days", null), 200);
        age(documentId, 2);
        placeHold("Investigation");

        assertThat(runNow().get("archived").asInt()).isZero();
        assertThat(lifecycleState()).isEqualTo("active"); // due, but on hold - nothing happens
        assertThat(audit("DOCUMENT_AUTO_ARCHIVED")).isZero();

        liftHold(); // no scheduled run in between
        assertThat(lifecycleState()).isEqualTo("archived");
        assertThat(audit("DOCUMENT_AUTO_ARCHIVED")).isEqualTo(1);
    }

    @Test
    void aReinstatedDocument_getsAFreshActivePeriod_andIsNotArchivedAgainAtOnce() throws Exception {
        createPolicy(admin, rule("Invoices", "document_type", type, 1, "days", 30, "days", null), 200);
        age(documentId, 2);
        runNow();
        assertThat(lifecycleState()).isEqualTo("archived");

        status(records, "active", "Dispute reopened", 200);
        runNow();
        assertThat(lifecycleState()).isEqualTo("active");
        assertThat(titles(listed(owner, ""))).contains("Supplier invoice 1041");
    }

    @Test
    void aFolderRule_coversTheFolderAndItsSubfolders() throws Exception {
        UUID parent = folder("Contracts 2020", null);
        UUID child = folder("Signed", parent);
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", child, documentId);
        createPolicy(admin, rule("Contracts", "folder", parent, 1, "days", 30, "days", "Companies Act 2006"), 200);
        age(documentId, 2);

        runNow();
        assertThat(lifecycleState()).isEqualTo("archived");
    }

    @Test
    void whenRulesOverlap_theOrganisationsPrecedenceDecidesWhichApplies() throws Exception {
        createPolicy(admin, rule("Default", "organisation", null, 100, "days", 0, "days", null), 200);
        createPolicy(admin, rule("Invoices", "document_type", type, 1, "days", 0, "days", null), 200);
        age(documentId, 10);

        // Longest retention (the default): the 100-day default wins, so a 10-day-old document is still active.
        runNow();
        assertThat(lifecycleState()).isEqualTo("active");
        assertThat(get200("/api/v1/documents/" + documentId + "/retention", owner).get("policyName").asString()).isEqualTo("Default");

        // Most specific: the document type's own rule wins, and it is already due.
        saveSettings(true, "most_specific", "flag", false);
        runNow();
        assertThat(lifecycleState()).isEqualTo("archived");
    }

    @Test
    void aDocumentNoRuleCovers_isFlaggedForAnAdministratorToAssignOne_orLeftAloneByChoice() throws Exception {
        UUID otherType = fx.documentType(tenantId, "Memo");
        createPolicy(admin, rule("Memos", "document_type", otherType, 1, "years", 1, "years", null), 200);

        runNow();
        JsonNode waiting = get200("/api/v1/retention/without-policy", admin).get("content");
        assertThat(titles(waiting)).contains("Supplier invoice 1041");
        assertThat(audit("DOCUMENT_NO_RETENTION_POLICY")).isEqualTo(1);
        assertThat(get200("/api/v1/documents/" + documentId + "/retention", owner).get("needsPolicy").asBoolean()).isTrue();

        // An administrator assigns a rule; the next run clears the flag.
        createPolicy(admin, rule("Default", "organisation", null, 1, "years", 1, "years", null), 200);
        runNow();
        assertThat(get200("/api/v1/retention/without-policy", admin).get("content")).isEmpty();

        // The other choice: leave such documents alone.
        UUID memo2 = fx.documentType(tenantId, "Letter");
        UUID letter = fx.document(tenantId, memo2, owner, "A letter");
        fx.jdbc().update("update retention_policies set is_deleted = true where tenant_id = ? and scope_type = 'organisation'", tenantId);
        saveSettings(true, "longest", "ignore", false);
        runNow();
        assertThat(fx.count("select count(*) from documents where document_id = ? and no_policy_flagged_at is not null", letter)).isZero();
    }

    @Test
    void theScheduledJob_skipsAnOrganisationThatSwitchedAutomationOff_butRunNowStillWorks() throws Exception {
        createPolicy(admin, rule("Invoices", "document_type", type, 1, "days", 30, "days", null), 200);
        age(documentId, 2);
        saveSettings(false, "longest", "flag", false);

        job.runAll();
        assertThat(lifecycleState()).isEqualTo("active"); // automation is off for this organisation

        saveSettings(true, "longest", "flag", false);
        job.runAll();
        assertThat(lifecycleState()).isEqualTo("archived");
    }

    @Test
    void anOrganisationWithNoRulesAtAll_isNeverTouched() throws Exception {
        age(documentId, 4000);
        job.runAll();
        assertThat(lifecycleState()).isEqualTo("active");
        assertThat(fx.count("select count(*) from documents where document_id = ? and no_policy_flagged_at is not null", documentId)).isZero();
    }

    // ------------------------------------------------------------------ the other places documents are counted and listed

    @Test
    void theDashboardCountsOnlyActiveDocuments_butStorageStillCountsEveryFile() throws Exception {
        fx.document(tenantId, type, owner, "A second invoice"); // stays active
        status(records, "inactive", null, 200);                  // the first one is switched off

        JsonNode dashboard = get200("/api/v1/dashboard?days=30", owner);
        assertThat(dashboard.get("documents").get("total").asLong()).isEqualTo(1); // only the active one
        assertThat(dashboard.get("types").get(0).get("count").asLong()).isEqualTo(1);
        // An inactive document's file still takes its space, so storage and version counts are not reduced.
        assertThat(dashboard.get("storage").get("versions").asLong()).isEqualTo(2);
    }

    @Test
    void aPinnedDocumentThatBecomesInactiveOrArchived_staysOnThePinnedList_flaggedAsArchived() throws Exception {
        User pinner = fx.user(tenantId, "Pia");
        fx.role(tenantId, "Pinners", new String[]{VIEW, "pid.pin-unpin-documents.pin"}, pinner);
        call(put("/api/v1/pins/" + documentId), fx.jwtFor(pinner), Map.of("mode", "ALL", "userIds", List.of()), 204);

        JsonNode before = get200("/api/v1/pins", pinner).get("pins");
        assertThat(before).hasSize(1);
        assertThat(before.get(0).get("archived").asBoolean()).isFalse();

        status(records, "inactive", null, 200);
        JsonNode inactive = get200("/api/v1/pins", pinner).get("pins");
        assertThat(inactive).hasSize(1); // a pin never just vanishes
        assertThat(inactive.get(0).get("archived").asBoolean()).isTrue();
        assertThat(inactive.get(0).get("document").get("lifecycleState").asString()).isEqualTo("inactive");

        status(records, "active", null, 200);
        assertThat(get200("/api/v1/pins", pinner).get("pins").get(0).get("archived").asBoolean()).isFalse();
    }

    @Test
    void aFolderTileCountsOnlyTheDocumentsItsListingShows_andAFolderHoldingOnlyHiddenOnesCannotBeDeleted() throws Exception {
        UUID folder = folder("Invoices 2026", null);
        UUID second = fx.document(tenantId, type, owner, "A second invoice");
        fx.jdbc().update("update documents set folder_id = ? where document_id in (?, ?)", folder, documentId, second);
        User folderAdmin = fx.user(tenantId, "Fay");
        fx.role(tenantId, "Folder admins", new String[]{VIEW, "dsc.manage-folder-structure.delete-folder"}, folderAdmin);

        assertThat(folderCount(owner, folder)).isEqualTo(2);
        status(records, "inactive", null, 200); // one of the two is switched off
        assertThat(folderCount(owner, folder)).isEqualTo(1); // opening the folder lists one, so the tile says one
        assertThat(titles(call(get("/api/v1/documents?folderId=" + folder + "&size=100"), fx.jwtFor(owner), null, 200).get("content")))
                .containsExactly("A second invoice");

        // A folder is not empty while hidden documents are still filed in it - and the refusal says so.
        fx.jdbc().update("update documents set lifecycle_state = 'archived' where document_id = ?", second);
        assertThat(folderCount(owner, folder)).isZero();
        JsonNode refused = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/folders/" + folder),
                fx.jwtFor(folderAdmin), null, 409);
        assertThat(refused.get("message").asString()).contains("2 inactive or archived documents").contains("Inactive & Archive");
    }

    @Test
    void runningTheJobNow_needsItsPermission() throws Exception {
        call(post("/api/v1/retention/run"), fx.jwtFor(owner), null, 403);
        call(post("/api/v1/retention/run"), fx.jwtFor(records), null, 403);
        call(post("/api/v1/retention/run"), fx.jwtFor(admin), null, 200);
        assertThat(audit("RETENTION_RUN_REQUESTED", null)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode status(User as, String state, String reason, int expected) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", state);
        body.put("reason", reason);
        return call(put("/api/v1/documents/" + documentId + "/status"), fx.jwtFor(as), body, expected);
    }

    private JsonNode runNow() throws Exception {
        return call(post("/api/v1/retention/run"), fx.jwtFor(admin), null, 200);
    }

    private void saveSettings(boolean automation, String precedence, String whenNone, boolean reason) throws Exception {
        call(put("/api/v1/retention/settings"), fx.jwtFor(admin), Map.of("automationEnabled", automation,
                "precedence", precedence, "whenNoPolicy", whenNone, "requireStatusReason", reason), 200);
    }

    private JsonNode createPolicy(User as, Map<String, Object> body, int expected) throws Exception {
        return call(post("/api/v1/retention/policies"), fx.jwtFor(as), body, expected);
    }

    private static Map<String, Object> rule(String name, String scope, UUID scopeId, int active, String activeUnit, int archive,
                                            String archiveUnit, String framework) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("scopeType", scope);
        m.put("scopeId", scopeId);
        m.put("activeAmount", active);
        m.put("activeUnit", activeUnit);
        m.put("archiveAmount", archive);
        m.put("archiveUnit", archiveUnit);
        m.put("clockStart", "created");
        m.put("framework", framework);
        return m;
    }

    private JsonNode listed(User as, String query) throws Exception {
        return call(get("/api/v1/documents?all=true&size=100" + (query.isEmpty() ? "" : "&" + query)), fx.jwtFor(as), null, 200)
                .get("content");
    }

    private JsonNode listedArchive(User as) throws Exception {
        return call(get("/api/v1/documents?view=archive&all=true&size=100"), fx.jwtFor(as), null, 200).get("content");
    }

    private static List<String> titles(JsonNode rows) {
        List<String> titles = new ArrayList<>();
        rows.forEach(r -> titles.add(r.get("title").asString()));
        return titles;
    }

    private void age(UUID id, int days) {
        fx.jdbc().update("update documents set created_at = now() - make_interval(days => ?), updated_at = now() - make_interval(days => ?) "
                + "where document_id = ?", days, days, id);
    }

    private void flagged() throws Exception {
        createPolicy(admin, rule("Short", "document_type", type, 1, "days", 1, "days", null), 200);
        age(documentId, 5);
        runNow();
        assertThat(get200("/api/v1/retention/review", admin).get("content")).hasSize(1);
    }

    private void placeHold(String reason) throws Exception {
        call(post("/api/v1/documents/" + documentId + "/legal-hold"), fx.jwtFor(admin), Map.of("reason", reason), 200);
    }

    private void liftHold() throws Exception {
        call(post("/api/v1/documents/" + documentId + "/legal-hold/release"), fx.jwtFor(admin), Map.of("reason", "Matter closed"), 200);
    }

    private UUID folder(String name, UUID parent) {
        UUID id = UUID.randomUUID();
        fx.jdbc().update("insert into folders (folder_id, tenant_id, parent_folder_id, folder_name) values (?, ?, ?, ?)",
                id, tenantId, parent, name);
        return id;
    }

    private long folderCount(User as, UUID folderId) throws Exception {
        for (JsonNode folder : call(get("/api/v1/folders"), fx.jwtFor(as), null, 200)) {
            if (folderId.toString().equals(folder.get("folderId").asString())) {
                return folder.get("documentCount").asLong();
            }
        }
        throw new AssertionError("folder not listed");
    }

    private String lifecycleState() {
        return fx.jdbc().queryForObject("select lifecycle_state from documents where document_id = ?", String.class, documentId);
    }

    private String lifecycleReason() {
        return fx.jdbc().queryForObject("select lifecycle_reason from documents where document_id = ?", String.class, documentId);
    }

    private long audit(String action) {
        return audit(action, documentId);
    }

    /** Audit rows of one action about one document - or, with a null id, about anything in the organisation. */
    private long audit(String action, UUID objectId) {
        return objectId == null
                ? fx.count("select count(*) from audit_events where tenant_id = ? and action = ?", tenantId, action)
                : fx.count("select count(*) from audit_events where tenant_id = ? and action = ? and object_id = ?",
                tenantId, action, objectId);
    }
}
