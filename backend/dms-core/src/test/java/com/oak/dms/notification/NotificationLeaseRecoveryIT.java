package com.oak.dms.notification;

import com.oak.dms.notification.dispatch.DeliveryClaimRepository;
import com.oak.dms.notification.dispatch.DeliveryClaimRepository.Lease;
import com.oak.dms.notification.dispatch.NotificationDispatcher;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.notification.support.NotificationMailSupport;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NOT-01 against PostgreSQL: a delivery that was claimed ('sending') but never finished is recovered once its lease has run
 * out - counted as one attempt, then retried or failed - while a live lease is left alone, two dispatchers recover a row
 * once between them, and a worker that lost its lease cannot write a result. The abandoned state is created directly in the
 * table (exactly what a dispatcher that died after the claim leaves behind); the dispatcher is driven by hand
 * ({@code runOnce}) and the mail goes to the in-process GreenMail server.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class NotificationLeaseRecoveryIT extends NotificationMailSupport {

    @Autowired
    private NotificationService service;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private DeliveryClaimRepository claimRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private WorkflowFixtures fx;

    private UUID notify(UUID tenant, User to, String key) {
        return service.notify(NotificationRequest.builder()
                .tenantId(tenant)
                .recipientUserIds(List.of(to.getUserId()))
                .categoryCode("comment.mention")
                .title("You were mentioned: Project plan")
                .body("Dilani mentioned you in a comment.")
                .linkUrl("/documents/abc")
                .sourceModule("documents")
                .dedupeKey(key)
                .build()).get(0);
    }

    private UUID emailDelivery(UUID notificationId) {
        return fx.jdbc().queryForObject("select delivery_id from notification_deliveries"
                + " where notification_id = ? and channel = 'email'", UUID.class, notificationId);
    }

    /** What a dispatcher that died right after the claim leaves behind. */
    private void abandon(UUID deliveryId, String lockedUntilSql) {
        fx.jdbc().update("update notification_deliveries set status = 'sending', locked_until = " + lockedUntilSql
                + ", updated_at = now() where delivery_id = ?", deliveryId);
    }

    private String status(UUID deliveryId) {
        return fx.jdbc().queryForObject("select status from notification_deliveries where delivery_id = ?", String.class, deliveryId);
    }

    private long attempts(UUID deliveryId, String outcome) {
        return fx.count("select count(*) from notification_delivery_attempts where delivery_id = ? and outcome = ?", deliveryId, outcome);
    }

    private long attemptsWithCode(UUID deliveryId, String code) {
        return fx.count("select count(*) from notification_delivery_attempts where delivery_id = ? and error_code = ?", deliveryId, code);
    }

    private int attemptCount(UUID deliveryId) {
        return fx.jdbc().queryForObject("select attempt_count from notification_deliveries where delivery_id = ?", Integer.class, deliveryId);
    }

    /** Whether the fencing lock still finds the delivery 'sending' under exactly this lease. */
    private boolean stillLeased(TransactionTemplate tx, UUID delivery, Lease lease) {
        return Boolean.TRUE.equals(tx.execute(s -> claimRepository.lockIfStillLeased(delivery, lease.leaseUntil())));
    }

    @Test
    void anAbandonedDeliveryIsRecovered_thenSent() throws Exception {
        UUID tenant = fx.tenant();
        User alice = fx.user(tenant, "Alice");
        UUID delivery = emailDelivery(notify(tenant, alice, "abandoned"));
        abandon(delivery, "now() - interval '1 minute'");

        for (int i = 0; i < 25 && !"sent".equals(status(delivery)); i++) {
            dispatcher.runOnce();
            Thread.sleep(250);
        }

        assertThat(status(delivery)).isEqualTo("sent");
        assertThat(attemptCount(delivery)).isEqualTo(2); // the abandoned attempt, then the real one
        assertThat(attemptsWithCode(delivery, "lease_expired")).isEqualTo(1);
        assertThat(attempts(delivery, "sent")).isEqualTo(1);
        assertThat(waitForMail(1, 5000)).isTrue();
        assertThat(mailTo(alice.getEmail())).hasSize(1);
    }

    @Test
    void aDeliveryWithALiveLeaseIsLeftAlone() {
        UUID tenant = fx.tenant();
        User alice = fx.user(tenant, "Alice");
        UUID delivery = emailDelivery(notify(tenant, alice, "live"));
        abandon(delivery, "now() + interval '10 minutes'");

        dispatcher.runOnce();

        assertThat(status(delivery)).isEqualTo("sending");
        assertThat(attemptCount(delivery)).isZero();
        assertThat(attempts(delivery, "failed")).isZero();
        assertThat(mailTo(alice.getEmail())).isEmpty();
    }

    @Test
    void aSendingDeliveryWithNoLeaseAtAllIsRecoveredToo() {
        UUID tenant = fx.tenant();
        User alice = fx.user(tenant, "Alice");
        UUID delivery = emailDelivery(notify(tenant, alice, "nolease"));
        abandon(delivery, "null");

        dispatcher.runOnce();

        assertThat(status(delivery)).isNotEqualTo("sending");
        assertThat(attemptsWithCode(delivery, "lease_expired")).isEqualTo(1);
    }

    @Test
    void anAbandonedDeliveryAtItsLastAttemptFailsAndIsAudited() {
        UUID tenant = fx.tenant();
        User alice = fx.user(tenant, "Alice");
        UUID delivery = emailDelivery(notify(tenant, alice, "exhausted"));
        int max = fx.jdbc().queryForObject("select max_attempts from notification_deliveries where delivery_id = ?",
                Integer.class, delivery);
        fx.jdbc().update("update notification_deliveries set attempt_count = ? where delivery_id = ?", max - 1, delivery);
        abandon(delivery, "now() - interval '1 minute'");

        dispatcher.runOnce();

        assertThat(status(delivery)).isEqualTo("failed");
        assertThat(attemptCount(delivery)).isEqualTo(max);
        assertThat(attemptsWithCode(delivery, "lease_expired")).isEqualTo(1);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'NOTIFICATION_DELIVERY_FAILED'",
                tenant)).isGreaterThanOrEqualTo(1);
        assertThat(mailTo(alice.getEmail())).isEmpty();
    }

    @Test
    void twoDispatchersRecoverTheSameAbandonedDeliveryOnce() throws Exception {
        UUID tenant = fx.tenant();
        User alice = fx.user(tenant, "Alice");
        UUID delivery = emailDelivery(notify(tenant, alice, "race"));
        abandon(delivery, "now() - interval '1 minute'");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Runnable run = () -> {
            try {
                start.await();
                dispatcher.runOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(run);
        pool.submit(run);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // the abandoned attempt was counted and logged by exactly one of them
        assertThat(attemptsWithCode(delivery, "lease_expired")).isEqualTo(1);
    }

    @Test
    void aWorkerThatLostItsLeaseCannotWriteAResult() {
        UUID tenant = fx.tenant();
        User alice = fx.user(tenant, "Alice");
        UUID delivery = emailDelivery(notify(tenant, alice, "fence"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Lease lease = tx.execute(s -> claimRepository.claimLeases(60, 50).stream()
                .filter(l -> l.deliveryId().equals(delivery)).findFirst().orElseThrow());
        assertThat(stillLeased(tx, delivery, lease)).isTrue();

        // the lease runs out and the delivery is recovered by another dispatcher
        fx.jdbc().update("update notification_deliveries set locked_until = now() - interval '1 minute' where delivery_id = ?", delivery);
        dispatcher.runOnce();

        assertThat(stillLeased(tx, delivery, lease)).isFalse();
        assertThat(attemptsWithCode(delivery, "lease_expired")).isEqualTo(1);

        // ... and when it is claimed again, only the NEW lease is valid
        fx.jdbc().update("update notification_deliveries set next_attempt_at = now() where delivery_id = ?", delivery);
        Lease second = tx.execute(s -> claimRepository.claimLeases(60, 50).stream()
                .filter(l -> l.deliveryId().equals(delivery)).findFirst().orElseThrow());
        assertThat(second.leaseUntil()).isNotEqualTo(lease.leaseUntil());
        assertThat(stillLeased(tx, delivery, lease)).isFalse();
        assertThat(stillLeased(tx, delivery, second)).isTrue();
    }
}
