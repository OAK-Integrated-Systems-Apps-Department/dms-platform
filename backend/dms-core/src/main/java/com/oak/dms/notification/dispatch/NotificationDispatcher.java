package com.oak.dms.notification.dispatch;

import com.oak.dms.notification.channel.NotificationChannelSender;
import com.oak.dms.notification.config.NotificationProperties;
import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.enums.DeliveryOutcome;
import com.oak.dms.notification.enums.DeliveryStatus;
import com.oak.dms.notification.helper.NotificationAttemptLog;
import com.oak.dms.notification.helper.NotificationDeliveryAuditor;
import com.oak.dms.notification.repository.NotificationDeliveryRepository;
import com.oak.dms.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Claims due deliveries (SKIP LOCKED + lease), sends each through its channel,
 * and retries with exponential backoff up to max_attempts; on final failure the
 * row is marked failed and audited while the in-app entry stands (3.2, N4).
 * The claim is one short transaction; each send runs outside a DB transaction
 * (the row is already leased), and the result is written in its own short
 * transaction - so SMTP latency never holds a database lock. Every attempt is
 * appended to the immutable attempts log.
 */
@Slf4j
@Component("notificationDeliveryDispatcher")
public class NotificationDispatcher {

    private final DeliveryClaimRepository claimRepository;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final List<NotificationChannelSender> senders;
    private final NotificationAttemptLog attemptLog;
    private final NotificationDeliveryAuditor auditor;
    private final NotificationProperties properties;
    private final TransactionTemplate tx;

    public NotificationDispatcher(DeliveryClaimRepository claimRepository, NotificationDeliveryRepository deliveries,
                                  NotificationRepository notifications, List<NotificationChannelSender> senders,
                                  NotificationAttemptLog attemptLog, NotificationDeliveryAuditor auditor,
                                  NotificationProperties properties, PlatformTransactionManager transactionManager) {
        this.claimRepository = claimRepository;
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.senders = senders;
        this.attemptLog = attemptLog;
        this.auditor = auditor;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Claim one batch of due deliveries and process each; returns how many were processed. */
    public int runOnce() {
        long leaseSeconds = Math.max(1, properties.getDispatcher().getLease().toSeconds());
        int batch = Math.max(1, properties.getDispatcher().getBatchSize());
        List<UUID> claimed = tx.execute(status -> claimRepository.claimDue(leaseSeconds, batch));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (UUID deliveryId : claimed) {
            try {
                if (processOne(deliveryId)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                // One bad row never stops the batch; the lease expires and it is retried.
                log.warn("Notification delivery {} failed to process: {}", deliveryId, e.getMessage(), e);
            }
        }
        return processed;
    }

    private boolean processOne(UUID deliveryId) {
        NotificationDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != DeliveryStatus.sending) {
            return false; // already handled, or the lease was reclaimed
        }
        Notification notification = notifications.findById(delivery.getNotificationId()).orElse(null);
        if (notification == null) {
            return false;
        }
        NotificationChannelSender sender = senders.stream()
                .filter(s -> s.supports(delivery.getChannel())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No sender for channel " + delivery.getChannel()));

        long startNanos = System.nanoTime();
        NotificationChannelSender.SendResult result = sender.send(delivery, notification);
        int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000);

        boolean[] finalFailure = {false};
        tx.executeWithoutResult(status -> {
            NotificationDelivery d = deliveries.findById(deliveryId).orElseThrow();
            int attemptNo = d.getAttemptCount() + 1;
            d.setAttemptCount(attemptNo);
            d.setLockedUntil(null);
            d.setUpdatedAt(OffsetDateTime.now());
            DeliveryOutcome outcome;
            if (result.success()) {
                d.setStatus(DeliveryStatus.sent);
                d.setSentAt(OffsetDateTime.now());
                d.setLastError(null);
                outcome = DeliveryOutcome.sent;
            } else {
                d.setLastError(result.errorMessage());
                outcome = DeliveryOutcome.failed;
                if (attemptNo >= d.getMaxAttempts()) {
                    d.setStatus(DeliveryStatus.failed);
                    finalFailure[0] = true;
                } else {
                    d.setStatus(DeliveryStatus.pending);
                    d.setNextAttemptAt(OffsetDateTime.now().plus(backoff(attemptNo)));
                }
            }
            deliveries.save(d);
            attemptLog.append(d, notification.getRecipientUserId(), attemptNo, outcome,
                    result.errorCode(), result.errorMessage(), durationMs);
        });

        if (finalFailure[0]) {
            // Final failure: the in-app entry stands, an admin can retry (N4/N7).
            auditor.deliveryFailed(delivery, notification, result.errorMessage());
        }
        return true;
    }

    /** Exponential backoff for the given (1-based) attempt number, capped at maxBackoff. */
    Duration backoff(int attemptNumber) {
        Duration base = properties.getDispatcher().getBaseBackoff();
        Duration cap = properties.getDispatcher().getMaxBackoff();
        long multiplier = 1L << Math.min(30, Math.max(0, attemptNumber - 1));
        Duration delay = base.multipliedBy(multiplier);
        return delay.compareTo(cap) > 0 ? cap : delay;
    }
}
