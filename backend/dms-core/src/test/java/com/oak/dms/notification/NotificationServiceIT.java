package com.oak.dms.notification;

import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N3/N6: the transactional write of inbox + delivery rows, de-duplication,
 * in-app always created, and email pending/skipped per the resolved
 * preference (critical always on; optional off by default; no-email skips).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class NotificationServiceIT {

    @Autowired
    private NotificationService service;
    @Autowired
    private WorkflowFixtures fx;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID tenant;
    private User alice;

    @BeforeEach
    void world() {
        tenant = fx.tenant();
        alice = fx.user(tenant, "Alice");
    }

    private NotificationRequest.NotificationRequestBuilder base(String category) {
        return NotificationRequest.builder()
                .tenantId(tenant)
                .recipientUserIds(List.of(alice.getUserId()))
                .categoryCode(category)
                .title("Title")
                .body("Body")
                .linkUrl("/x")
                .sourceModule("test");
    }

    private long deliveries(UUID notificationId, String channel, String status) {
        return fx.count("select count(*) from notification_deliveries d join notifications n"
                + " on n.notification_id = d.notification_id where n.notification_id = ?"
                + " and d.channel = ? and d.status = ?", notificationId, channel, status);
    }

    @Test
    void standard_createsInboxInAppSentAndEmailPending() {
        UUID id = service.notify(base("comment.mention").dedupeKey("k1").build()).get(0);

        assertThat(fx.count("select count(*) from notifications where notification_id = ?", id)).isEqualTo(1);
        assertThat(deliveries(id, "in_app", "sent")).isEqualTo(1);
        assertThat(deliveries(id, "email", "pending")).isEqualTo(1);
        assertThat(fx.count("select count(*) from notification_deliveries d join notifications n on"
                + " n.notification_id = d.notification_id where n.notification_id = ? and d.channel = 'email'"
                + " and d.destination = ?", id, alice.getEmail())).isEqualTo(1);
        // In-app delivery is logged as a sent attempt immediately.
        assertThat(fx.count("select count(*) from notification_delivery_attempts a join notifications n"
                + " on n.notification_id = a.notification_id where n.notification_id = ? and a.channel = 'in_app'"
                + " and a.outcome = 'sent'", id)).isEqualTo(1);
    }

    @Test
    void sameDedupeKey_isIdempotent() {
        List<UUID> first = service.notify(base("comment.mention").dedupeKey("dup").build());
        List<UUID> second = service.notify(base("comment.mention").dedupeKey("dup").build());

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        assertThat(fx.count("select count(*) from notifications where recipient_user_id = ? and dedupe_key = 'dup'",
                alice.getUserId())).isEqualTo(1);
    }

    @Test
    void optionalCategory_defaultsEmailOff_butInAppStands() {
        UUID id = service.notify(base("document.status-changed").dedupeKey("opt").build()).get(0);

        assertThat(deliveries(id, "in_app", "sent")).isEqualTo(1);
        assertThat(deliveries(id, "email", "skipped")).isEqualTo(1);
        assertThat(fx.count("select count(*) from notification_deliveries d join notifications n on"
                + " n.notification_id = d.notification_id where n.notification_id = ? and d.channel = 'email'"
                + " and d.skip_reason = 'email_disabled'", id)).isEqualTo(1);
    }

    @Test
    void criticalCategory_emailCannotBeDisabledByUser() {
        // User tries to turn off a critical category; resolver must ignore it.
        fx.jdbc().update("insert into user_notification_preferences (tenant_id, user_id, category_code, channel, enabled)"
                + " values (?, ?, 'workflow.task.escalated', 'email', false)", tenant, alice.getUserId());

        UUID id = service.notify(base("workflow.task.escalated").dedupeKey("crit").build()).get(0);
        assertThat(deliveries(id, "email", "pending")).isEqualTo(1);
    }

    @Test
    void standardCategory_userCanTurnEmailOff() {
        fx.jdbc().update("insert into user_notification_preferences (tenant_id, user_id, category_code, channel, enabled)"
                + " values (?, ?, 'comment.mention', 'email', false)", tenant, alice.getUserId());

        UUID id = service.notify(base("comment.mention").dedupeKey("off").build()).get(0);
        assertThat(deliveries(id, "email", "skipped")).isEqualTo(1);
    }

    @Test
    void tenantDefault_canTurnOptionalEmailOn() {
        fx.jdbc().update("insert into tenant_notification_settings (tenant_id, category_code, email_enabled_default)"
                + " values (?, 'document.status-changed', true)", tenant);

        UUID id = service.notify(base("document.status-changed").dedupeKey("tdef").build()).get(0);
        assertThat(deliveries(id, "email", "pending")).isEqualTo(1);
    }

    @Test
    void noEmailAddress_skipsEmailWithReason_butInAppStands() {
        User ghost = fx.user(tenant, "Ghost");
        fx.jdbc().update("update users set email = '' where user_id = ?", ghost.getUserId());

        UUID id = service.notify(base("comment.mention").recipientUserIds(List.of(ghost.getUserId()))
                .dedupeKey("noemail").build()).get(0);
        assertThat(deliveries(id, "in_app", "sent")).isEqualTo(1);
        assertThat(fx.count("select count(*) from notification_deliveries d join notifications n on"
                + " n.notification_id = d.notification_id where n.notification_id = ? and d.channel = 'email'"
                + " and d.skip_reason = 'no_email'", id)).isEqualTo(1);
        // N7: the admin is flagged in the main audit trail.
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ?"
                + " and action = 'NOTIFICATION_RECIPIENT_NO_EMAIL'", tenant)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void notify_rollsBackWithTheCallerTransaction() {
        TransactionTemplate txt = new TransactionTemplate(transactionManager);
        txt.executeWithoutResult(status -> {
            service.notify(base("comment.mention").dedupeKey("rollback").build());
            status.setRollbackOnly();
        });
        assertThat(fx.count("select count(*) from notifications where recipient_user_id = ? and dedupe_key = 'rollback'",
                alice.getUserId())).isZero();
    }
}
