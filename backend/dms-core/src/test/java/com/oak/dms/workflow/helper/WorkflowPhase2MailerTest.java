package com.oak.dms.workflow.helper;

import com.oak.dms.comment.helper.CommentNotificationMailer;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer.Doc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N8/N9 content preservation (unit): the migrated workflow and comment mailers
 * route through {@link NotificationService}. This captures the
 * {@link NotificationRequest} and asserts each notification keeps its category,
 * subject (title), key facts (body) and link - and carries the neutral variant
 * the service shows to a recipient who can't open the document (no title, no
 * document link).
 */
class WorkflowPhase2MailerTest {

    private NotificationService notificationService;
    private WorkflowNotificationMailer workflowMailer;
    private CommentNotificationMailer commentMailer;
    private final UUID documentId = UUID.randomUUID();
    private final Doc contract = new Doc(documentId, "Contract");
    private final UUID tenantId = UUID.randomUUID();
    private final User to = User.builder().userId(UUID.randomUUID()).tenantId(tenantId)
            .email("nimal@oak.test").displayName("Nimal").build();
    private final User other = User.builder().userId(UUID.randomUUID()).tenantId(tenantId)
            .email("saman@oak.test").displayName("Saman").build();

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        when(notificationService.notify(any())).thenReturn(List.of(UUID.randomUUID()));
        workflowMailer = new WorkflowNotificationMailer(notificationService);
        commentMailer = new CommentNotificationMailer(notificationService);
    }

    private NotificationRequest captured() {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).notify(captor.capture());
        return captor.getValue();
    }

    @Test
    void taskAssigned_keepsSubjectFactsAndDocumentLink() {
        workflowMailer.sendTaskAssigned(List.of(to), contract, "Sign-off", "the Legal role", null);
        NotificationRequest r = captured();
        assertThat(r.getCategoryCode()).isEqualTo("workflow.task.assigned");
        assertThat(r.getTitle()).isEqualTo("Approval needed: Contract");
        assertThat(r.getBody()).contains("\"Contract\" is waiting for your approval").contains("Sign-off")
                .contains("the Legal role");
        assertThat(r.getLinkUrl()).isEqualTo("/documents/" + documentId);
        assertThat(r.getDocumentId()).isEqualTo(documentId);
    }

    @Test
    void taskAssigned_carriesANeutralVariantWithoutTheTitleOrDocumentLink() {
        workflowMailer.sendTaskAssigned(List.of(to), contract, "Sign-off", null, null);
        NotificationRequest r = captured();
        assertThat(r.getNeutralTitle()).isEqualTo("Approval needed: a document").doesNotContain("Contract");
        assertThat(r.getNeutralBody()).isEqualTo("A document is waiting for your approval at stage \"Sign-off\".")
                .doesNotContain("Contract");
        assertThat(r.getNeutralLinkUrl()).isEqualTo("/tasks").doesNotContain("/documents/");
    }

    @Test
    void oneEvent_isOneServiceCall_forAllItsRecipients() {
        workflowMailer.sendReminder(List.of(to, other), contract, "Sign-off", null);
        NotificationRequest r = captured();   // exactly one call (verify defaults to times(1))
        assertThat(r.getRecipientUserIds()).containsExactlyInAnyOrder(to.getUserId(), other.getUserId());
    }

    @Test
    void anEventWithNoRecipients_makesNoCall() {
        workflowMailer.sendReminder(List.of(), contract, "Sign-off", null);
        verify(notificationService, times(0)).notify(any());
    }

    @Test
    void workflowNotifications_haveNoDedupeKey_soARepeatIsANewNotification() {
        workflowMailer.sendTaskAssigned(List.of(to), contract, "Sign-off", null, null);
        workflowMailer.sendTaskAssigned(List.of(to), contract, "Sign-off", null, null);
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, times(2)).notify(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> assertThat(r.getDedupeKey()).isNull());
    }

    @Test
    void delegation_toNewHolder_saysWhoDelegated() {
        workflowMailer.sendTaskMoved(List.of(to), true, "delegated", contract, "Sign-off", "Nimal", "Delegate1", null);
        NotificationRequest r = captured();
        assertThat(r.getCategoryCode()).isEqualTo("workflow.task.delegated");
        assertThat(r.getTitle()).isEqualTo("Approval needed: Contract");
        assertThat(r.getBody()).contains("Nimal delegated to you");
    }

    @Test
    void escalation_toTarget_saysEscalatedToYou() {
        workflowMailer.sendEscalated(List.of(to), true, contract, "Sign-off", null);
        NotificationRequest r = captured();
        assertThat(r.getCategoryCode()).isEqualTo("workflow.task.escalated");
        assertThat(r.getTitle()).isEqualTo("Escalated: Contract");
        assertThat(r.getBody()).contains("escalated to you");
    }

    @Test
    void escalationFailed_explainsWhy() {
        workflowMailer.sendEscalationFailed(List.of(to), contract, "Sign-off",
                "the escalation target is no longer an active user", UUID.randomUUID());
        NotificationRequest r = captured();
        assertThat(r.getCategoryCode()).isEqualTo("workflow.escalation.failed");
        assertThat(r.getBody()).contains("no longer an active user");
    }

    @Test
    void statusChanged_isTheOptionalDocumentCategory() {
        workflowMailer.sendStatusChanged(List.of(to), contract, "approved", UUID.randomUUID());
        NotificationRequest r = captured();
        assertThat(r.getCategoryCode()).isEqualTo("document.status-changed");
        assertThat(r.getTitle()).isEqualTo("Document approved: Contract");
        assertThat(r.getBody()).contains("a document you own");
    }

    @Test
    void newVersion_namesTheUploaderAndLinksTheDocument() {
        workflowMailer.sendNewVersion(List.of(to), contract, "Dilani", UUID.randomUUID());
        NotificationRequest r = captured();
        assertThat(r.getCategoryCode()).isEqualTo("workflow.new-version");
        assertThat(r.getBody()).contains("Dilani uploaded a new version of \"Contract\"");
        assertThat(r.getLinkUrl()).isEqualTo("/documents/" + documentId);
    }

    @Test
    void mention_linksTheDocument_andTheNeutralVariantKeepsTheCommentButNotTheTitle() {
        commentMailer.sendMentioned(to, "Dilani", documentId, "Contract", "See @Nimal");
        NotificationRequest r = captured();
        assertThat(r.getCategoryCode()).isEqualTo("comment.mention");
        assertThat(r.getTitle()).isEqualTo("Dilani mentioned you: Contract");
        assertThat(r.getBody()).contains("See @Nimal");
        assertThat(r.getLinkUrl()).isEqualTo("/documents/" + documentId);
        assertThat(r.getDocumentId()).isEqualTo(documentId);
        // Decision 10: they always read the comment; decision 7: never the title or a document link.
        assertThat(r.getNeutralTitle()).doesNotContain("Contract");
        assertThat(r.getNeutralBody()).contains("See @Nimal").doesNotContain("Contract");
        assertThat(r.getNeutralLinkUrl()).isEqualTo("/mentions");
    }

    @Test
    void reply_keepsTextAndDocumentLink_neutralHasNoLink() {
        commentMailer.sendReply(to, "Dilani", documentId, "Contract", "Agreed");
        NotificationRequest r = captured();
        assertThat(r.getCategoryCode()).isEqualTo("comment.reply");
        assertThat(r.getTitle()).isEqualTo("Dilani replied: Contract");
        assertThat(r.getBody()).contains("Agreed");
        assertThat(r.getLinkUrl()).isEqualTo("/documents/" + documentId);
        assertThat(r.getNeutralTitle()).doesNotContain("Contract");
        assertThat(r.getNeutralLinkUrl()).isNull();
    }
}
