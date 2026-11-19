package com.oak.dms.outbox;

import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * OBX-02, interrupted waiting, against PostgreSQL: what the database holds when the relay thread is interrupted while it
 * waits for broker confirmations. The relay stops waiting and returns normally with the interrupt flag still set, so its
 * transaction COMMITS what was settled: rows whose confirm had arrived are {@code published}; rows it never heard about are
 * left exactly as they were ({@code pending}, no attempt counted) and are sent again on a later poll.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
@TestPropertySource(properties = {"dms.outbox.relay-interval-ms=3600000", "dms.outbox.confirm-timeout-ms=60000"})
class OutboxRelayInterruptIT {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private WorkflowFixtures fx;

    private JdbcTemplate jdbc;
    private String prefix;

    @BeforeEach
    void setUp() {
        jdbc = fx.jdbc();
        prefix = "obx02i." + UUID.randomUUID().toString().substring(0, 8) + ".";
        jdbc.update("update outbox_events set status = 'failed' where status = 'pending'");
        reset(rabbitTemplate);
        OutboxConfirms.stubInvoke(rabbitTemplate);
    }

    @Test
    void anInterruptedWaitCommitsTheConfirmedRowsAndLeavesTheUnheardOnesPendingAndUncounted() throws Exception {
        UUID tenant = fx.tenant();
        for (int i = 0; i < 6; i++) {
            jdbc.update("insert into outbox_events (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload,"
                            + " status, created_at) values (?, ?, 'obx02i', ?, ?, '{\"n\":1}'::jsonb, 'pending', now() - make_interval(secs => ?))",
                    UUID.randomUUID(), tenant, UUID.randomUUID(), prefix + i, (double) (6 - i));
        }
        CountDownLatch allSent = new CountDownLatch(6);
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            if (key.startsWith(prefix) && Integer.parseInt(key.substring(prefix.length())) < 3) {
                OutboxConfirms.ack(invocation);                    // the first three are confirmed
            }                                                      // the other three never are
            allSent.countDown();
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        AtomicBoolean flagAfter = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                relay.relayPendingEvents();
                flagAfter.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "relay-interrupted");
        thread.start();
        assertThat(allSent.await(30, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);                                         // it is now waiting for the three confirms that will not come
        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(thread.isAlive()).as("the interrupt ended the wait (the deadline is 60 s)").isFalse();
        assertThat(failure.get()).as("the relay returned normally, so its transaction committed").isNull();
        assertThat(flagAfter.get()).as("the interrupt flag was kept").isTrue();
        for (int i = 0; i < 6; i++) {
            String status = jdbc.queryForObject("select status from outbox_events where event_type = ?", String.class, prefix + i);
            int attempts = jdbc.queryForObject("select attempts from outbox_events where event_type = ?", Integer.class, prefix + i);
            assertThat(status).as("row %d", i).isEqualTo(i < 3 ? "published" : "pending");
            assertThat(attempts).as("row %d attempts", i).isZero();
        }
        // the rows it never heard about are free again (no lock is left behind)
        Integer lockedAway = jdbc.queryForObject("select count(*) from (select 1 from outbox_events where event_type like ? and status = 'pending'"
                + " for update skip locked) s", Integer.class, prefix + "%");
        assertThat(lockedAway).isEqualTo(3);
    }
}
