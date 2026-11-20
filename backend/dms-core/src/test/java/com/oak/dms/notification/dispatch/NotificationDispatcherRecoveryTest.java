package com.oak.dms.notification.dispatch;

import com.oak.dms.notification.channel.NotificationChannelSender;
import com.oak.dms.notification.channel.NotificationChannelSender.SendResult;
import com.oak.dms.notification.config.NotificationProperties;
import com.oak.dms.notification.dispatch.DeliveryClaimRepository.Lease;
import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.enums.DeliveryOutcome;
import com.oak.dms.notification.enums.DeliveryStatus;
import com.oak.dms.notification.enums.NotificationChannel;
import com.oak.dms.notification.helper.NotificationAttemptLog;
import com.oak.dms.notification.helper.NotificationDeliveryAuditor;
import com.oak.dms.notification.repository.NotificationDeliveryRepository;
import com.oak.dms.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NOT-01: a delivery that was claimed ('sending') but never finished is recovered once its lease has run out, is counted as
 * one attempt, and ends up retried or failed - never stuck. Mock-based, so it runs without a database; the SQL itself
 * (SKIP LOCKED, the fencing lock) is covered by {@link DeliveryClaimRepositorySqlTest} and the PostgreSQL integration tests.
 */
class NotificationDispatcherRecoveryTest {

    private final DeliveryClaimRepository claims = mock(DeliveryClaimRepository.class);
    private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationChannelSender sender = mock(NotificationChannelSender.class);
    private final NotificationAttemptLog attemptLog = mock(NotificationAttemptLog.class);
    private final NotificationDeliveryAuditor auditor = mock(NotificationDeliveryAuditor.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final NotificationProperties properties = new NotificationProperties();

    private final UUID deliveryId = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private NotificationDelivery delivery;
    private Notification notification;
    private final OffsetDateTime leaseUntil = OffsetDateTime.now().plusMinutes(2);

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        delivery = NotificationDelivery.builder().deliveryId(deliveryId).tenantId(UUID.randomUUID())
                .notificationId(UUID.randomUUID()).channel(NotificationChannel.email).status(DeliveryStatus.sending)
                .attemptCount(0).maxAttempts(5).lockedUntil(OffsetDateTime.now().minusMinutes(1)).build();
        notification = Notification.builder().notificationId(delivery.getNotificationId()).recipientUserId(recipient).build();
        when(deliveries.findById(deliveryId)).thenAnswer(i -> Optional.of(delivery));
        when(notifications.findById(delivery.getNotificationId())).thenReturn(Optional.of(notification));
        when(sender.supports(NotificationChannel.email)).thenReturn(true);
        when(claims.lockExpiredLeases(anyInt())).thenReturn(List.of());
        when(claims.claimLeases(anyLong(), anyInt())).thenReturn(List.of());
        when(claims.lockIfStillLeased(any(), any())).thenReturn(true);
    }

    private NotificationDispatcher dispatcher(List<NotificationChannelSender> senders) {
        return new NotificationDispatcher(claims, deliveries, notifications, senders, attemptLog, auditor, properties, transactions);
    }

    private NotificationDispatcher dispatcher() {
        return dispatcher(List.of(sender));
    }

    private void claimed() {
        when(claims.claimLeases(anyLong(), anyInt())).thenReturn(List.of(new Lease(deliveryId, leaseUntil)));
    }

    private void expired() {
        when(claims.lockExpiredLeases(anyInt())).thenReturn(List.of(deliveryId));
    }

    // ---------------------------------------------------------------- recovery

    @Test
    void anExpiredSendingDeliveryIsRecoveredAndRetried() {
        expired();

        int recovered = dispatcher().recoverExpiredLeases();

        assertThat(recovered).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.pending);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLockedUntil()).isNull();
        assertThat(delivery.getLastError()).contains("lease expired");
        verify(deliveries).save(delivery);
        verify(auditor, never()).deliveryFailed(any(), any(), any());
    }

    @Test
    void aDeliveryWhoseLeaseIsStillLiveIsNotRecovered() {
        // lockExpiredLeases (status = 'sending' AND lease over) returns nothing for a live lease.
        int recovered = dispatcher().recoverExpiredLeases();

        assertThat(recovered).isZero();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.sending);
        verify(deliveries, never()).save(any());
        verify(attemptLog, never()).append(any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void recoveryUsesTheNormalBackoff() {
        expired();
        delivery.setAttemptCount(2); // the abandoned attempt is attempt 3: base 60 s * 2^2 = 240 s
        OffsetDateTime before = OffsetDateTime.now();

        dispatcher().recoverExpiredLeases();

        Duration expected = properties.getDispatcher().getBaseBackoff().multipliedBy(4);
        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        assertThat(delivery.getNextAttemptAt()).isAfterOrEqualTo(before.plus(expected));
        assertThat(delivery.getNextAttemptAt()).isBeforeOrEqualTo(OffsetDateTime.now().plus(expected));
    }

    @Test
    void recoveryWritesOneAttemptHistoryEntry() {
        expired();
        delivery.setAttemptCount(1);

        dispatcher().recoverExpiredLeases();

        verify(attemptLog, times(1)).append(eq(delivery), eq(recipient), eq(2), eq(DeliveryOutcome.failed),
                eq(NotificationDispatcher.ERROR_LEASE_EXPIRED), any(), eq(null));
    }

    @Test
    void recoveryAtMaxAttemptsFailsTheDeliveryAndAuditsIt() {
        expired();
        delivery.setAttemptCount(4); // max 5: this abandoned attempt is the last

        dispatcher().recoverExpiredLeases();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.failed);
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        assertThat(delivery.getLockedUntil()).isNull();
        verify(auditor).deliveryFailed(eq(delivery), eq(notification), any());
        verify(attemptLog).append(eq(delivery), eq(recipient), eq(5), eq(DeliveryOutcome.failed),
                eq(NotificationDispatcher.ERROR_LEASE_EXPIRED), any(), eq(null));
    }

    @Test
    void aPoisonDeliveryThatKeepsBeingAbandonedEndsFailedAfterMaxAttemptsNotRetriedForever() {
        NotificationDispatcher dispatcher = dispatcher();
        int rounds = 0;
        while (delivery.getStatus() != DeliveryStatus.failed && rounds < 50) {
            // each round: the row is claimed again, the claimer dies, the lease expires, recovery runs
            delivery.setStatus(DeliveryStatus.sending);
            expired();
            dispatcher.recoverExpiredLeases();
            rounds++;
        }

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.failed);
        assertThat(rounds).isEqualTo(delivery.getMaxAttempts());
        verify(attemptLog, times(delivery.getMaxAttempts())).append(any(), any(), anyInt(), any(), any(), any(), any());
        verify(auditor, times(1)).deliveryFailed(any(), any(), any());
    }

    @Test
    void aRecoveredDeliveryDoesNotDisappearSilently() {
        expired();

        dispatcher().recoverExpiredLeases();

        // it is visible in the attempt history, carries the reason, and is back in the queue
        verify(attemptLog).append(any(), eq(recipient), eq(1), eq(DeliveryOutcome.failed), eq("lease_expired"), any(), any());
        assertThat(delivery.getLastError()).isNotBlank();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.pending);
    }

    @Test
    void recoveryOfADeliveryWithNoNotificationClosesItInsteadOfRetryingForever() {
        expired();
        when(notifications.findById(delivery.getNotificationId())).thenReturn(Optional.empty());

        dispatcher().recoverExpiredLeases();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.failed);
        assertThat(delivery.getLockedUntil()).isNull();
        verify(sender, never()).send(any(), any());
    }

    @Test
    void aRowThatIsNotSendingIsNeverTouchedByRecovery() {
        expired();
        delivery.setStatus(DeliveryStatus.sent);

        int recovered = dispatcher().recoverExpiredLeases();

        assertThat(recovered).isZero();
        verify(deliveries, never()).save(any());
    }

    @Test
    void recoveryRunsBeforeTheClaimAndAFailingRecoveryDoesNotStopNewDeliveries() {
        when(claims.lockExpiredLeases(anyInt())).thenThrow(new IllegalStateException("db hiccup"));
        claimed();
        when(sender.send(any(), any())).thenReturn(SendResult.ok());

        int processed = dispatcher().runOnce();

        assertThat(processed).isEqualTo(1);
        InOrder order = inOrder(claims);
        order.verify(claims).lockExpiredLeases(anyInt());
        order.verify(claims).claimLeases(anyLong(), anyInt());
    }

    // ------------------------------------------------- stale workers (fencing)

    @Test
    void aStaleWorkerCannotOverwriteANewerResult() {
        claimed();
        when(sender.send(any(), any())).thenReturn(SendResult.ok());
        // while the send ran, the lease was recovered: the fencing lock no longer finds the row under this worker's lease
        when(claims.lockIfStillLeased(deliveryId, leaseUntil)).thenReturn(false);

        int processed = dispatcher().runOnce();

        assertThat(processed).isZero();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.sending); // untouched by the stale worker
        assertThat(delivery.getAttemptCount()).isZero();
        verify(deliveries, never()).save(any());
        verify(attemptLog, never()).append(any(), any(), anyInt(), any(), any(), any(), any());
        verify(auditor, never()).deliveryFailed(any(), any(), any());
    }

    @Test
    void anAttemptIsCountedOnceWhenARecoveryAndALateWorkerRace() {
        claimed();
        when(sender.send(any(), any())).thenAnswer(invocation -> {
            // the send is "slow": meanwhile another dispatcher recovers this delivery (counts the attempt) ...
            expired();
            when(claims.lockIfStillLeased(deliveryId, leaseUntil)).thenReturn(false);
            dispatcher().recoverExpiredLeases();
            return SendResult.ok(); // ... and then this worker finishes
        });

        dispatcher().runOnce();

        assertThat(delivery.getAttemptCount()).isEqualTo(1); // by the recovery only
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.pending);
        verify(attemptLog, times(1)).append(any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void aHealthyDeliveryWithALiveLeaseIsNotRecoveredPrematurely() {
        claimed();
        when(sender.send(any(), any())).thenReturn(SendResult.ok());

        dispatcher().runOnce();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.sent);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        verify(attemptLog, times(1)).append(any(), any(), anyInt(), any(), any(), any(), any());
    }

    // ------------------------------------------------------ unexpected failures

    @Test
    void aSenderThatThrowsIsRecordedAsAFailedAttemptAndRetried() {
        claimed();
        when(sender.send(any(), any())).thenThrow(new IllegalStateException("template exploded"));

        int processed = dispatcher().runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.pending);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isAfter(OffsetDateTime.now().plusSeconds(30)); // backoff, not a busy loop
        verify(attemptLog).append(any(), eq(recipient), eq(1), eq(DeliveryOutcome.failed),
                eq(NotificationDispatcher.ERROR_UNEXPECTED), any(), any());
    }

    @Test
    void aSenderThatKeepsThrowingFailsAtMaxAttempts() {
        when(sender.send(any(), any())).thenThrow(new IllegalStateException("template exploded"));
        NotificationDispatcher dispatcher = dispatcher();
        for (int i = 0; i < delivery.getMaxAttempts(); i++) {
            delivery.setStatus(DeliveryStatus.sending);
            claimed();
            dispatcher.runOnce();
        }

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.failed);
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        verify(auditor, times(1)).deliveryFailed(any(), any(), any());
    }

    @Test
    void aMissingSenderIsRecordedAsAFailedAttempt() {
        claimed();

        dispatcher(List.of()).runOnce();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.pending);
        verify(attemptLog).append(any(), eq(recipient), eq(1), eq(DeliveryOutcome.failed),
                eq(NotificationDispatcher.ERROR_NO_SENDER), any(), any());
    }

    @Test
    void aMissingNotificationClosesTheDeliveryInsteadOfLeavingItSending() {
        claimed();
        when(notifications.findById(delivery.getNotificationId())).thenReturn(Optional.empty());

        dispatcher().runOnce();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.failed);
        assertThat(delivery.getLockedUntil()).isNull();
        verify(sender, never()).send(any(), any());
    }

    @Test
    void aFailureWhileSavingTheResultIsNotReportedAsSuccessAndIsRecoveredLater() {
        claimed();
        when(sender.send(any(), any())).thenReturn(SendResult.ok());
        doThrow(new IllegalStateException("database went away")).when(deliveries).save(any());

        int processed = dispatcher().runOnce();

        assertThat(processed).isZero(); // not counted as processed, no false success
        verify(auditor, never()).deliveryFailed(any(), any(), any());

        // the next poll: the lease has run out, recovery counts the abandoned attempt
        org.mockito.Mockito.reset(deliveries);
        when(deliveries.findById(deliveryId)).thenAnswer(i -> Optional.of(delivery));
        delivery.setStatus(DeliveryStatus.sending);
        delivery.setAttemptCount(0);
        expired();
        dispatcher().runOnce();

        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.pending);
    }

    // ------------------------------------------------------- existing behaviour

    @Test
    void aNormalSendStillSucceeds() {
        claimed();
        when(sender.send(any(), any())).thenReturn(SendResult.ok());

        int processed = dispatcher().runOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.sent);
        assertThat(delivery.getSentAt()).isNotNull();
        assertThat(delivery.getLockedUntil()).isNull();
        verify(attemptLog).append(eq(delivery), eq(recipient), eq(1), eq(DeliveryOutcome.sent), eq(null), eq(null), any());
    }

    @Test
    void aReturnedFailureIsStillRetriedWithBackoffThenFailed() {
        when(sender.send(any(), any())).thenReturn(SendResult.failed("send_failed", "smtp down"));
        NotificationDispatcher dispatcher = dispatcher();

        for (int attempt = 1; attempt <= delivery.getMaxAttempts(); attempt++) {
            delivery.setStatus(DeliveryStatus.sending);
            claimed();
            dispatcher.runOnce();
            if (attempt < delivery.getMaxAttempts()) {
                assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.pending);
            }
        }

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.failed);
        assertThat(delivery.getAttemptCount()).isEqualTo(5);
        verify(attemptLog, times(5)).append(any(), eq(recipient), anyInt(), eq(DeliveryOutcome.failed), eq("send_failed"), eq("smtp down"), any());
        verify(auditor, times(1)).deliveryFailed(any(), any(), eq("smtp down"));
    }

    // ----------------------------------------------------- delivery semantics

    /**
     * Delivery is at-least-once. If the mail server accepts the message but the result cannot be saved, the abandoned
     * attempt is recovered and the message is sent again, so the recipient may get a duplicate (NOT-02 - not addressed
     * here). This test pins that known behaviour so it is not mistaken for exactly-once.
     */
    @Test
    void aCrashAfterSmtpAcceptanceButBeforePersistenceCanSendTheMessageAgain() {
        claimed();
        when(sender.send(any(), any())).thenReturn(SendResult.ok());
        doThrow(new IllegalStateException("crash before the result is saved")).when(deliveries).save(any());
        dispatcher().runOnce(); // the message was accepted (send returned ok); the result was never saved
        verify(sender, times(1)).send(any(), any());

        org.mockito.Mockito.reset(deliveries);
        when(deliveries.findById(deliveryId)).thenAnswer(i -> Optional.of(delivery));
        // the failed transaction rolled back: the row is as the claim left it (a mock keeps the in-memory changes)
        delivery.setStatus(DeliveryStatus.sending);
        delivery.setAttemptCount(0);
        delivery.setSentAt(null);
        delivery.setLockedUntil(OffsetDateTime.now().minusMinutes(1));
        expired();
        dispatcher().recoverExpiredLeases(); // lease ran out: back to pending, one attempt counted
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.pending);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        when(claims.lockExpiredLeases(anyInt())).thenReturn(List.of());
        delivery.setStatus(DeliveryStatus.sending); // the next claim (the mocked claim does not flip the status itself)
        claimed();
        dispatcher().runOnce(); // sent again

        verify(sender, times(2)).send(any(), any());
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.sent);
    }
}
