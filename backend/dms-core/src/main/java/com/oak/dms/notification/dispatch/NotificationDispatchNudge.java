package com.oak.dms.notification.dispatch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * After the business transaction that created notifications commits, wakes the
 * dispatcher on its own thread pool so email goes out in near-real-time
 * (N-NFR-002) rather than waiting for the next poll. Gated by the same
 * per-instance switch as the poller, so tests (switch off) never send mail
 * except when they drive the dispatcher explicitly.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dms.notification.dispatcher", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationDispatchNudge {

    private final NotificationDispatcher dispatcher;
    private final TaskExecutor executor;

    public NotificationDispatchNudge(NotificationDispatcher dispatcher,
                                     @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.dispatcher = dispatcher;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationsCreated(NotificationCreatedEvent event) {
        try {
            executor.execute(dispatcher::runOnce);
        } catch (RuntimeException e) {
            log.debug("Dispatcher nudge failed (poller will pick it up): {}", e.getMessage());
        }
    }
}
