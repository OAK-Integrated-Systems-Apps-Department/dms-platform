package com.oak.dms.ownership;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ownership meets the features develop added: a signature-locked document (V108/V118) can still change hands
 * with its lock untouched, and the offboarding preview counts the right tasks now that a decision can be
 * retracted and a stage can be under a review lock (V119).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipDevelopInterplayIT extends OwnershipApiSupport {

    private ContractWorld w;
    private User leaver;
    private User successor;
    private UUID tenantId;

    @BeforeEach
    void world() throws Exception {
        w = ContractWorld.create(fx);
        tenantId = w.tenantId();
        leaver = w.nimal();
        successor = w.dilani();
    }

    private void lockBySignature(UUID documentId) {
        fx.jdbc().update("update documents set signature_locked_at = now() - interval '1 hour',"
                + " signature_locked_version_id = latest_version_id where document_id = ?", documentId);
    }

    private Map<String, Object> lockFields(UUID documentId) {
        return fx.jdbc().queryForMap("select signature_locked_at, signature_locked_version_id from documents where document_id = ?",
                documentId);
    }

    @Test
    void aSignatureLockedDocumentCanBeTransferredAndKeepsItsLock() throws Exception {
        UUID documentId = fx.document(tenantId, w.contractTypeId(), leaver, "Signed contract");
        lockBySignature(documentId);
        Map<String, Object> before = lockFields(documentId);
        assertThat(before.get("signature_locked_at")).isNotNull();

        call(post("/api/v1/documents/" + documentId + "/ownership/transfer"), fx.tenantAdminJwtFor(w.admin()),
                body("newOwnerUserId", successor.getUserId()), 200);

        assertThat(fx.count("select count(*) from documents where document_id = ? and owner_user_id = ?",
                documentId, successor.getUserId())).isEqualTo(1);
        assertThat(lockFields(documentId)).isEqualTo(before);
        assertThat(fx.count("select count(*) from ownership_transfers where document_id = ? and previous_owner_user_id = ?"
                + " and new_owner_user_id = ?", documentId, leaver.getUserId(), successor.getUserId())).isEqualTo(1);
    }

    @Test
    void aBulkTransferMovesSignatureLockedDocumentsToo() throws Exception {
        UUID signed = fx.document(tenantId, w.contractTypeId(), leaver, "Signed one");
        UUID plain = fx.document(tenantId, w.contractTypeId(), leaver, "Plain one");
        lockBySignature(signed);
        Map<String, Object> before = lockFields(signed);

        JsonNode result = call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(w.admin()),
                allOf(leaver, successor), 200);

        assertThat(result.get("transferredCount").asInt()).isEqualTo(2);
        assertThat(result.get("skippedCount").asInt()).isZero();
        assertThat(fx.count("select count(*) from documents where document_id in (?, ?) and owner_user_id = ?",
                signed, plain, successor.getUserId())).isEqualTo(2);
        assertThat(lockFields(signed)).isEqualTo(before);
        assertThat(fx.count("select count(*) from ownership_transfers where document_id = ?", signed)).isEqualTo(1);
    }

    /** One approval run whose first task SQL hands to the leaver; returns the task and its stage instance. */
    private UUID[] leaverTask(String title) throws Exception {
        UUID document = fx.document(tenantId, w.contractTypeId(), w.chamath(), title);
        UUID task = taskId(submit(w.chamath(), document, 201), 1, w.saman().getDisplayName());
        fx.jdbc().update("update workflow_tasks set assignee_id = ? where task_id = ?", leaver.getUserId(), task);
        UUID stage = fx.jdbc().queryForObject("select stage_instance_id from workflow_tasks where task_id = ?", UUID.class, task);
        return new UUID[]{task, stage};
    }

    private JsonNode flags() throws Exception {
        return call(get("/api/v1/users/" + leaver.getUserId() + "/offboarding-preview"), fx.tenantAdminJwtFor(w.admin()), null, 200)
                .get("flags");
    }

    @Test
    void retractedDecisionsAndReviewLocksAreCountedOnlyWhileTheStageIsStillOpen() throws Exception {
        activeTemplate(w.admin(), "REVIEW", w.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "any", "halt_immediately", 24,
                        entry("user", w.saman().getUserId()), entry("user", w.kumari().getUserId()))));
        UUID[] plain = leaverTask("Plain task");
        UUID[] retracted = leaverTask("Retracted decision");
        UUID[] locked = leaverTask("Stage under review lock");
        UUID[] closedStage = leaverTask("Stage already closed");

        // a retracted decision puts the task back to pending (V119) with the retraction recorded
        fx.jdbc().update("update workflow_tasks set retracted_at = now(), retracted_by = ?, retraction_reason = 'changed my mind'"
                + " where task_id = ?", leaver.getUserId(), retracted[0]);
        // another approver is reviewing in that stage: the lock blocks the task for a while but does not remove it
        fx.jdbc().update("update workflow_stage_instances set review_locked_by = ?, review_locked_at = now(),"
                + " review_lock_expires_at = now() + interval '5 minutes' where stage_instance_id = ?",
                w.kumari().getUserId(), locked[1]);
        // the stage closed (someone else satisfied it): nothing is left to act on
        fx.jdbc().update("update workflow_stage_instances set status = 'approved' where stage_instance_id = ?", closedStage[1]);
        // a task that really was decided is not open
        UUID[] decided = leaverTask("Decided task");
        fx.jdbc().update("update workflow_tasks set status = 'approved', decision = 'approve', acted_by = ?, acted_at = now()"
                + " where task_id = ?", leaver.getUserId(), decided[0]);

        JsonNode flags = flags();

        assertThat(flags.get("stuckTaskCount").asInt()).isEqualTo(3);
        List<String> titles = new java.util.ArrayList<>();
        flags.get("openTasks").forEach(t -> titles.add(t.get("documentTitle").asString()));
        assertThat(titles).containsExactlyInAnyOrder("Plain task", "Retracted decision", "Stage under review lock");
        assertThat(plain[0]).isNotNull();
    }

    @Test
    void aStageThatTheLeaverHasLockedForReviewDoesNotMakeSomeoneElsesTaskTheirs() throws Exception {
        activeTemplate(w.admin(), "REVIEW", w.contractTypeId(), "flag_only", List.of(
                stageDef("Review", "any", "halt_immediately", 24,
                        entry("user", w.saman().getUserId()), entry("user", w.kumari().getUserId()))));
        UUID document = fx.document(tenantId, w.contractTypeId(), w.chamath(), "Someone else's task");
        UUID task = taskId(submit(w.chamath(), document, 201), 1, w.saman().getDisplayName());
        UUID stage = fx.jdbc().queryForObject("select stage_instance_id from workflow_tasks where task_id = ?", UUID.class, task);
        fx.jdbc().update("update workflow_stage_instances set review_locked_by = ?, review_locked_at = now(),"
                + " review_lock_expires_at = now() + interval '5 minutes' where stage_instance_id = ?", leaver.getUserId(), stage);

        assertThat(flags().get("stuckTaskCount").asInt()).isZero();
    }
}
