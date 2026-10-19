package com.oak.dms.notification;

import com.oak.dms.notification.dispatch.NotificationDispatcher;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.notification.support.NotificationMailSupport;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N4: two dispatchers running the claim concurrently over the same pending rows
 * must never double-send - FOR UPDATE SKIP LOCKED gives each row to exactly one
 * claimer. Repeated 5x (locking-change rule).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class NotificationConcurrencyIT extends NotificationMailSupport {

    private static final int DELIVERIES = 12;

    @Autowired
    private NotificationService service;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private WorkflowFixtures fx;

    @RepeatedTest(5)
    void twoDispatchers_sendEachDeliveryExactlyOnce() throws Exception {
        UUID tenant = fx.tenant();
        User alice = fx.user(tenant, "Alice");
        for (int i = 0; i < DELIVERIES; i++) {
            service.notify(NotificationRequest.builder()
                    .tenantId(tenant)
                    .recipientUserIds(List.of(alice.getUserId()))
                    .categoryCode("comment.mention")
                    .title("Mention " + i)
                    .body("body " + i)
                    .linkUrl("/documents/" + i)
                    .sourceModule("documents")
                    .dedupeKey("c-" + i)
                    .build());
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable drain = () -> {
            try {
                start.await();
                for (int i = 0; i < 100; i++) {
                    int handled = dispatcher.runOnce();
                    if (handled == 0 && pendingEmails(tenant) == 0) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(drain);
        pool.submit(drain);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(waitForMail(DELIVERIES, 5000)).isTrue();
        // Exactly DELIVERIES emails to Alice - no duplicates.
        assertThat(mailTo(alice.getEmail())).hasSize(DELIVERIES);
        // Every email delivery of this tenant ended sent, each with exactly one sent attempt, none failed.
        assertThat(fx.count("select count(*) from notification_deliveries where tenant_id = ? and channel = 'email'"
                + " and status = 'sent'", tenant)).isEqualTo(DELIVERIES);
        assertThat(fx.count("select count(*) from notification_delivery_attempts where tenant_id = ? and channel = 'email'"
                + " and outcome = 'sent'", tenant)).isEqualTo(DELIVERIES);
        assertThat(fx.count("select count(*) from notification_delivery_attempts where tenant_id = ? and channel = 'email'"
                + " and outcome = 'failed'", tenant)).isZero();
    }

    private long pendingEmails(UUID tenant) {
        return fx.count("select count(*) from notification_deliveries where tenant_id = ? and channel = 'email'"
                + " and status in ('pending','sending')", tenant);
    }
}
