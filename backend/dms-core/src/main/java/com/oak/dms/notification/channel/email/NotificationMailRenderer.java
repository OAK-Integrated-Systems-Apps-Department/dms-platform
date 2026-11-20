package com.oak.dms.notification.channel.email;

import com.oak.dms.common.config.BrandingProperties;
import com.oak.dms.common.mail.BrandedMailLayout;
import com.oak.dms.notification.entity.Notification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders a notification into an HTML email with a plain-text alternative
 * (3.2, N5, Deviation #2). One branded layout ({@link BrandedMailLayout}) for every category; the subject
 * is the notification title (which carries the key facts, e.g. the document
 * name), the body is its text, and a button links to {@code linkUrl} when the
 * recipient may open it (N9 decides that per recipient before this runs).
 */
@Component
public class NotificationMailRenderer {

    private final BrandedMailLayout layout;
    private final String frontendBaseUrl;

    public NotificationMailRenderer(BrandingProperties branding,
                                    @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.layout = new BrandedMailLayout(branding);
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public RenderedMail render(Notification n) {
        String subject = n.getTitle();
        String absoluteLink = absolute(n.getLinkUrl());
        String body = n.getBody() == null ? "" : n.getBody();

        boolean shared = "document.shared".equals(n.getCategoryCode());
        // "Shared with you": the sharer's note (after a blank line) is its own quoted paragraph, and the button
        // names what it opens, like a SharePoint share mail.
        List<String> paragraphs = shared ? sharedParagraphs(body) : List.of(body);
        BrandedMailLayout.Content content = BrandedMailLayout.Content.of(subject, paragraphs);
        if (absoluteLink != null) {
            content = content.withButton(shared ? "folder".equals(n.getObjectType()) ? "Open folder" : "Open document"
                    : "Open", absoluteLink);
        }
        return new RenderedMail(subject, layout.html(content), layout.text(content));
    }

    private static List<String> sharedParagraphs(String body) {
        String[] parts = body.split("\n\n", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return List.of(parts[0]);
        }
        return List.of(parts[0], "\"" + parts[1].strip() + "\"");
    }

    private String absolute(String linkUrl) {
        if (linkUrl == null || linkUrl.isBlank()) {
            return null;
        }
        if (linkUrl.startsWith("http://") || linkUrl.startsWith("https://")) {
            return linkUrl;
        }
        String base = frontendBaseUrl == null ? "" : frontendBaseUrl.replaceAll("/+$", "");
        return base + (linkUrl.startsWith("/") ? linkUrl : "/" + linkUrl);
    }

    /** A rendered email: subject, HTML body and the plain-text alternative. */
    public record RenderedMail(String subject, String html, String text) {
    }
}
