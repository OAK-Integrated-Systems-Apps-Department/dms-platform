package com.oak.dms.workflow.helper;

import com.oak.dms.comment.helper.CommentNotificationMailer;
import com.oak.dms.user.User;
import com.oak.dms.workflow.config.WorkflowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Phase 2 mail content (S9): wording, recipients' links, and the no-link rule for mentions without access. */
class WorkflowPhase2MailerTest {

    private JavaMailSender sender;
    private WorkflowNotificationMailer workflowMailer;
    private CommentNotificationMailer commentMailer;
    private final User to = User.builder().userId(UUID.randomUUID()).email("nimal@oak.test").displayName("Nimal")
            .build();

    @BeforeEach
    void setUp() {
        fresh();
    }

    /** New mock sender and mailers (the sender field is final). */
    private void fresh() {
        sender = mock(JavaMailSender.class);
        WorkflowProperties properties = new WorkflowProperties();
        workflowMailer = new WorkflowNotificationMailer(sender, properties);
        commentMailer = new CommentNotificationMailer(sender, properties);
        ReflectionTestUtils.setField(workflowMailer, "frontendBaseUrl", "https://dms.test");
        ReflectionTestUtils.setField(commentMailer, "frontendBaseUrl", "https://dms.test");
    }

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void delegation_tellsTheNewHolderWhoDelegated_andTheOldOneTheyAreDone() {
        workflowMailer.sendTaskMoved(to, true, "delegated", "Contract 114", "Sign-off", "Kumari", "Nimal", "On leave");
        SimpleMailMessage mail = sent();
        assertThat(mail.getSubject()).isEqualTo("Approval needed: Contract 114");
        assertThat(mail.getText()).contains("Kumari delegated to you").contains("Note: On leave")
                .contains("https://dms.test/tasks");

        fresh();
        workflowMailer.sendTaskMoved(to, false, "reassigned", "Contract 114", "Sign-off", null, "Asanka", "Away");
        mail = sent();
        assertThat(mail.getSubject()).isEqualTo("Task reassigned: Contract 114");
        assertThat(mail.getText()).contains("reassigned to Asanka").contains("Reason: Away");
    }

    @Test
    void infoRequest_linksToRequestsForMe() {
        workflowMailer.sendInfoRequested(to, "Contract 114", "Saman", "Please send the annex",
                OffsetDateTime.parse("2026-10-07T10:00:00Z"));
        SimpleMailMessage mail = sent();
        assertThat(mail.getTo()).containsExactly("nimal@oak.test");
        assertThat(mail.getText()).contains("Saman needs information").contains("Please send the annex")
                .contains("https://dms.test/tasks?view=requests").contains("2026-10-07");
    }

    @Test
    void mentionWithoutAccess_hasTheTextButNoDocumentLink() {
        UUID documentId = UUID.randomUUID();
        commentMailer.sendMentioned(to, "Chamath", "Contract 114", "Please check @Nimal", false, documentId);
        SimpleMailMessage mail = sent();
        assertThat(mail.getText()).contains("Please check @Nimal").contains("https://dms.test/mentions")
                .doesNotContain("/documents/");

        fresh();
        commentMailer.sendMentioned(to, "Chamath", "Contract 114", "hi", true, documentId);
        assertThat(sent().getText()).contains("https://dms.test/documents/" + documentId);
    }
}
