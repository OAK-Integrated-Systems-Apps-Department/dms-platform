package com.oak.dms.notification.channel.email;

import com.oak.dms.common.config.BrandingProperties;
import com.oak.dms.notification.entity.Notification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders a notification into an HTML email with a plain-text alternative
 * (3.2, N5, Deviation #2). One branded layout for every category; the subject
 * is the notification title (which carries the key facts, e.g. the document
 * name), the body is its text, and a button links to {@code linkUrl} when the
 * recipient may open it (N9 decides that per recipient before this runs).
 */
@Component
public class NotificationMailRenderer {

    private final BrandingProperties branding;
    private final String frontendBaseUrl;

    public NotificationMailRenderer(BrandingProperties branding,
                                    @Value("${app.frontend-base-url}") String frontendBaseUrl) {
        this.branding = branding;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public RenderedMail render(Notification n) {
        String subject = n.getTitle();
        String absoluteLink = absolute(n.getLinkUrl());
        String body = n.getBody() == null ? "" : n.getBody();

        String html = html(subject, body, absoluteLink);
        String text = text(subject, body, absoluteLink);
        return new RenderedMail(subject, html, text);
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

    private String html(String subject, String body, String link) {
        String product = esc(branding.getProductName());
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><body style=\"margin:0;padding:0;background:#f4f5f7;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" style=\"padding:24px;\">")
                .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"max-width:600px;background:#ffffff;border-radius:8px;font-family:Arial,Helvetica,sans-serif;color:#1a1a1a;\">")
                .append("<tr><td style=\"padding:24px 28px 8px 28px;font-size:18px;font-weight:bold;\">").append(esc(subject)).append("</td></tr>")
                .append("<tr><td style=\"padding:8px 28px 16px 28px;font-size:14px;line-height:1.6;color:#333;\">")
                .append(esc(body).replace("\n", "<br/>")).append("</td></tr>");
        if (link != null) {
            sb.append("<tr><td style=\"padding:8px 28px 24px 28px;\">")
                    .append("<a href=\"").append(esc(link)).append("\" ")
                    .append("style=\"display:inline-block;padding:10px 18px;background:#2563eb;color:#ffffff;")
                    .append("text-decoration:none;border-radius:6px;font-size:14px;\">Open</a>")
                    .append("</td></tr>");
        }
        sb.append("<tr><td style=\"padding:16px 28px 24px 28px;border-top:1px solid #eee;font-size:12px;color:#888;\">")
                .append("You are receiving this from ").append(product).append(".</td></tr>")
                .append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private String text(String subject, String body, String link) {
        StringBuilder sb = new StringBuilder(subject).append("\n\n").append(body);
        if (link != null) {
            sb.append("\n\nOpen: ").append(link);
        }
        sb.append("\n\n-- \n").append(branding.getProductName());
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** A rendered email: subject, HTML body and the plain-text alternative. */
    public record RenderedMail(String subject, String html, String text) {
    }
}
