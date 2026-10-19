package com.oak.dms.notification.channel;

import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.enums.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * The in-app channel (3.2). The inbox row itself IS the delivery, so the
 * service creates in-app deliveries already {@code sent} and the dispatcher
 * never has to send them. This sender exists so the channel abstraction is
 * complete and an in-app "send" is always a success.
 */
@Component
public class InAppChannelSender implements NotificationChannelSender {

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.in_app;
    }

    @Override
    public SendResult send(NotificationDelivery delivery, Notification notification) {
        return SendResult.ok();
    }
}
