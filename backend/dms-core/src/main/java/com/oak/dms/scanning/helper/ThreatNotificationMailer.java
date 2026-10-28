package com.oak.dms.scanning.helper;

import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds the threat-detected notifications (VMSBDI FR-003) and routes them
 * through the shared {@link NotificationService} (3.2, N8) as the critical
 * {@code security.threat.*} categories (email always on). Never attaches or
 * links the quarantined file itself; administrators get a link to the review
 * screen, the uploader none.
 */
@Component
@RequiredArgsConstructor
public class ThreatNotificationMailer {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx");
    private static final String MODULE = "scanning";

    private final NotificationService notificationService;

    public void sendToUploader(User uploader, QuarantineRecord record) {
        String body = "The file you uploaded, \"" + record.getOriginalFilename() + "\", was scanned for malware and a "
                + "threat was detected (" + record.getThreatName() + ").\n\n"
                + "The file was NOT added to the document repository. It has been isolated in quarantine and your "
                + "administrators have been notified.\n\n"
                + "Detected: " + WHEN.format(record.getCreatedAt()) + "\n\n"
                + "If you believe this is a mistake, contact your administrator - do not try to upload the file again.";
        notificationService.notify(NotificationRequest.builder()
                .tenantId(uploader.getTenantId())
                .recipientUserIds(List.of(uploader.getUserId()))
                .categoryCode("security.threat.uploader")
                .title("Your upload was blocked: a threat was detected in " + record.getOriginalFilename())
                .body(body)
                .objectType("quarantine")
                .objectId(record.getQuarantineId())
                .sourceModule(MODULE)
                .dedupeKey("threat-uploader:" + record.getQuarantineId())
                .build());
    }

    public void sendToAdministrator(User admin, QuarantineRecord record, ScanResult scan, User uploader) {
        String body = "An uploaded file was quarantined by the malware scan.\n\n"
                + "File:        " + record.getOriginalFilename() + "\n"
                + "Threat:      " + record.getThreatName() + "\n"
                + "Uploaded by: " + (uploader != null ? nameOf(uploader) + " <" + uploader.getEmail() + ">" : "unknown") + "\n"
                + "Detected:    " + WHEN.format(record.getCreatedAt()) + "\n"
                + "Size:        " + record.getSizeBytes() + " bytes\n"
                + "SHA-256:     " + record.getFileHash() + "\n"
                + "Engine:      " + describeEngine(scan);
        notificationService.notify(NotificationRequest.builder()
                .tenantId(admin.getTenantId())
                .recipientUserIds(List.of(admin.getUserId()))
                .categoryCode("security.threat.admin")
                .title("[Quarantine] Threat detected: " + record.getThreatName() + " in " + record.getOriginalFilename())
                .body(body)
                .linkUrl("/admin/quarantine/" + record.getQuarantineId())
                .objectType("quarantine")
                .objectId(record.getQuarantineId())
                .sourceModule(MODULE)
                .dedupeKey("threat-admin:" + record.getQuarantineId() + ":" + admin.getUserId())
                .build());
    }

    private static String describeEngine(ScanResult scan) {
        if (scan == null || scan.getEngineName() == null) {
            return "unknown";
        }
        StringBuilder engine = new StringBuilder(scan.getEngineName());
        if (scan.getEngineVersion() != null) {
            engine.append(' ').append(scan.getEngineVersion());
        }
        if (scan.getSignatureVersion() != null) {
            engine.append(", signatures ").append(scan.getSignatureVersion());
        }
        return engine.toString();
    }

    private static String nameOf(User user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank()
                ? user.getDisplayName()
                : user.getEmail();
    }
}
