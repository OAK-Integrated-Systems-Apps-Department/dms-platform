package com.oak.dms.notification.channel.email;

import com.oak.dms.notification.channel.NotificationChannelSender;
import com.oak.dms.notification.config.NotificationProperties;
import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.enums.NotificationChannel;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends a notification as an HTML email (with a plain-text alternative part)
 * through the app's existing SMTP ({@link JavaMailSender}, 3.2 N5). A send
 * failure is returned, never thrown, so the dispatcher can record the attempt
 * and schedule a retry.
 */
@Component
public class EmailChannelSender implements NotificationChannelSender {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;
    private final NotificationMailRenderer renderer;

    public EmailChannelSender(JavaMailSender mailSender, NotificationProperties properties,
                              NotificationMailRenderer renderer) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.renderer = renderer;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.email;
    }

    @Override
    public SendResult send(NotificationDelivery delivery, Notification notification) {
        String to = delivery.getDestination();
        if (to == null || to.isBlank()) {
            return SendResult.failed("no_destination", "No email address on the delivery");
        }
        try {
            NotificationMailRenderer.RenderedMail mail = renderer.render(notification);
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(properties.getMailFrom());
            helper.setTo(to);
            helper.setSubject(mail.subject());
            // text first, html second -> html is the preferred alternative.
            helper.setText(mail.text(), mail.html());
            mailSender.send(mime);
            return SendResult.ok();
        } catch (MailException | MessagingException e) {
            return SendResult.failed("send_failed", e.getMessage());
        }
    }
}
