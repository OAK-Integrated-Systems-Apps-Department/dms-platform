package com.oak.dms.notification;

import com.oak.dms.notification.dispatch.NotificationDispatcher;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.notification.support.NotificationMailSupport;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N4/N5: the dispatcher sends a pending email via the HTML channel, logs the
 * attempt, and on repeated SMTP failure retries with backoff up to max_attempts
 * then marks the delivery failed while the in-app entry stands.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class NotificationDispatcherIT extends NotificationMailSupport {

    @Autowired
    private NotificationService service;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private WorkflowFixtures fx;

    private UUID notify(UUID tenant, User to, String key) {
        return service.notify(NotificationRequest.builder()
                .tenantId(tenant)
                .recipientUserIds(List.of(to.getUserId()))
                .categoryCode("comment.mention")
                .title("You were mentioned: Project plan")
                .body("Dilani mentioned you in a comment.")
                .linkUrl("/documents/abc")
                .sourceModule("documents")
                .dedupeKey(key)
                .build()).get(0);
    }

    private long sentCount(UUID notificationId) {
        return fx.count("select count(*) from notification_deliveries d join notifications n"
                + " on n.notification_id = d.notification_id where n.notification_id = ? and d.channel = 'email'"
                + " and d.status = 'sent' and d.sent_at is not null", notificationId);
    }

    @Test
    void sendsHtmlEmail_marksSent_andLogsAttempt() throws Exception {
        UUID tenant = fx.tenant();
        User alice = fx.user(tenant, "Alice");
        UUID id = notify(tenant, alice, "ok");

        // runOnce() claims globally and the shared test DB may hold other tests' pending rows,
        // so drain until OUR delivery is sent, then verify it precisely via the DB.
        for (int i = 0; i < 15 && sentCount(id) == 0; i++) {
            dispatcher.runOnce();
            Thread.sleep(100);
        }
        assertThat(waitForMail(1, 5000)).isTrue();

        // Authoritative "sent exactly once" check, isolated by notification id.
        assertThat(sentCount(id)).isEqualTo(1);

        var mail = mailTo(alice.getEmail());
        assertThat(mail).isNotEmpty();
        assertThat(subjectOf(mail.get(0))).isEqualTo("You were mentioned: Project plan");
        assertThat(bodyOf(mail.get(0))).contains("Dilani mentioned you").contains("/documents/abc");
        assertThat(fx.count("select count(*) from notification_delivery_attempts a join notifications n"
                + " on n.notification_id = a.notification_id where n.notification_id = ? and a.channel = 'email'"
                + " and a.outcome = 'sent'", id)).isEqualTo(1);
    }

    @Test
    void retriesWithBackoff_thenMarksFailed_butInAppStands() throws Exception {
        UUID tenant = fx.tenant();
        User bob = fx.user(tenant, "Bob");
        UUID id = notify(tenant, bob, "fail");

        // SMTP down -> every send fails. Restart afterwards so other tests are unaffected.
        greenMail.stop();
        try {
            for (int i = 0; i < 25; i++) {
                dispatcher.runOnce();
                long failed = fx.count("select count(*) from notification_deliveries d join notifications n"
                        + " on n.notification_id = d.notification_id where n.notification_id = ? and d.channel = 'email'"
                        + " and d.status = 'failed'", id);
                if (failed == 1) {
                    break;
                }
                Thread.sleep(250);
            }
        } finally {
            greenMail.start();
        }

        // Final failure after max_attempts (3 in tests); every attempt logged as failed.
        assertThat(fx.count("select count(*) from notification_deliveries d join notifications n"
                + " on n.notification_id = d.notification_id where n.notification_id = ? and d.channel = 'email'"
                + " and d.status = 'failed' and d.attempt_count = 3", id)).isEqualTo(1);
        assertThat(fx.count("select count(*) from notification_delivery_attempts a join notifications n"
                + " on n.notification_id = a.notification_id where n.notification_id = ? and a.channel = 'email'"
                + " and a.outcome = 'failed'", id)).isEqualTo(3);
        // The in-app entry stands regardless.
        assertThat(fx.count("select count(*) from notification_deliveries d join notifications n"
                + " on n.notification_id = d.notification_id where n.notification_id = ? and d.channel = 'in_app'"
                + " and d.status = 'sent'", id)).isEqualTo(1);
        // N7: the final failure is recorded in the main audit trail.
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ?"
                + " and action = 'NOTIFICATION_DELIVERY_FAILED'", tenant)).isGreaterThanOrEqualTo(1);
    }
}
