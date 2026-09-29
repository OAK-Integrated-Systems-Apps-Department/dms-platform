package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Builds and sends the threat-detected emails (VMSBDI FR-003). Follows the
 * {@code PasswordResetService} precedent - {@link JavaMailSender} with a plain
 * {@link SimpleMailMessage} - rather than a generic notification module
 * nobody has specified yet. Throws on a send failure; the caller
 * ({@code ThreatNotificationListener}) decides that a failure is only logged.
 * <p>
 * Never attaches or links the quarantined file itself - administrators get a
 * link to the quarantine review screen, which exposes no bytes either.
 */
@Component
@RequiredArgsConstructor
public class ThreatNotificationMailer {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx");

    private final JavaMailSender mailSender;
    private final ScanProperties scanProperties;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    /** To the person whose upload was blocked. */
    public void sendToUploader(User uploader, QuarantineRecord record) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(scanProperties.getNotifications().getMailFrom());
        message.setTo(uploader.getEmail());
        message.setSubject("Your upload was blocked: a threat was detected in " + record.getOriginalFilename());
        message.setText("Hello " + nameOf(uploader) + ",\n\n"
                + "The file you uploaded, \"" + record.getOriginalFilename() + "\", was scanned for malware and a "
                + "threat was detected (" + record.getThreatName() + ").\n\n"
                + "The file was NOT added to the document repository. It has been isolated in quarantine and your "
                + "administrators have been notified.\n\n"
                + "Detected: " + WHEN.format(record.getCreatedAt()) + "\n\n"
                + "If you believe this is a mistake, contact your administrator - do not try to upload the file again.\n");
        mailSender.send(message);
    }

    /** To an administrator who can review quarantine. */
    public void sendToAdministrator(User admin, QuarantineRecord record, ScanResult scan, User uploader) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(scanProperties.getNotifications().getMailFrom());
        message.setTo(admin.getEmail());
        message.setSubject("[Quarantine] Threat detected: " + record.getThreatName() + " in "
                + record.getOriginalFilename());
        message.setText("Hello " + nameOf(admin) + ",\n\n"
                + "An uploaded file was quarantined by the malware scan.\n\n"
                + "File:        " + record.getOriginalFilename() + "\n"
                + "Threat:      " + record.getThreatName() + "\n"
                + "Uploaded by: " + (uploader != null ? nameOf(uploader) + " <" + uploader.getEmail() + ">" : "unknown") + "\n"
                + "Detected:    " + WHEN.format(record.getCreatedAt()) + "\n"
                + "Size:        " + record.getSizeBytes() + " bytes\n"
                + "SHA-256:     " + record.getFileHash() + "\n"
                + "Engine:      " + describeEngine(scan) + "\n\n"
                + "Review it (release a false positive, or delete it permanently):\n"
                + frontendBaseUrl + "/admin/quarantine/" + record.getQuarantineId() + "\n");
        mailSender.send(message);
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
