package com.oak.dms.ownership;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O6: the offboarding preview and action - transfer or holding, flagged work, the confirmation, deactivation, rollback. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipOffboardingIT extends OwnershipApiSupport {

    @MockitoBean
    KeycloakUserService keycloak;

    private ContractWorld w;
    private User leaver;
    private User successor;
    private UUID tenantId;
    private final List<UUID> tasks = new ArrayList<>();

    @BeforeEach
    void world() throws Exception {
        w = ContractWorld.create(fx);
        tenantId = w.tenantId();
        leaver = w.nimal();
        successor = w.dilani();
        when(keycloak.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(w.admin().getKeycloakId()));
        when(keycloak.getPlatformRealmRoles(w.admin().getKeycloakId())).thenReturn(List.of("TENANT_ADMIN"));
    }

    private JsonNode preview(User as, boolean admin, UUID userId, int status) throws Exception {
        return call(get("/api/v1/users/" + userId + "/offboarding-preview"), admin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as), null, status);
    }

    private JsonNode offboard(User as, boolean admin, UUID userId, Object body, int status) throws Exception {
        return call(post("/api/v1/users/" + userId + "/offboard"), admin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as), body, status);
    }

    private static java.util.Map<String, Object> offboardBody(User target, Integer acknowledged, Object... extra) {
        java.util.Map<String, Object> map = body("reason", "Left the company", "mode", "transfer",
                "newOwnerUserId", target.getUserId());
        if (acknowledged != null) {
            map.put("acknowledgedStuckTasks", acknowledged);
        }
        for (int i = 0; i < extra.length; i += 2) {
            map.put((String) extra[i], extra[i + 1]);
        }
        return map;
    }

    /** One approval run per task, then SQL puts each task in the hands of the leaver by a different route. */
    private void leaverHoldsFiveTasksAndStartedOneRun() throws Exception {
        activeTemplate(w.admin(), "REVIEW", w.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "any", "halt_immediately", 24,
                        entry("user", w.saman().getUserId()), entry("user", w.kumari().getUserId()))));
        String[] routes = {"assigned", "delegated", "claimed", "escalated", "awaiting_info"};
        for (String route : routes) {
            UUID document = fx.document(tenantId, w.contractTypeId(), w.chamath(), "Run for " + route);
            JsonNode run = submit(w.chamath(), document, 201);
            UUID task = taskId(run, 1, w.saman().getDisplayName());
            switch (route) {
                case "assigned" -> fx.jdbc().update("update workflow_tasks set assignee_id = ? where task_id = ?", leaver.getUserId(), task);
                case "delegated" -> fx.jdbc().update("update workflow_tasks set delegated_from = assignee_id, delegated_to = ?,"
                        + " delegated_at = now() where task_id = ?", leaver.getUserId(), task);
                case "claimed" -> fx.jdbc().update("update workflow_tasks set claimed_by = ?, claimed_at = now(), status = 'claimed'"
                        + " where task_id = ?", leaver.getUserId(), task);
                case "escalated" -> fx.jdbc().update("update workflow_tasks set escalated_to = ?, escalated_at = now() where task_id = ?",
                        leaver.getUserId(), task);
                default -> fx.jdbc().update("update workflow_tasks set assignee_id = ?, status = 'awaiting_info' where task_id = ?",
                        leaver.getUserId(), task);
            }
            tasks.add(task);
        }
        // not the leaver's: a cancelled task of theirs, and an unclaimed role pool they belong to
        UUID closedDoc = fx.document(tenantId, w.contractTypeId(), w.chamath(), "Closed one");
        UUID closed = taskId(submit(w.chamath(), closedDoc, 201), 1, w.saman().getDisplayName());
        fx.jdbc().update("update workflow_tasks set assignee_id = ?, status = 'cancelled' where task_id = ?", leaver.getUserId(), closed);
        UUID poolDoc = fx.document(tenantId, w.contractTypeId(), w.chamath(), "Pool one");
        UUID pool = taskId(submit(w.chamath(), poolDoc, 201), 1, w.saman().getDisplayName());
        fx.jdbc().update("update workflow_tasks set assignee_type = 'role', assignee_id = ? where task_id = ?", w.deptHead().getRoleId(), pool);
        fx.grant(leaver, w.deptHead());

        // a run the leaver started
        activeTemplate(w.admin(), "INV", w.invoiceTypeId(), "flag_only", List.of(
                stageDef("Check", "any", "halt_immediately", 24, entry("user", w.saman().getUserId()))));
        fx.role(tenantId, "Leaver submits", new String[]{WorkflowFixtures.SUBMIT_PERMISSION, VIEW}, leaver);
        submit(leaver, fx.document(tenantId, w.invoiceTypeId(), leaver, "Leaver's invoice"), 201);
    }

    private List<UUID> leaverDocuments(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(fx.document(tenantId, w.contractTypeId(), leaver, "Leaver doc " + i));
        }
        return ids;
    }

    private boolean userDeactivated(User user) {
        return fx.count("select count(*) from users where user_id = ? and is_deleted and status = 'disabled'", user.getUserId()) == 1;
    }

    @Test
    void previewCountsDocumentsAndFlagsTheWorkLeftBehind() throws Exception {
        List<UUID> docs = leaverDocuments(3);
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", docs.get(2));
        leaverHoldsFiveTasksAndStartedOneRun();

        JsonNode preview = preview(w.admin(), true, leaver.getUserId(), 200);

        assertThat(preview.get("user").get("name").asString()).isEqualTo("Nimal");
        // 3 documents plus the invoice made for the started run
        assertThat(preview.get("ownedTotal").asInt()).isEqualTo(4);
        assertThat(preview.get("inRecycleBinCount").asInt()).isEqualTo(1);
        assertThat(preview.get("hiddenCount").asInt()).isZero();
        JsonNode flags = preview.get("flags");
        assertThat(flags.get("stuckTaskCount").asInt()).isEqualTo(5);
        assertThat(flags.get("startedRunCount").asInt()).isEqualTo(1);
        List<String> routes = new ArrayList<>();
        flags.get("openTasks").forEach(t -> routes.add(t.get("route").asString()));
        assertThat(routes).containsExactlyInAnyOrder("assigned", "delegated", "claimed", "escalated", "assigned");
        JsonNode first = flags.get("openTasks").get(0);
        assertThat(first.get("instanceUrl").asString()).startsWith("/workflows/instances/");
        assertThat(first.get("documentTitle").asString()).startsWith("Run for");
        assertThat(first.get("canReassign").asBoolean()).isTrue();
        assertThat(flags.get("startedRuns").get(0).get("documentTitle").asString()).isEqualTo("Leaver's invoice");
        assertThat(flags.get("startedRuns").get(0).get("canCancel").asBoolean()).isTrue();
        // no department, so the chain ends at the tenant admin
        assertThat(preview.get("suggestedTarget").get("source").asString()).isEqualTo("tenant_admin");
        assertThat(preview.get("canOffboard").asBoolean()).isTrue();
    }

    @Test
    void offboardingNeedsTheNumberOfStuckTasksToBeConfirmed() throws Exception {
        List<UUID> docs = leaverDocuments(2);
        leaverHoldsFiveTasksAndStartedOneRun();

        JsonNode none = offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, null), 409);
        assertThat(none.get("code").asString()).isEqualTo("OFFBOARD_CONFIRMATION_REQUIRED");
        assertThat(none.get("details").get("stuckTaskCount").asInt()).isEqualTo(5);
        offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, 4), 409);
        assertThat(userDeactivated(leaver)).isFalse();
        assertThat(ownerOf(docs.get(0))).isEqualTo(leaver.getUserId().toString());
        assertThat(fx.count("select count(*) from ownership_transfer_batches where tenant_id = ?", tenantId)).isZero();

        JsonNode done = offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, 5), 200);

        assertThat(done.get("deactivated").asBoolean()).isTrue();
        assertThat(done.get("batch").get("kind").asString()).isEqualTo("offboarding");
        assertThat(done.get("batch").get("transferredCount").asInt()).isEqualTo(3);   // 2 + the invoice
        assertThat(done.get("batch").get("flaggedTaskCount").asInt()).isEqualTo(5);
        assertThat(ownerOf(docs.get(0))).isEqualTo(successor.getUserId().toString());
        assertThat(userDeactivated(leaver)).isTrue();
        verify(keycloak).disableUser(leaver.getKeycloakId());
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ? and reason = 'offboarding'"
                + " and previous_owner_user_id = ?", tenantId, leaver.getUserId())).isEqualTo(3);
        assertThat(auditCount(tenantId, "USER_OFFBOARDED")).isEqualTo(1);
        assertThat(auditCount(tenantId, "USER_DEACTIVATED")).isEqualTo(1);
        assertThat(auditCount(tenantId, "OWNERSHIP_BATCH_TRANSFERRED")).isEqualTo(1);
        // flagged, never reassigned: the tasks are untouched and can still be reassigned the normal way
        assertThat(fx.count("select count(*) from workflow_tasks where task_id in (?, ?) and status in ('pending','claimed','awaiting_info')",
                tasks.get(0), tasks.get(1))).isEqualTo(2);
        reassign(w.admin(), tasks.get(0), w.saman(), "Nimal has left", 200);
    }

    @Test
    void withoutOpenTasksNoConfirmationIsNeeded() throws Exception {
        List<UUID> docs = leaverDocuments(2);
        JsonNode done = offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, null), 200);
        assertThat(done.get("batch").get("transferredCount").asInt()).isEqualTo(2);
        assertThat(done.get("flags").get("stuckTaskCount").asInt()).isZero();
        assertThat(ownerOf(docs.get(1))).isEqualTo(successor.getUserId().toString());
        assertThat(userDeactivated(leaver)).isTrue();
    }

    @Test
    void holdingModeParksTheDocumentsWithTheChainsOwner() throws Exception {
        fx.department(tenantId, "Treasury", leaver);
        List<UUID> docs = leaverDocuments(2);
        JsonNode done = offboard(w.admin(), true, leaver.getUserId(),
                body("reason", "Redundancy", "mode", "holding"), 200);
        // Treasury has no holding owner or head -> tenant admin
        assertThat(done.get("batch").get("holding").asBoolean()).isTrue();
        assertThat(done.get("batch").get("newOwner").get("name").asString()).isEqualTo("Admin");
        assertThat(ownerOf(docs.get(0))).isEqualTo(w.admin().getUserId().toString());
        assertThat(fx.count("select count(*) from documents where owner_user_id = ? and ownership_state = 'holding'"
                + " and owner_department_id is not null", w.admin().getUserId())).isEqualTo(2);
        assertThat(fx.count("select count(*) from ownership_transfers where reason = 'holding' and tenant_id = ?", tenantId)).isEqualTo(2);
    }

    @Test
    void ifKeycloakRefusesTheAccountStaysAndNothingIsTransferred() throws Exception {
        List<UUID> docs = leaverDocuments(2);
        doThrow(new RuntimeException("Keycloak is down")).when(keycloak).disableUser(any());

        offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, null), 500);

        assertThat(userDeactivated(leaver)).isFalse();
        assertThat(ownerOf(docs.get(0))).isEqualTo(leaver.getUserId().toString());
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ?", tenantId)).isZero();
        assertThat(fx.count("select count(*) from ownership_transfer_batches where tenant_id = ?", tenantId)).isZero();
        assertThat(auditCount(tenantId, "USER_OFFBOARDED")).isZero();
    }

    @Test
    void theTenantAdminAccountCannotBeOffboarded() throws Exception {
        leaverDocuments(1);
        User otherAdmin = fx.user(tenantId, "Admin2");
        when(keycloak.getPlatformRealmRoles(otherAdmin.getKeycloakId())).thenReturn(List.of("TENANT_ADMIN"));
        fx.document(tenantId, w.contractTypeId(), otherAdmin, "Admin2 doc");
        offboard(w.admin(), true, otherAdmin.getUserId(), offboardBody(successor, null), 422);
        assertThat(userDeactivated(otherAdmin)).isFalse();
        assertThat(preview(w.admin(), true, otherAdmin.getUserId(), 200).get("blockers").toString()).contains("TENANT_ADMIN_ACCOUNT");
        verify(keycloak, never()).disableUser(otherAdmin.getKeycloakId());
    }

    @Test
    void invalidTargetsAndUnknownUsersAreRefused() throws Exception {
        leaverDocuments(1);
        User disabled = fx.user(tenantId, "Dormant");
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", disabled.getUserId());
        offboard(w.admin(), true, leaver.getUserId(), offboardBody(disabled, null), 422);
        offboard(w.admin(), true, leaver.getUserId(), body("reason", "x", "mode", "transfer"), 422);
        offboard(w.admin(), true, leaver.getUserId(), body("reason", "x", "mode", "sideways"), 422);
        offboard(w.admin(), true, leaver.getUserId(), body("mode", "holding"), 400);          // reason is required
        offboard(w.admin(), true, UUID.randomUUID(), offboardBody(successor, null), 404);
        UUID otherTenant = fx.tenant();
        User foreign = fx.user(otherTenant, "Foreign");
        offboard(w.admin(), true, foreign.getUserId(), offboardBody(successor, null), 404);
        preview(w.admin(), true, foreign.getUserId(), 404);
        assertThat(userDeactivated(leaver)).isFalse();
    }

    @Test
    void anAlreadyDeactivatedUserIsGone() throws Exception {
        leaverDocuments(1);
        offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, null), 200);
        offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, null), 404);
        assertThat(fx.count("select count(*) from ownership_transfer_batches where tenant_id = ?", tenantId)).isEqualTo(1);
        // the preview still works and says so
        assertThat(preview(w.admin(), true, leaver.getUserId(), 200).get("blockers").toString()).contains("ALREADY_DEACTIVATED");
    }

    @Test
    void managerNeedsTheDeactivatePermissionAndOnlyMovesWhatTheyCanOpen() throws Exception {
        var legal = w.legal();
        User legalPerson = w.saman();
        User manager = fx.user(tenantId, "Manager");
        makeHead(legal.getDepartmentId(), manager);
        fx.role(tenantId, "Dot only", new String[]{SELECT, EXECUTE, VIEW}, manager);
        UUID open = fx.document(tenantId, w.contractTypeId(), legalPerson, "Open doc");
        UUID secret = fx.document(tenantId, w.contractTypeId(), legalPerson, "Secret doc");
        restrictTo(tenantId, secret, "edit", legalPerson);

        offboard(manager, false, legalPerson.getUserId(), offboardBody(successor, null), 403);   // lacks deactivate
        JsonNode preview = preview(manager, false, legalPerson.getUserId(), 200);
        assertThat(preview.get("canOffboard").asBoolean()).isFalse();
        assertThat(preview.get("hiddenCount").asInt()).isEqualTo(1);
        assertThat(preview.toString()).doesNotContain("Secret doc");
        preview(manager, false, leaver.getUserId(), 403);                                        // not their department

        fx.role(tenantId, "Can deactivate", new String[]{DEACTIVATE}, manager);
        JsonNode done = offboard(manager, false, legalPerson.getUserId(), offboardBody(successor, null), 200);
        assertThat(done.get("batch").get("transferredCount").asInt()).isEqualTo(1);
        assertThat(ownerOf(open)).isEqualTo(successor.getUserId().toString());
        assertThat(ownerOf(secret)).as("hidden from the manager: stays with the deactivated owner").isEqualTo(legalPerson.getUserId().toString());
        assertThat(userDeactivated(legalPerson)).isTrue();
        // ...and shows up for an admin in the pending list (D13)
        JsonNode pending = call(get("/api/v1/ownership/pending"), fx.tenantAdminJwtFor(w.admin()), null, 200);
        assertThat(pending.toString()).contains("Secret doc").contains("owner_deactivated");
    }

    @Test
    void moreThanFiveThousandDocumentsAreSplitFirstThenTheUserIsOffboarded() throws Exception {
        UUID ref = versionOf(fx.document(tenantId, w.contractTypeId(), leaver, "Seed"));
        bulkDocuments(tenantId, w.contractTypeId(), leaver, ref, 5000, "Many");
        JsonNode refused = offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, null), 422);
        assertThat(refused.get("code").asString()).isEqualTo("OWNERSHIP_BATCH_TOO_LARGE");
        assertThat(userDeactivated(leaver)).isFalse();
        assertThat(ownedBy(leaver)).isEqualTo(5001);

        call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(w.admin()),
                allOf(leaver, successor, "limit", 5000), 200);
        JsonNode done = offboard(w.admin(), true, leaver.getUserId(), offboardBody(successor, null), 200);
        assertThat(done.get("batch").get("transferredCount").asInt()).isEqualTo(1);
        assertThat(ownedBy(leaver)).isZero();
        assertThat(userDeactivated(leaver)).isTrue();
    }
}
