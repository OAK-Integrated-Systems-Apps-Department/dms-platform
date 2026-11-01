package com.oak.dms.common.mail;

import com.oak.dms.common.config.BrandingProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;

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
}
