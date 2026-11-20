package com.oak.dms.common.mail;

import com.oak.dms.common.config.BrandingProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.List;

/**
 * Sends an HTML + plain-text mail with the brand logo attached inline (see
 * {@link BrandedMailLayout}). Every outgoing app mail goes through here so the
 * look stays identical. Failures surface as {@link MailException}.
 */
@Component
public class BrandedMailSender {

    private static final ClassPathResource LOGO = new ClassPathResource("mail/logo.png");

    private final JavaMailSender mailSender;
    private final BrandingProperties branding;

    public BrandedMailSender(JavaMailSender mailSender, BrandingProperties branding) {
        this.mailSender = mailSender;
        this.branding = branding;
    }

    public void send(String from, String to, String subject, String html, String text) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(new InternetAddress(from, branding.getProductName(), "UTF-8"));
            helper.setTo(to);
            helper.setSubject(subject);
            // text first, html second -> html is the preferred alternative.
            helper.setText(text, html);
            // Must come after setText: the inline part attaches to the HTML body.
            helper.addInline(BrandedMailLayout.LOGO_CID, LOGO, "image/png");
            mailSender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Could not build the email", e);
        }
    }

    /**
     * A file to attach. {@code content} is opened when the mail is built, and may be opened again, so give a source that can be
     * (a file, a byte array) - not a stream that can be read once.
     */
    public record Attachment(String filename, String contentType, InputStreamSource content) {
    }

    /**
     * The same mail as {@link #send(String, String, String, String, String)} - same layout, same inline logo - with files attached.
     * The original method and every caller of it are unchanged.
     */
    public void send(String from, String to, String subject, String html, String text, List<Attachment> attachments) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(new InternetAddress(from, branding.getProductName(), "UTF-8"));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            helper.addInline(BrandedMailLayout.LOGO_CID, LOGO, "image/png");
            for (Attachment attachment : attachments) {
                helper.addAttachment(MimeUtility.encodeText(attachment.filename(), "UTF-8", null), attachment.content(),
                        attachment.contentType());
            }
            mailSender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("Could not build the email", e);
        }
    }
}
