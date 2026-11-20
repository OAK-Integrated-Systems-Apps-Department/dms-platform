package com.oak.dms.sharing;

import com.oak.dms.common.config.BrandingProperties;
import com.oak.dms.common.mail.BrandedMailLayout;
import com.oak.dms.common.mail.BrandedMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The emails of external sharing: a share link, and a guest's invitation or new access (workflow step 3). Best
 * effort - the link or grant already exists when this runs, so a mail failure is logged and reported back as
 * "not sent" for the sharer to pass the link on themselves, never thrown. A password is never put in a mail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SharingMailer {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH);

    private final BrandedMailSender mailSender;
    private final BrandingProperties branding;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Value("${dms.sharing.mail-from}")
    private String mailFrom;

    String linkUrl(String token) {
        return trimmed(frontendBaseUrl) + "/s/" + token;
    }

    String portalUrl(UUID tenantId) {
        return trimmed(frontendBaseUrl) + "/portal?org=" + tenantId;
    }

    String inviteUrl(UUID tenantId, String token) {
        return trimmed(frontendBaseUrl) + "/portal/accept?token=" + token + "&org=" + tenantId;
    }

    boolean sendLink(String to, String sharedBy, String itemName, String organisation, String url,
                     OffsetDateTime expiresAt, boolean passwordProtected, boolean canDownload, String message) {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(sharedBy + " (" + organisation + ") shared \"" + itemName + "\" with you.");
        if (message != null && !message.isBlank()) {
            paragraphs.add("\"" + message.strip() + "\"");
        }
        paragraphs.add("You can " + (canDownload ? "view and download" : "view") + " it until "
                + WHEN.format(expiresAt.withOffsetSameInstant(ZoneOffset.UTC)) + ".");
        BrandedMailLayout.Content content = BrandedMailLayout.Content.of(sharedBy + " shared something with you", paragraphs)
                .withButton("Open " + itemName, url);
        if (passwordProtected) {
            content = content.withNote("This link is password protected. The sender will give you the password separately.");
        }
        return send(to, sharedBy + " shared \"" + itemName + "\" with you", content);
    }

    boolean sendInvite(String to, String invitedBy, String organisation, String acceptUrl) {
        String product = branding.getProductName();
        BrandedMailLayout.Content content = BrandedMailLayout.Content.of("You're invited to " + organisation, List.of(
                        invitedBy + " invited you to the " + organisation + " client portal on " + product + ", "
                                + "where you can open the documents and folders shared with you.",
                        "Set a password to activate your account. This invitation lasts 7 days."))
                .withButton("Set your password", acceptUrl);
        return send(to, "You're invited to the " + organisation + " client portal", content);
    }

    boolean sendAccessGranted(String to, String grantedBy, String organisation, String itemName, String portalUrl) {
        BrandedMailLayout.Content content = BrandedMailLayout.Content.of("New document shared with you", List.of(
                        grantedBy + " (" + organisation + ") gave you access to \"" + itemName + "\"."))
                .withButton("Open the portal", portalUrl);
        return send(to, grantedBy + " shared \"" + itemName + "\" with you", content);
    }

    private boolean send(String to, String subject, BrandedMailLayout.Content content) {
        if (to == null || to.isBlank()) {
            return false;
        }
        try {
            BrandedMailLayout layout = new BrandedMailLayout(branding);
            mailSender.send(mailFrom, to, subject, layout.html(content), layout.text(content));
            return true;
        } catch (Exception e) {
            log.warn("Could not send a sharing email: {}", e.getMessage());
            return false;
        }
    }

    private static String trimmed(String base) {
        return base != null && base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
