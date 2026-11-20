package com.oak.dms.reporting.delivery;

import com.oak.dms.common.config.BrandingProperties;
import com.oak.dms.common.mail.BrandedMailLayout;
import com.oak.dms.common.mail.BrandedMailSender;
import com.oak.dms.notification.config.NotificationProperties;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.export.ReportExporter;
import com.oak.dms.reporting.run.ReportRunService;
import com.oak.dms.reporting.schedule.ReportPeople;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamSource;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An email, branded like every other one. Inside the organisation the default is a LINK to the signed-in download page; the file is
 * attached only when the schedule asks, the organisation allows attachments, and it fits under {@code max_attachment_mb} - otherwise
 * it falls back to the link and records why. An external address has no sign-in, so a link would lead nowhere: it gets the file
 * attached or nothing, and only if the organisation still allows external recipients and the domain is still on its list.
 * <p>
 * The message says what the report is and how to get it - never filter values, rows or other people's names.
 */
@Slf4j
@Component
public class EmailReportDelivery implements ReportDeliveryChannel {

    private static final long MB = 1024L * 1024L;

    private final BrandedMailSender mailSender;
    private final BrandedMailLayout layout;
    private final NotificationProperties notificationProperties;
    private final ReportPeople people;
    private final StorageLocationRepository locations;
    private final ObjectStorageServiceFactory storage;
    private final Map<String, ReportExporter> exporters;
    private final String frontendBaseUrl;

    public EmailReportDelivery(BrandedMailSender mailSender, BrandingProperties branding, NotificationProperties notificationProperties,
                               ReportPeople people, StorageLocationRepository locations, ObjectStorageServiceFactory storage,
                               List<ReportExporter> exporters, @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.mailSender = mailSender;
        this.layout = new BrandedMailLayout(branding);
        this.notificationProperties = notificationProperties;
        this.people = people;
        this.locations = locations;
        this.storage = storage;
        this.exporters = exporters.stream().collect(Collectors.toMap(ReportExporter::format, Function.identity()));
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public Outcome deliver(Delivery d) {
        String address = d.target().external() ? d.target().address() : addressOf(d);
        if (address == null) {
            return Outcome.failed("NO_EMAIL_ADDRESS");
        }
        if (d.target().external()) {
            Outcome refused = externalRefusal(d, address);
            if (refused != null) {
                return refused;
            }
        }

        String mode = "link";
        String note = null;
        List<BrandedMailSender.Attachment> attachments = List.of();
        if (d.hasFile() && ("attachment".equals(d.requestedMode()) || d.target().external())) {
            String why = attachmentRefusal(d);
            if (why == null) {
                attachments = List.of(attachment(d));
                mode = "attachment";
            } else if (d.target().external()) {
                return Outcome.failed("ATTACHMENT_" + why); // nothing to fall back to: there is no link an outsider could open
            } else {
                note = "LINK_FALLBACK_" + why;
            }
        }

        try {
            BrandedMailLayout.Content content = content(d, mode);
            mailSender.send(notificationProperties.getMailFrom(), address, subject(d), layout.html(content), layout.text(content),
                    attachments);
            return Outcome.sent(mode, note);
        } catch (MailException e) {
            log.warn("Report email for run {} was not sent: {}", d.run().getRunId(), e.getMessage());
            return Outcome.retry("MAIL_SEND_FAILED");
        } catch (RuntimeException e) {
            log.warn("Report email for run {} could not be prepared", d.run().getRunId(), e);
            return Outcome.retry("MAIL_PREPARE_FAILED");
        }
    }

    private String addressOf(Delivery d) {
        return people.activePerson(d.run().getTenantId(), d.target().userId())
                .map(ReportPeople.Person::email).filter(e -> !e.isBlank()).orElse(null);
    }

    /** Re-checked at send time: the organisation may have changed its mind since the schedule was saved. */
    private Outcome externalRefusal(Delivery d, String address) {
        EffectiveReportSettings s = d.settings();
        String domain = address.substring(address.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        boolean listed = s.externalDomainAllowlist().stream().anyMatch(a -> a.equalsIgnoreCase(domain));
        if (!s.allowExternalRecipients() || !listed) {
            return Outcome.failed("EXTERNAL_NOT_ALLOWED");
        }
        return null;
    }

    /** Why the file cannot be attached, or null if it can. The words become part of a code, so they are fixed. */
    private String attachmentRefusal(Delivery d) {
        EffectiveReportSettings s = d.settings();
        if (!s.allowAttachments()) {
            return "NOT_ALLOWED";
        }
        Long size = d.run().getFileSizeBytes();
        if (size == null || size > (long) s.maxAttachmentMb() * MB) {
            return "TOO_LARGE";
        }
        return null;
    }

    private BrandedMailSender.Attachment attachment(Delivery d) {
        ReportRun run = d.run();
        ReportExporter exporter = exporters.get(run.getFormat());
        StorageLocation location = locations.findByStorageLocationIdAndDeletedFalse(run.getStorageLocationId()).orElseThrow();
        // Opened when the mail is built, and again if the mail library asks for it again.
        InputStreamSource source = () -> {
            DownloadedObject object = storage.resolve(location).download(location, run.getFileObjectKey());
            return object.content();
        };
        return new BrandedMailSender.Attachment(ReportRunService.filename(run, exporter.extension()), exporter.contentType(), source);
    }

    private static String subject(Delivery d) {
        return "Report: " + d.title();
    }

    private BrandedMailLayout.Content content(Delivery d, String mode) {
        if (!d.hasFile()) {
            return BrandedMailLayout.Content.of(d.title(), List.of("No data found: nothing matched this report's filters."));
        }
        if ("attachment".equals(mode)) {
            List<String> paragraphs = new java.util.ArrayList<>(List.of("Your scheduled report is attached."));
            if (d.run().isTruncated()) {
                paragraphs.add("It was cut at the organisation's row limit, so it does not hold every matching document.");
            }
            BrandedMailLayout.Content content = BrandedMailLayout.Content.of(d.title(), paragraphs);
            return d.target().external() ? content.withNote("This report was sent to you by an organisation you are not a member of.")
                    : content;
        }
        return BrandedMailLayout.Content.of(d.title(), List.of("Your report is ready to download. It is kept for a limited time."))
                .withButton("Open report", frontendBaseUrl.replaceAll("/+$", "") + "/reports/runs/" + d.run().getRunId())
                .withNote("You will be asked to sign in. The report is only available to you.");
    }
}
