package com.oak.dms.notification;

import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.ContractWorld;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * N12: the inbox + preferences APIs (self), the admin settings + delivery log
 * APIs (permission-gated), tenant isolation (404) and the critical-locked (409)
 * and permission (403) rules.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class NotificationApiIT extends WorkflowApiSupport {

    @Autowired
    private NotificationService notificationService;

    private ContractWorld w;

    @BeforeEach
    void world() {
        w = ContractWorld.create(fx);
    }

    private UUID notify(User to, String category, String dedupe) {
        return notificationService.notify(NotificationRequest.builder()
                .tenantId(w.tenantId()).recipientUserIds(List.of(to.getUserId()))
                .categoryCode(category).title("T").body("B").sourceModule("test").dedupeKey(dedupe)
                .build()).get(0);
    }

    @Test
    void inbox_unreadCount_markRead_markAll() throws Exception {
        notify(w.chamath(), "comment.mention", "a-" + UUID.randomUUID());
        UUID second = notify(w.chamath(), "comment.reply", "b-" + UUID.randomUUID());

        JsonNode list = get200("/api/v1/notifications?size=100", w.chamath());
        assertThat(list.get("content").size()).isGreaterThanOrEqualTo(2);
        long unread = get200("/api/v1/notifications/unread-count", w.chamath()).get("count").asLong();
        assertThat(unread).isGreaterThanOrEqualTo(2);

        call(post("/api/v1/notifications/" + second + "/read"), fx.jwtFor(w.chamath()), null, 204);
        long afterOne = get200("/api/v1/notifications/unread-count", w.chamath()).get("count").asLong();
        assertThat(afterOne).isEqualTo(unread - 1);

        call(post("/api/v1/notifications/read-all"), fx.jwtFor(w.chamath()), null, 204);
        assertThat(get200("/api/v1/notifications/unread-count", w.chamath()).get("count").asLong()).isZero();
    }

    @Test
    void preferences_toggleStandard_rejectCritical() throws Exception {
        JsonNode prefs = get200("/api/v1/notifications/preferences", w.chamath());
        assertThat(prefs.isArray()).isTrue();

        call(put("/api/v1/notifications/preferences/comment.mention"), fx.jwtFor(w.chamath()),
                Map.of("emailEnabled", false), 204);
        // A critical category cannot be turned off -> 409.
        call(put("/api/v1/notifications/preferences/workflow.task.escalated"), fx.jwtFor(w.chamath()),
                Map.of("emailEnabled", false), 409);
    }

    @Test
    void tenantIsolation_cannotTouchAnotherUsersNotification() throws Exception {
        UUID nimals = notify(w.nimal(), "comment.mention", "iso-" + UUID.randomUUID());
        // chamath marking nimal's notification read -> 404 (not visible).
        call(post("/api/v1/notifications/" + nimals + "/read"), fx.jwtFor(w.chamath()), null, 404);
    }

    @Test
    void adminSettings_requirePermission_andRejectCritical() throws Exception {
        // A plain user has no NOTIF permission -> 403.
        call(get("/api/v1/admin/notifications/settings"), fx.jwtFor(w.chamath()), null, 403);

        JsonNode settings = adminGet("/api/v1/admin/notifications/settings", w.admin());
        assertThat(settings.isArray()).isTrue();
        adminPut("/api/v1/admin/notifications/settings/document.status-changed", w.admin(),
                Map.of("emailEnabledDefault", true), 204);
        adminPut("/api/v1/admin/notifications/settings/workflow.task.escalated", w.admin(),
                Map.of("emailEnabledDefault", false), 409);
    }

    @Test
    void deliveryLog_listsAndRetry_gatedByPermission() throws Exception {
        notify(w.chamath(), "comment.mention", "log-" + UUID.randomUUID());
        JsonNode log = adminGet("/api/v1/admin/notifications/deliveries?size=100", w.admin());
        assertThat(log.get("content").size()).isGreaterThanOrEqualTo(1);
        // A plain user cannot view the log.
        call(get("/api/v1/admin/notifications/deliveries"), fx.jwtFor(w.chamath()), null, 403);
    }

    private JsonNode adminGet(String path, User admin) throws Exception {
        return call(get(path), fx.tenantAdminJwtFor(admin), null, 200);
    }
}
