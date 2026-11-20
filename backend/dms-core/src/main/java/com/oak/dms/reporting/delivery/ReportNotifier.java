package com.oak.dms.reporting.delivery;

import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * The three notices reports send, through the platform's one notification service. {@code report.ready} follows each person's own
 * notification preferences; {@code report.failed} and {@code report.schedule-paused} are mandatory (V140). A notice carries a link to
 * the signed-in download page - never the file, never a signed URL, and never a filter value or a row.
 * <p>
 * A notification that cannot be written must not undo the work it reports on, so every call logs and carries on.
 */
@Slf4j
@Component
public class ReportNotifier {

    public static final String READY = "report.ready";
    public static final String FAILED = "report.failed";
    public static final String SCHEDULE_PAUSED = "report.schedule-paused";
    public static final String SCHEDULE_ASSIGNED = "report.schedule-assigned";
    private static final String MODULE = "reports";

    private final NotificationService notifications;

    public ReportNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** A report is ready; the link opens the download page. */
    public boolean ready(UUID tenantId, UUID userId, UUID runId, String title) {
        return ready(tenantId, userId, runId, title, false);
    }

    /** @param emailedSeparately the email channel sends this person the report itself, so the platform must not email it again */
    public boolean ready(UUID tenantId, UUID userId, UUID runId, String title, boolean emailedSeparately) {
        return send(tenantId, java.util.List.of(userId), READY, "Report ready: " + title,
                "Your report is ready to download. It is kept for a limited time.", "/reports/runs/" + runId, runId,
                "report-ready:" + runId, emailedSeparately);
    }

    /** A scheduled report with no data that is still delivered, saying so. */
    public boolean readyEmpty(UUID tenantId, UUID userId, UUID runId, String title) {
        return readyEmpty(tenantId, userId, runId, title, false);
    }

    public boolean readyEmpty(UUID tenantId, UUID userId, UUID runId, String title, boolean emailedSeparately) {
        return send(tenantId, java.util.List.of(userId), READY, "Report ready: " + title,
                "No data found: nothing matched this report's filters under your access.", "/reports/runs/" + runId, runId,
                "report-ready:" + runId, emailedSeparately);
    }

    /** One summary for a run: it failed, or some of its deliveries did. {@code reason} is user-safe text, never a stack or an address. */
    public void failed(UUID tenantId, Collection<UUID> userIds, UUID runId, String title, String reason, String dedupe) {
        send(tenantId, userIds, FAILED, "Report problem: " + title, reason, "/reports/runs/" + runId, runId, dedupe, false);
    }

    /** The new owner of a reassigned schedule is told it is theirs now. No names or addresses in the text. */
    public void scheduleAssigned(UUID tenantId, UUID newOwner, UUID scheduleId, String name) {
        send(tenantId, java.util.List.of(newOwner), SCHEDULE_ASSIGNED, "Report schedule assigned to you: " + name,
                "A report schedule was handed over to you. It now runs as you, with your access, and its reports are delivered under the settings saved with it.",
                "/reports?tab=schedules", scheduleId, null, false);
    }

    public void schedulePaused(UUID tenantId, Collection<UUID> userIds, UUID scheduleId, String name, String reason) {
        // No de-duplication key: a pause happens once per transition, and pausing again after a resume is news again.
        send(tenantId, userIds, SCHEDULE_PAUSED, "Report schedule paused: " + name, reason, "/reports/schedules", scheduleId, null, false);
    }

    private boolean send(UUID tenantId, Collection<UUID> recipients, String category, String title, String body, String link, UUID objectId,
                      String dedupeKey, boolean suppressEmail) {
        if (recipients.isEmpty()) {
            return true; // nobody to tell is not a failure
        }
        try {
            notifications.notify(NotificationRequest.builder()
                    .tenantId(tenantId)
                    .recipientUserIds(recipients)
                    .categoryCode(category)
                    .title(title)
                    .body(body)
                    .linkUrl(link)
                    .objectType(AuditObjectTypes.REPORT_RUN)
                    .objectId(objectId)
                    .sourceModule(MODULE)
                    .dedupeKey(dedupeKey)
                    .suppressEmail(suppressEmail)
                    .suppressEmailReason("sent_by_other_channel")
                    .build());
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not send the {} notification for {}", category, objectId, e);
            return false;
        }
    }
}
