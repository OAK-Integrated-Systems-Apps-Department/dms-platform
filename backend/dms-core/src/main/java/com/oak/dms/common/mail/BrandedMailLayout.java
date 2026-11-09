package com.oak.dms.common.mail;

import com.oak.dms.common.config.BrandingProperties;

import java.util.List;

/**
 * The one HTML email layout every mail the app sends goes through: the app's
 * forest-green header with the logo (the dark artwork), a white card for the message, an
 * optional call-to-action button and a quiet footer. Table-based with inline
 * styles because that is what mail clients (Gmail, Outlook) actually render.
 * <p>
 * The logo is referenced as {@code cid:brand-logo}; {@link BrandedMailSender}
 * attaches it inline, so it shows without the recipient's client having to
 * reach the app's host (which is {@code localhost} in dev).
 */
public class BrandedMailLayout {

    /** Content-ID of the inline logo image. */
    static final String LOGO_CID = "brand-logo";
    /** Shown size of mail/logo.png (stored at twice this, for high-density screens). */
    private static final int LOGO_WIDTH = 130;
    private static final int LOGO_HEIGHT = 56;

    // Mirrors frontend/app/globals.css (light theme).
    private static final String PRIMARY = "#1f4d3a";
    private static final String PRIMARY_FG = "#eaf3ed";
    private static final String PRIMARY_TINT = "#e7f0ea";
    private static final String PAGE_BG = "#f6f5f0";
    private static final String INK = "#182420";
    private static final String BODY_INK = "#3a4640";
    private static final String MUTED = "#7b857f";
    private static final String BORDER = "#e4e2d8";
    private static final String FONT = "-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    private final BrandingProperties branding;

    public BrandedMailLayout(BrandingProperties branding) {
        this.branding = branding;
    }

    /**
     * What goes in a mail. {@code buttonLabel}/{@code buttonUrl} and {@code note}
     * are optional (null). Paragraphs may contain newlines, which become line breaks.
     */
    public record Content(String heading, List<String> paragraphs, String buttonLabel, String buttonUrl,
                          String note) {

        public static Content of(String heading, List<String> paragraphs) {
            return new Content(heading, paragraphs, null, null, null);
        }

        public Content withButton(String label, String url) {
            return new Content(heading, paragraphs, label, url, note);
        }

        public Content withNote(String note) {
            return new Content(heading, paragraphs, buttonLabel, buttonUrl, note);
        }

        boolean hasButton() {
            return buttonUrl != null && !buttonUrl.isBlank();
        }

        String label() {
            return buttonLabel == null || buttonLabel.isBlank() ? "Open" : buttonLabel;
        }

        boolean hasNote() {
            return note != null && !note.isBlank();
        }
    }

    public String html(Content c) {
        String product = esc(branding.getProductName());
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
                .append("<title>").append(esc(c.heading())).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:").append(PAGE_BG).append(";\">")
                // Preview text shown beside the subject in the inbox list.
                .append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">")
                .append(esc(preview(c))).append("</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"background:").append(PAGE_BG).append(";\"><tr><td align=\"center\" style=\"padding:32px 16px;\">")
                .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"width:100%;max-width:600px;font-family:").append(FONT).append(";\">");

        // Header: the logo on the brand colour. The header is dark green in every mail client (mail has no
        // theme switch), so it always takes the dark artwork - the white wordmark. The artwork carries the
        // product name, so the name is its alt text for clients that block images.
        sb.append("<tr><td style=\"background:").append(PRIMARY)
                .append(";border-radius:14px 14px 0 0;padding:24px 32px;\">")
                .append("<img src=\"cid:").append(LOGO_CID)
                .append("\" width=\"").append(LOGO_WIDTH).append("\" height=\"").append(LOGO_HEIGHT)
                .append("\" alt=\"").append(product)
                .append("\" style=\"display:block;border:0;font-size:20px;font-weight:600;color:#ffffff;\"/>")
                .append("</td></tr>");

        // Card.
        sb.append("<tr><td style=\"background:#ffffff;border:1px solid ").append(BORDER)
                .append(";border-top:0;border-radius:0 0 14px 14px;padding:36px 32px 32px 32px;\">")
                .append("<h1 style=\"margin:0 0 16px 0;font-size:22px;line-height:1.3;font-weight:700;color:")
                .append(INK).append(";\">").append(esc(c.heading())).append("</h1>");
        for (String p : c.paragraphs()) {
            sb.append("<p style=\"margin:0 0 16px 0;font-size:15px;line-height:1.65;color:").append(BODY_INK)
                    .append(";\">").append(esc(p).replace("\n", "<br/>")).append("</p>");
        }
        if (c.hasButton()) {
            sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 20px 0;\"><tr>")
                    .append("<td style=\"background:").append(PRIMARY).append(";border-radius:8px;\">")
                    .append("<a href=\"").append(esc(c.buttonUrl())).append("\" style=\"display:inline-block;padding:13px 28px;")
                    .append("font-size:15px;font-weight:600;color:").append(PRIMARY_FG)
                    .append(";text-decoration:none;border-radius:8px;\">").append(esc(c.label())).append("</a>")
                    .append("</td></tr></table>")
                    .append("<p style=\"margin:0 0 16px 0;font-size:12px;line-height:1.6;color:").append(MUTED)
                    .append(";\">Button not working? Copy this link into your browser:<br/>")
                    .append("<a href=\"").append(esc(c.buttonUrl())).append("\" style=\"color:").append(PRIMARY)
                    .append(";word-break:break-all;\">").append(esc(c.buttonUrl())).append("</a></p>");
        }
        if (c.hasNote()) {
            sb.append("<div style=\"margin-top:8px;padding:14px 16px;background:").append(PRIMARY_TINT)
                    .append(";border-left:3px solid ").append(PRIMARY)
                    .append(";border-radius:6px;font-size:13px;line-height:1.6;color:").append(BODY_INK).append(";\">")
                    .append(esc(c.note()).replace("\n", "<br/>")).append("</div>");
        }
        sb.append("</td></tr>");

        // Footer.
        sb.append("<tr><td style=\"padding:20px 32px 0 32px;text-align:center;font-size:12px;line-height:1.6;color:")
                .append(MUTED).append(";\">").append(product)
                .append(" &middot; Secure document management<br/>")
                .append("This is an automated message - please do not reply to it.</td></tr>")
                .append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    public String text(Content c) {
        StringBuilder sb = new StringBuilder(c.heading()).append("\n\n");
        for (String p : c.paragraphs()) {
            sb.append(p).append("\n\n");
        }
        if (c.hasButton()) {
            sb.append(c.label()).append(": ").append(c.buttonUrl()).append("\n\n");
        }
        if (c.hasNote()) {
            sb.append(c.note()).append("\n\n");
        }
        return sb.append("-- \n").append(branding.getProductName()).toString();
    }

    private static String preview(Content c) {
        return c.paragraphs().isEmpty() ? c.heading() : c.paragraphs().get(0);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
