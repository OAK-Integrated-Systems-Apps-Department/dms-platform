package com.oak.dms.notification.dispatch;

import com.oak.dms.notification.channel.NotificationChannelSender;
import com.oak.dms.notification.config.NotificationProperties;
import com.oak.dms.notification.dispatch.DeliveryClaimRepository.Lease;
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
import java.util.ArrayList;
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
 * <p>
 * <b>An attempt is counted exactly once, when its outcome is written.</b> That is
 * either by the worker that sent it, or - if the worker never got to write one
 * (the instance stopped or crashed after the claim, or the result could not be
 * saved) - by {@link #recoverExpiredLeases()}, which runs at the start of every
 * poll (NOT-01): a row still 'sending' once its lease has run out is recorded as
 * a failed attempt ("lease_expired") and then retried with the normal backoff, or
 * failed for good at max_attempts. The two writers cannot both win: the result
 * write only proceeds while the row is still 'sending' under the very lease the
 * worker was given (the fencing token), so a worker that finishes late finds its
 * lease gone and discards its result.
 * <p>
 * Delivery is at-least-once, not exactly-once. If the mail server accepted a
 * message but the result was never saved, the recovered attempt sends it again,
 * so the recipient may get a duplicate (NOT-02, not addressed here).
 */
@Slf4j
@Component("notificationDeliveryDispatcher")
public class NotificationDispatcher {

    /** Attempt-log error code for an attempt that was abandoned and recovered by lease expiry. */
    static final String ERROR_LEASE_EXPIRED = "lease_expired";
    static final String ERROR_NO_SENDER = "no_sender";
    static final String ERROR_UNEXPECTED = "unexpected_error";

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

    /**
     * Recover abandoned deliveries, then claim one batch of due deliveries and process each; returns how many of the
     * claimed deliveries were processed.
     */
    public int runOnce() {
        try {
            recoverExpiredLeases();
        } catch (RuntimeException e) {
            // Recovery is best-effort and never stops new deliveries from going out; it runs again on the next poll.
            log.warn("Notification lease recovery failed: {}", e.getMessage(), e);
        }
        long leaseSeconds = Math.max(1, properties.getDispatcher().getLease().toSeconds());
        int batch = Math.max(1, properties.getDispatcher().getBatchSize());
        List<Lease> claimed = tx.execute(status -> claimRepository.claimLeases(leaseSeconds, batch));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (Lease lease : claimed) {
            try {
                if (processOne(lease)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                // One bad row never stops the batch. What failed here is the database work around the attempt (the
                // attempt itself is recorded as a failure, not thrown), so the row stays 'sending' until its lease
                // runs out; recoverExpiredLeases() then counts that attempt and retries or fails it.
                log.warn("Notification delivery {} failed to process: {}", lease.deliveryId(), e.getMessage(), e);
            }
        }
        return processed;
    }

    /**
     * Picks up deliveries that were claimed but whose result was never written (status 'sending', lease expired) and
     * records the abandoned attempt: retried after the usual backoff, or failed and audited once max_attempts is reached.
     * A row with a live lease, or one whose result is being written right now, is never touched.
     *
     * @return how many deliveries were recovered
     */
    int recoverExpiredLeases() {
        int batch = Math.max(1, properties.getDispatcher().getBatchSize());
        List<FailedDelivery> exhausted = new ArrayList<>();
        Integer recovered = tx.execute(status -> {
            int count = 0;
            for (UUID deliveryId : claimRepository.lockExpiredLeases(batch)) {
                NotificationDelivery delivery = deliveries.findById(deliveryId).orElse(null);
                if (delivery == null || delivery.getStatus() != DeliveryStatus.sending) {
                    continue; // cannot happen under the row lock; never act on a row that is not abandoned
                }
                Notification notification = notifications.findById(delivery.getNotificationId()).orElse(null);
                if (notification == null) {
                    failOrphan(delivery);
                } else {
                    String message = "The delivery lease expired before a result was recorded (the dispatcher stopped or "
                            + "could not save it); the message may or may not have been sent.";
                    if (recordFailedAttempt(delivery, notification, ERROR_LEASE_EXPIRED, message, null)) {
                        exhausted.add(new FailedDelivery(delivery, notification, message));
                    }
                    log.warn("Notification delivery {} was abandoned while sending; recovered as attempt {} ({})",
                            deliveryId, delivery.getAttemptCount(), delivery.getStatus());
                }
                count++;
            }
            return count;
        });
        // Final failures are audited after the commit, exactly like a failure the worker records itself.
        exhausted.forEach(f -> auditor.deliveryFailed(f.delivery(), f.notification(), f.message()));
        return recovered == null ? 0 : recovered;
    }

    private boolean processOne(Lease lease) {
        UUID deliveryId = lease.deliveryId();
        NotificationDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != DeliveryStatus.sending) {
            return false; // already handled, or the lease was reclaimed
        }
        Notification notification = notifications.findById(delivery.getNotificationId()).orElse(null);
        if (notification == null) {
            // The foreign key makes this unreachable in practice; if it happens there is no one to notify and nothing
            // to send, so the delivery is closed rather than left leased.
            tx.executeWithoutResult(status -> {
                if (claimRepository.lockIfStillLeased(deliveryId, lease.leaseUntil())) {
                    deliveries.findById(deliveryId).ifPresent(this::failOrphan);
                }
            });
            return false;
        }

        long startNanos = System.nanoTime();
        NotificationChannelSender.SendResult result = send(delivery, notification);
        int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000);

        FailedDelivery[] finalFailure = {null};
        Boolean written = tx.execute(status -> {
            // Fencing: only the worker that still holds the lease it was given may write this attempt's outcome. If the
            // lease was recovered (or the row re-claimed) while this send was running, the outcome is discarded - the
            // recovery already counted this attempt.
            if (!claimRepository.lockIfStillLeased(deliveryId, lease.leaseUntil())) {
                return false;
            }
            NotificationDelivery d = deliveries.findById(deliveryId).orElseThrow();
            if (result.success()) {
                int attemptNo = d.getAttemptCount() + 1;
                d.setAttemptCount(attemptNo);
                d.setLockedUntil(null);
                d.setUpdatedAt(OffsetDateTime.now());
                d.setStatus(DeliveryStatus.sent);
                d.setSentAt(OffsetDateTime.now());
                d.setLastError(null);
                deliveries.save(d);
                attemptLog.append(d, notification.getRecipientUserId(), attemptNo, DeliveryOutcome.sent,
                        null, null, durationMs);
            } else if (recordFailedAttempt(d, notification, result.errorCode(), result.errorMessage(), durationMs)) {
                finalFailure[0] = new FailedDelivery(d, notification, result.errorMessage());
            }
            return true;
        });
        if (!Boolean.TRUE.equals(written)) {
            log.warn("Notification delivery {} result discarded: its lease had expired and the delivery was recovered "
                    + "or re-claimed while this send was running (the message may be delivered twice)", deliveryId);
            return false;
        }

        if (finalFailure[0] != null) {
            // Final failure: the in-app entry stands, an admin can retry (N4/N7).
            auditor.deliveryFailed(finalFailure[0].delivery(), notification, result.errorMessage());
        }
        return true;
    }

    /**
     * One send, with every way it can go wrong turned into a failed result so the attempt is recorded and retried like any
     * other failure: no sender for the channel, or the sender (rendering, mail API) throwing instead of returning one.
     */
    private NotificationChannelSender.SendResult send(NotificationDelivery delivery, Notification notification) {
        NotificationChannelSender sender = senders.stream()
                .filter(s -> s.supports(delivery.getChannel())).findFirst().orElse(null);
        if (sender == null) {
            return NotificationChannelSender.SendResult.failed(ERROR_NO_SENDER, "No sender for channel " + delivery.getChannel());
        }
        try {
            return sender.send(delivery, notification);
        } catch (RuntimeException e) {
            log.error("Notification delivery {} threw while sending: {}", delivery.getDeliveryId(), e.getMessage(), e);
            return NotificationChannelSender.SendResult.failed(ERROR_UNEXPECTED,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Records one failed attempt on a delivery that is 'sending' and whose row the caller holds locked: the attempt is
     * counted here (and only here), then the row goes back to 'pending' with the backoff or, at max_attempts, to 'failed'.
     *
     * @return true when this was the final attempt (the delivery is now 'failed' and should be audited)
     */
    private boolean recordFailedAttempt(NotificationDelivery d, Notification notification, String errorCode,
                                        String errorMessage, Integer durationMs) {
        int attemptNo = d.getAttemptCount() + 1;
        d.setAttemptCount(attemptNo);
        d.setLockedUntil(null);
        d.setUpdatedAt(OffsetDateTime.now());
        d.setLastError(errorMessage);
        boolean finalFailure = attemptNo >= d.getMaxAttempts();
        if (finalFailure) {
            d.setStatus(DeliveryStatus.failed);
        } else {
            d.setStatus(DeliveryStatus.pending);
            d.setNextAttemptAt(OffsetDateTime.now().plus(backoff(attemptNo)));
        }
        deliveries.save(d);
        attemptLog.append(d, notification.getRecipientUserId(), attemptNo, DeliveryOutcome.failed,
                errorCode, errorMessage, durationMs);
        return finalFailure;
    }

    /**
     * Closes a leased delivery whose notification no longer exists. The attempt log needs a recipient, which only the
     * notification knows, so nothing is appended; the row is failed with the reason so it is neither retried forever nor
     * left 'sending'.
     */
    private void failOrphan(NotificationDelivery d) {
        log.error("Notification delivery {} has no notification {}; closing it as failed", d.getDeliveryId(), d.getNotificationId());
        d.setStatus(DeliveryStatus.failed);
        d.setLockedUntil(null);
        d.setLastError("The notification for this delivery no longer exists");
        d.setUpdatedAt(OffsetDateTime.now());
        deliveries.save(d);
    }

    /** A delivery that has just failed for good, kept for the audit event written after its transaction commits. */
    private record FailedDelivery(NotificationDelivery delivery, Notification notification, String message) {
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
