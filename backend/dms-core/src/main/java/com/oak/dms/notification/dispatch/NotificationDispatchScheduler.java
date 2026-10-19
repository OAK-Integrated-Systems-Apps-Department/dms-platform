package com.oak.dms.notification.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for due deliveries and dispatches them (3.2, N4). The per-instance
 * switch {@code dms.notification.dispatcher.enabled} (on by default) lets
 * delivery move to a dedicated instance or the notification-worker later
 * without changing any caller. Off in tests, which drive the dispatcher
 * directly.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dms.notification.dispatcher", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationDispatchScheduler {

    private final NotificationDispatcher dispatcher;

    public NotificationDispatchScheduler(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${dms.notification.dispatcher.poll-interval:PT30S}",
            initialDelayString = "${dms.notification.dispatcher.poll-interval:PT30S}")
    public void poll() {
        try {
            int handled = dispatcher.runOnce();
            if (handled > 0) {
                log.debug("Notification dispatcher sent {} deliveries", handled);
            }
        } catch (RuntimeException e) {
            log.warn("Notification dispatcher poll failed: {}", e.getMessage(), e);
        }
    }
}
