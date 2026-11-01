package com.oak.dms.notification.channel.email;

import com.oak.dms.common.mail.BrandedMailSender;
import com.oak.dms.notification.channel.NotificationChannelSender;
import com.oak.dms.notification.config.NotificationProperties;
import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.enums.NotificationChannel;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

/**
 * Sends a notification as an HTML email (with a plain-text alternative part)
 * through the app's SMTP ({@link BrandedMailSender}, 3.2 N5). A send
 * failure is returned, never thrown, so the dispatcher can record the attempt
 * and schedule a retry.
 */
@Component
public class EmailChannelSender implements NotificationChannelSender {

    private final BrandedMailSender mailSender;
    private final NotificationProperties properties;
    private final NotificationMailRenderer renderer;

    public EmailChannelSender(BrandedMailSender mailSender, NotificationProperties properties,
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
            mailSender.send(properties.getMailFrom(), to, mail.subject(), mail.html(), mail.text());
            return SendResult.ok();
        } catch (MailException e) {
            return SendResult.failed("send_failed", e.getMessage());
        }
    }
}
