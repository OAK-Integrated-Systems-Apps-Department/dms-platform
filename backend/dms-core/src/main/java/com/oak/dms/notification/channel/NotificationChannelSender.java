package com.oak.dms.notification.channel;

import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.enums.NotificationChannel;

/**
 * One delivery channel (3.2, decision 2). Email and in-app ship today; another
 * channel (SMS, push) is added by implementing this interface - callers and the
 * dispatcher never change. The dispatcher picks the sender whose
 * {@link #supports} matches the delivery's channel.
 */
public interface NotificationChannelSender {

    boolean supports(NotificationChannel channel);

    /** Attempt one send. Never throws for an expected delivery failure - returns a failed result instead. */
    SendResult send(NotificationDelivery delivery, Notification notification);

    /** The outcome of one attempt. A failure carries a short code + message for the attempt log. */
    record SendResult(boolean success, String errorCode, String errorMessage) {

        public static SendResult ok() {
            return new SendResult(true, null, null);
        }

        public static SendResult failed(String errorCode, String errorMessage) {
            return new SendResult(false, errorCode, errorMessage);
        }
    }
}
