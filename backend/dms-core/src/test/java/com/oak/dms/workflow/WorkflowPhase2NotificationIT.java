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
import org.springframework.mail.SimpleMailMessage;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2 mail (S9), after commit, with the mail sender mocked (WorkflowApiSupport). */
@SpringBootTest
@Import(WorkflowFixtures.class)
class WorkflowPhase2NotificationIT extends WorkflowApiSupport {

    @Autowired
    private WorkflowSlaService slaService;

    private ContractWorld w;
    private User delegate1;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
        delegate1 = fx.user(w.tenantId(), "Delegate1");
        fx.role(w.tenantId(), "Delegates " + UUID.randomUUID(), WorkflowFixtures.APPROVER_PERMISSIONS, delegate1);
        fx.role(w.tenantId(), "Requesters " + UUID.randomUUID(), new String[]{WorkflowInfoRequestIT.REQUEST_INFO},
                w.saman(), w.nimal());
        fx.role(w.tenantId(), "Commenters " + UUID.randomUUID(),
                new String[]{"docm.documents.view", "docm.document-comments.create"}, w.chamath(), w.dilani());
    }

    private JsonNode nimalStage(String escalationTarget, UUID escalationUser) throws Exception {
        Map<String, Object> stage = stageDef("Sign-off", "any", "halt_immediately", 48, entry("user", w.nimal().getUserId()));
        stage.put("reminderBeforeHours", 24);
        stage.put("escalationTarget", escalationTarget);
        stage.put("escalationUserId", escalationUser);
        activeTemplate(w.admin(), "MAIL", w.contractTypeId(), "flag_only", List.of(stage));
        return submit(w.chamath(), w.contractDocumentId(), 201);
    }

    private static java.util.function.Predicate<SimpleMailMessage> subject(String part) {
        return m -> m.getSubject() != null && m.getSubject().contains(part);
    }

    @Test
    void delegation_mailsTheDelegateAndTheOriginalHolder() throws Exception {
        UUID task = taskId(nimalStage("none", null), 1, w.nimal().getDisplayName());
        delegate(w.nimal(), task, delegate1, 200);
        assertThat(awaitMail(to(delegate1).and(subject("Approval needed"))).getText()).contains("Nimal delegated to you");
        assertThat(awaitMail(to(w.nimal()).and(subject("Task delegated"))).getText()).contains("Delegate1");
    }

    @Test
    void reminderAndEscalation_mailTheHoldersAndTheTarget() throws Exception {
        UUID instanceId = id(nimalStage("named_user", delegate1.getUserId()), "instanceId");
        fx.jdbc().update("update workflow_tasks set due_at = now() + interval '23 hours' where stage_instance_id in"
                + " (select stage_instance_id from workflow_stage_instances where instance_id = ?)", instanceId);
        slaService.runOnce();
        awaitMail(to(w.nimal()).and(subject("Reminder")));

        fx.jdbc().update("update workflow_tasks set due_at = now() - interval '1 minute' where stage_instance_id in"
                + " (select stage_instance_id from workflow_stage_instances where instance_id = ?)", instanceId);
        slaService.runOnce();
        assertThat(awaitMail(to(delegate1).and(subject("Escalated"))).getText()).contains("escalated to you");
        assertThat(awaitMail(to(w.nimal()).and(subject("Escalated"))).getText()).contains("escalated to Delegate1");
    }

    @Test
    void anUnusableEscalationTarget_alertsWhoeverCanReassign() throws Exception {
        User reassigner = fx.user(w.tenantId(), "Reassigner");
        fx.role(w.tenantId(), "Reassigners " + UUID.randomUUID(), new String[]{"wa.reassign-workflow-task"}, reassigner);
        UUID instanceId = id(nimalStage("named_user", w.dilani().getUserId()), "instanceId");
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", w.dilani().getUserId());
        fx.jdbc().update("update workflow_tasks set due_at = now() - interval '1 minute' where stage_instance_id in"
                + " (select stage_instance_id from workflow_stage_instances where instance_id = ?)", instanceId);
        slaService.runOnce();
        assertThat(awaitMail(to(reassigner).and(subject("Escalation failed"))).getText())
                .contains("no longer an active user");
    }

    @Test
    void informationRequest_mailsBothSides() throws Exception {
        UUID task = taskId(nimalStage("none", null), 1, w.nimal().getDisplayName());
        UUID request = id(postAs("/api/v1/workflow/tasks/" + task + "/info-requests", w.nimal(),
                Map.of("requestedFromUserId", w.chamath().getUserId(), "message", "Signed annex please"), 201),
                "infoRequestId");
        assertThat(awaitMail(to(w.chamath()).and(subject("Information requested"))).getText())
                .contains("Signed annex please");
        postAs("/api/v1/workflow/info-requests/" + request + "/decline", w.chamath(), Map.of("reason", "Not signed yet"), 200);
        assertThat(awaitMail(to(w.nimal()).and(subject("Information declined"))).getText()).contains("Not signed yet");

        UUID second = id(postAs("/api/v1/workflow/tasks/" + task + "/info-requests", w.nimal(),
                Map.of("requestedFromUserId", w.dilani().getUserId(), "message", "Budget?"), 201), "infoRequestId");
        postAs("/api/v1/workflow/instances/" + id(instance(fx.jdbc().queryForObject(
                "select instance_id from workflow_info_requests where info_request_id = ?", UUID.class, second),
                w.chamath()), "instanceId") + "/cancel", w.chamath(), Map.of("reason", "Withdrawn"), 200);
        assertThat(awaitMail(to(w.dilani()).and(subject("Information request closed"))).getText())
                .contains("The workflow was withdrawn");
    }

    @Test
    void mentionAndReply_mails_andNotifiedAtIsSet() throws Exception {
        User outsider = fx.user(w.tenantId(), "Outsider");
        UUID root = id(postAs("/api/v1/documents/" + w.contractDocumentId() + "/comments", w.chamath(),
                Map.of("body", "See @[Outsider](user:" + outsider.getUserId() + ")"), 201), "commentId");
        SimpleMailMessage mention = awaitMail(to(outsider).and(subject("mentioned you")));
        assertThat(mention.getText()).contains("See @Outsider").doesNotContain("/documents/");
        for (int i = 0; i < 50 && fx.count("select count(*) from comment_mentions where comment_id = ?"
                + " and notified_at is not null", root) == 0; i++) {
            Thread.sleep(100);
        }
        assertThat(fx.count("select count(*) from comment_mentions where comment_id = ? and notified_at is not null",
                root)).isEqualTo(1);

        postAs("/api/v1/documents/" + w.contractDocumentId() + "/comments", w.dilani(),
                Map.of("body", "Agreed", "parentCommentId", root), 201);
        assertThat(awaitMail(to(w.chamath()).and(subject("replied"))).getText()).contains("Agreed");
    }
}
