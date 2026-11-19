package com.oak.dms.outbox;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBX-02 without a database or a broker: what the relay does with each outcome the broker can report for a publish.
 * <p>
 * The broker is a controlled stand-in ({@link OutboxConfirms}): it answers through the {@link CorrelationData} each send
 * carries, which is how the real template reports confirms (a completed future) and returns (the {@code returned} field,
 * set before the confirm). So these tests prove the relay's decisions, not RabbitMQ's behaviour; the latter is
 * {@code OutboxRelayBrokerIT}'s job and needs a real broker. Waits are on latches and futures; the only real time spent is
 * the short, configured confirmation deadline in the tests that exercise it.
 */
class OutboxRelayConfirmationTest {

    private static final long SHORT_DEADLINE_MS = 300;

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
    private final ScheduledExecutorService broker = Executors.newScheduledThreadPool(2);

    {
        OutboxConfirms.stubInvoke(rabbit);                     // the relay publishes a batch inside template.invoke(...)
        OutboxConfirms.stubClock(repository);                  // OBX-03: the database clock retries are scheduled on
    }

    @AfterEach
    void stopBroker() {
        broker.shutdownNow();
    }

    private OutboxRelay relay(long deadlineMs) {
        return new OutboxRelay(repository, rabbit, audit, deadlineMs);
    }

    private OutboxEvent event(String type) {
        return OutboxEvent.builder().outboxEventId(UUID.randomUUID()).tenantId(UUID.randomUUID()).aggregateType("test")
                .aggregateId(UUID.randomUUID()).eventType(type).payload("{\"k\":1}").build();
    }

    private void batch(OutboxEvent... events) {
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of(events));
    }

    private void send(org.mockito.stubbing.Answer<Object> answer) {
        doAnswer(answer).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private void sendFor(String routingKey, org.mockito.stubbing.Answer<Object> answer) {
        doAnswer(answer).when(rabbit).send(anyString(), eq(routingKey), any(Message.class), any(CorrelationData.class));
    }

    private static void assertPublished(OutboxEvent e) {
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(e.getPublishedAt()).isNotNull();
        assertThat(e.getAttempts()).isZero();
    }

    private static void assertRetryable(OutboxEvent e, int attempts) {
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(attempts);
        assertThat(e.getPublishedAt()).isNull();
        assertThat(e.getLastError()).isNotBlank();
    }

    // ---- 1-3: the three outcomes the broker can report

    @Test
    void aPositiveConfirmationOfARoutedMessageMarksTheRowPublished() {
        OutboxEvent e = event("ok.1");
        batch(e);
        send(OutboxConfirms::ack);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertPublished(e);
        verify(repository).save(e);
        verify(audit, never()).event(any());
    }

    @Test
    void aNegativeConfirmationIsADeliveryFailure() {
        OutboxEvent e = event("nack.1");
        batch(e);
        send(OutboxConfirms::nack);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertRetryable(e, 1);
        assertThat(e.getLastError()).contains("negatively acknowledged").contains("queue full");
    }

    @Test
    void aConfirmationThatNeverArrivesIsADeliveryFailureAfterTheDeadline() {
        OutboxEvent e = event("silent.1");
        batch(e);
        send(OutboxConfirms::silence);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertRetryable(e, 1);
        assertThat(e.getLastError()).contains("no broker confirmation").contains(String.valueOf(SHORT_DEADLINE_MS));
    }

    // ---- 4: a return beats a positive confirm

    @Test
    void aReturnedMessageIsAFailureEvenWhenTheBrokerAlsoConfirmsThePublish() {
        OutboxEvent e = event("user.created");
        batch(e);
        send(OutboxConfirms::returnedThenAck);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertRetryable(e, 1);
        assertThat(e.getLastError()).contains("returned").contains("unroutable").contains("312").contains("user.created");
    }

    // ---- 5-6: failures that are not an answer from the broker

    @Test
    void anImmediateSendExceptionIsADeliveryFailureAndIsNotWaitedFor() {
        OutboxEvent e = event("boom.1");
        batch(e);
        doThrow(new IllegalStateException("channel unavailable")).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        long started = System.nanoTime();
        relay(30_000).relayPendingEvents();

        assertRetryable(e, 1);
        assertThat(e.getLastError()).contains("channel unavailable");
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).as("did not wait for a confirm that cannot come").isLessThan(5);
    }

    @Test
    void aConnectionThatDropsIsAFailureWhetherItShowsAsAThrownSendOrAFailedConfirmation() {
        OutboxEvent refused = event("conn.refused");
        OutboxEvent dropped = event("conn.dropped");
        OutboxEvent fine = event("conn.fine");
        batch(refused, dropped, fine);
        doThrow(new AmqpConnectException(new ConnectException("refused"))).when(rabbit)
                .send(anyString(), eq("conn.refused"), any(Message.class), any(CorrelationData.class));
        sendFor("conn.dropped", i -> {
            OutboxConfirms.correlation(i).getFuture().completeExceptionally(new AmqpException("Channel closed; cannot ack/nack"));
            return null;
        });
        sendFor("conn.fine", OutboxConfirms::ack);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertRetryable(refused, 1);
        assertRetryable(dropped, 1);
        assertThat(dropped.getLastError()).contains("confirmation failed").contains("Channel closed");
        assertPublished(fine);
    }

    @Test
    void aChannelThatCannotBeOpenedFailsEveryRowOfTheBatchOnceAndStillSettlesThem() {
        OutboxEvent a = event("nochan.a");
        OutboxEvent b = event("nochan.b");
        batch(a, b);
        doThrow(new AmqpConnectException(new ConnectException("refused"))).when(rabbit)
                .invoke(any(org.springframework.amqp.rabbit.core.RabbitOperations.OperationsCallback.class));

        relay(30_000).relayPendingEvents();

        assertRetryable(a, 1);
        assertRetryable(b, 1);
        assertThat(a.getLastError()).contains("publishing failed");
        verify(repository).save(a);
        verify(repository).save(b);
    }

    @Test
    void aChannelLostAfterSomeSendsFailsOnlyWhatHadNoAnswerYet() {
        OutboxEvent answered = event("lost.answered");
        OutboxEvent pending = event("lost.pending");
        OutboxEvent unsent = event("lost.unsent");
        batch(answered, pending, unsent);
        sendFor("lost.answered", OutboxConfirms::ack);
        sendFor("lost.pending", OutboxConfirms::silence);
        doThrow(new org.springframework.amqp.AmqpIOException(new java.io.IOException("channel closed"))).when(rabbit)
                .send(anyString(), eq("lost.unsent"), any(Message.class), any(CorrelationData.class));
        // the connection dies while the relay is waiting for the second answer: the callback surfaces the failure
        doAnswer(i -> {
            org.springframework.amqp.rabbit.core.RabbitOperations.OperationsCallback<?> callback = i.getArgument(0);
            callback.doInRabbit(rabbit);
            throw new org.springframework.amqp.AmqpIOException(new java.io.IOException("connection reset"));
        }).when(rabbit).invoke(any(org.springframework.amqp.rabbit.core.RabbitOperations.OperationsCallback.class));

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertPublished(answered);                      // it had its confirm before the failure
        assertRetryable(pending, 1);
        assertRetryable(unsent, 1);
    }

    @Test
    void thePublisherConnectionBoundsChannelRpcsWithoutTouchingTheSharedDefaults() {
        com.rabbitmq.client.ConnectionFactory client = new com.rabbitmq.client.ConnectionFactory();
        int clientDefault = client.getChannelRpcTimeout();

        org.springframework.amqp.rabbit.connection.CachingConnectionFactory factory =
                com.oak.dms.messaging.RabbitMqConfig.publisherConnectionFactory(client, 10_000);

        assertThat(client.getChannelRpcTimeout()).isEqualTo(10_000);
        assertThat(clientDefault).as("the Rabbit client's own default is 10 minutes").isEqualTo(600_000);
        assertThat(factory.isPublisherConfirms()).isTrue();
        assertThat(factory.isPublisherReturns()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> com.oak.dms.messaging.RabbitMqConfig
                        .publisherConnectionFactory(new com.rabbitmq.client.ConnectionFactory(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 7: late confirmation

    @Test
    void aLateConfirmationOfAnEarlierAttemptCannotTouchALaterAttempt() {
        OutboxEvent e = event("late.1");
        batch(e);
        List<CorrelationData> seen = new CopyOnWriteArrayList<>();
        send(i -> {
            seen.add(OutboxConfirms.correlation(i));
            return null;                                                 // 1st attempt: silence -> times out
        });
        relay(SHORT_DEADLINE_MS).relayPendingEvents();
        assertRetryable(e, 1);

        send(i -> {
            seen.add(OutboxConfirms.correlation(i));
            return OutboxConfirms.ack(i);                                // 2nd attempt: confirmed
        });
        // the first attempt's confirmation turns up now - after its attempt has already been settled as a failure
        seen.get(0).getFuture().complete(new CorrelationData.Confirm(true, null));
        assertThat(e.getStatus()).as("a late answer changes nothing by itself").isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).getId()).as("each publish has its own correlation").isNotEqualTo(seen.get(0).getId());
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(e.getPublishedAt()).isNotNull();
        assertThat(e.getAttempts()).as("the first attempt's failure was counted once, the late answer not at all").isEqualTo(1);
    }

    // ---- 8, 9, 11: several publishes in flight, answered out of order

    @Test
    void severalInFlightPublishesAreMatchedToTheirOwnEventsEvenWhenAnsweredOutOfOrder() {
        OutboxEvent first = event("many.1");
        OutboxEvent second = event("many.2");
        OutboxEvent third = event("many.3");
        batch(first, second, third);
        List<CorrelationData> inFlight = new ArrayList<>();
        send(i -> {
            inFlight.add(OutboxConfirms.correlation(i));
            if (inFlight.size() == 3) {                                  // all three are in flight: now answer, last first
                inFlight.get(2).getFuture().complete(new CorrelationData.Confirm(true, null));
                inFlight.get(1).getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
                inFlight.get(0).getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            return null;
        });

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        Set<String> ids = new HashSet<>();
        inFlight.forEach(c -> ids.add(c.getId()));
        assertThat(ids).as("a unique correlation per publish").hasSize(3);
        assertPublished(first);
        assertRetryable(second, 1);                                     // the nack belongs to the second event only
        assertPublished(third);
        assertThat(inFlight.get(1).getId()).startsWith(second.getOutboxEventId().toString());
    }

    @Test
    void aMixedBatchPublishesWhatWasConfirmedAndRetriesTheRest() {
        OutboxEvent ok1 = event("mix.ok1");
        OutboxEvent nacked = event("mix.nacked");
        OutboxEvent returned = event("mix.returned");
        OutboxEvent silent = event("mix.silent");
        OutboxEvent thrown = event("mix.thrown");
        OutboxEvent ok2 = event("mix.ok2");
        batch(ok1, nacked, returned, silent, thrown, ok2);
        sendFor("mix.ok1", OutboxConfirms::ack);
        sendFor("mix.nacked", OutboxConfirms::nack);
        sendFor("mix.returned", OutboxConfirms::returnedThenAck);
        sendFor("mix.silent", OutboxConfirms::silence);
        doThrow(new IllegalStateException("nope")).when(rabbit)
                .send(anyString(), eq("mix.thrown"), any(Message.class), any(CorrelationData.class));
        sendFor("mix.ok2", OutboxConfirms::ack);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertPublished(ok1);
        assertRetryable(nacked, 1);
        assertRetryable(returned, 1);
        assertRetryable(silent, 1);
        assertRetryable(thrown, 1);
        assertPublished(ok2);
        verify(repository, org.mockito.Mockito.times(6)).save(any());
    }

    @Test
    void anAllFailedBatchCountsOneAttemptPerRowAndPublishesNothing() {
        OutboxEvent a = event("all.a");
        OutboxEvent b = event("all.b");
        OutboxEvent c = event("all.c");
        batch(a, b, c);
        send(OutboxConfirms::nack);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        for (OutboxEvent e : List.of(a, b, c)) {
            assertRetryable(e, 1);
        }
    }

    // ---- 12-13: the attempt policy is the existing one

    @Test
    void aRowOlderThanTheRetryWindowFailsForGoodAndIsAudited_whateverTheFailureWas() {
        OutboxEvent nacked = event("max.nack");
        nacked.setAttempts(4);
        nacked.setFirstFailedAt(java.time.OffsetDateTime.now().minusHours(25));
        OutboxEvent returned = event("max.return");
        returned.setAttempts(4);
        returned.setFirstFailedAt(java.time.OffsetDateTime.now().minusHours(25));
        OutboxEvent silent = event("max.silent");
        silent.setAttempts(4);
        silent.setFirstFailedAt(java.time.OffsetDateTime.now().minusHours(25));
        batch(nacked, returned, silent);
        sendFor("max.nack", OutboxConfirms::nack);
        sendFor("max.return", OutboxConfirms::returnedThenAck);
        sendFor("max.silent", OutboxConfirms::silence);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        for (OutboxEvent e : List.of(nacked, returned, silent)) {
            assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.failed);
            assertThat(e.getAttempts()).isEqualTo(5);
        }
        verify(audit, org.mockito.Mockito.times(3)).event(AuditActionCatalog.OUTBOX_EVENT_PUBLISH_FAILED);
    }

    @Test
    void aFailedRowIsNotPublishedAgainAndANewPollWithoutPendingRowsSendsNothing() {
        OutboxEvent e = event("gone.1");
        e.setAttempts(4);
        e.setFirstFailedAt(java.time.OffsetDateTime.now().minusHours(25));
        batch(e);
        send(OutboxConfirms::nack);
        relay(SHORT_DEADLINE_MS).relayPendingEvents();
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.failed);

        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of());   // the query only returns pending rows (OBX-01)
        org.mockito.Mockito.clearInvocations(rabbit);
        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.failed);
    }

    // ---- 14-15: order and wire format are unchanged

    @Test
    void rowsAreSentInRepositoryOrderAndSettledInThatOrder() {
        OutboxEvent first = event("ord.1");
        OutboxEvent second = event("ord.2");
        OutboxEvent third = event("ord.3");
        batch(first, second, third);
        List<String> sent = new ArrayList<>();
        send(i -> {
            sent.add(OutboxConfirms.routingKey(i));
            return OutboxConfirms.ack(i);
        });

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertThat(sent).containsExactly("ord.1", "ord.2", "ord.3");
        InOrder saved = inOrder(repository);
        saved.verify(repository).save(first);
        saved.verify(repository).save(second);
        saved.verify(repository).save(third);
    }

    @Test
    void theMessageIsStillTheRawJsonPayloadOnTheEventsExchange_withACorrelationAttached() {
        OutboxEvent e = event("wire.1");
        batch(e);
        List<Message> messages = new ArrayList<>();
        List<String> exchanges = new ArrayList<>();
        send(i -> {
            exchanges.add(i.getArgument(0));
            messages.add(i.getArgument(2));
            return OutboxConfirms.ack(i);
        });

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertThat(exchanges).containsExactly(com.oak.dms.messaging.RabbitMqConfig.DMS_EVENTS_EXCHANGE);
        assertThat(new String(messages.get(0).getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("{\"k\":1}");
        assertThat(messages.get(0).getMessageProperties().getContentType()).isEqualTo("application/json");
    }

    // ---- 16-17: interruption, and no unbounded wait

    @Test
    void anInterruptedWaitStopsWaiting_keepsTheInterruptFlag_andLeavesTheUnheardRowsUntouched() throws Exception {
        OutboxEvent answered = event("int.answered");
        OutboxEvent unheard = event("int.unheard");
        batch(answered, unheard);
        CountDownLatch bothSent = new CountDownLatch(1);
        sendFor("int.answered", OutboxConfirms::ack);
        sendFor("int.unheard", i -> {
            bothSent.countDown();
            return null;                                                // never answered
        });
        AtomicBoolean flagKept = new AtomicBoolean();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = pool.submit(() -> {
                relay(60_000).relayPendingEvents();
                flagKept.set(Thread.currentThread().isInterrupted());
            });
            assertThat(bothSent.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);                                           // let it reach the wait
            pool.shutdownNow();                                          // interrupts the relay thread

            run.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(flagKept.get()).as("the interrupt is not swallowed").isTrue();
        assertPublished(answered);
        assertThat(unheard.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(unheard.getAttempts()).as("an unheard outcome is not counted as a failed attempt").isZero();
        verify(repository, never()).save(unheard);
    }

    @Test
    void theWholeBatchWaitsAtMostOneDeadline_notOneDeadlinePerMessage() {
        OutboxEvent[] events = new OutboxEvent[50];
        for (int i = 0; i < events.length; i++) {
            events[i] = event("slow." + i);
        }
        batch(events);
        send(OutboxConfirms::silence);

        long started = System.nanoTime();
        relay(SHORT_DEADLINE_MS).relayPendingEvents();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).as("50 x %d ms would be %d ms", SHORT_DEADLINE_MS, 50 * SHORT_DEADLINE_MS)
                .isLessThan(SHORT_DEADLINE_MS * 10);
        for (OutboxEvent e : events) {
            assertRetryable(e, 1);
        }
    }

    @Test
    void aNonPositiveDeadlineIsRefusedSoThereIsNeverAnUnboundedOrZeroWait() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> relay(0)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> relay(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAnswerAlreadyThereIsReadEvenWhenTheDeadlineHasPassed() {
        OutboxEvent slowSend = event("dl.slow");
        OutboxEvent answeredLate = event("dl.ok");
        batch(slowSend, answeredLate);
        sendFor("dl.slow", i -> {
            try {
                Thread.sleep(SHORT_DEADLINE_MS + 200);                  // the sends alone outlast the deadline
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OutboxConfirms.ack(i);
        });
        sendFor("dl.ok", OutboxConfirms::ack);

        relay(SHORT_DEADLINE_MS).relayPendingEvents();

        assertPublished(slowSend);
        assertPublished(answeredLate);
    }

    // ---- 18: concurrent relay instances

    @Test
    void concurrentRelaysWithAsynchronousConfirmsEachSettleTheirOwnRowsOnly() throws Exception {
        List<OutboxEvent> aRows = new ArrayList<>();
        List<OutboxEvent> bRows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            aRows.add(event("conc.a." + i));
            bRows.add(event("conc.b." + i));
        }
        // each relay instance gets its own disjoint batch, as OBX-01's SKIP LOCKED guarantees
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(aRows).thenReturn(bRows);
        // the broker answers from other threads, after the sends: A's rows are nacked, B's confirmed
        send(i -> {
            String key = OutboxConfirms.routingKey(i);
            CorrelationData correlation = OutboxConfirms.correlation(i);
            broker.schedule(() -> correlation.getFuture()
                    .complete(new CorrelationData.Confirm(key.startsWith("conc.b."), key.startsWith("conc.b.") ? null : "rejected")),
                    5, TimeUnit.MILLISECONDS);
            return null;
        });
        OutboxRelay relayA = relay(10_000);
        OutboxRelay relayB = relay(10_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(relayA::relayPendingEvents);
            a.get(20, TimeUnit.SECONDS);
            Future<?> b = pool.submit(relayB::relayPendingEvents);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        aRows.forEach(e -> assertRetryable(e, 1));
        bRows.forEach(OutboxRelayConfirmationTest::assertPublished);
    }

    @Test
    void relaysRunningAtTheSameTimeDoNotMixUpTheirConfirmations() throws Exception {
        List<OutboxEvent> aRows = new ArrayList<>();
        List<OutboxEvent> bRows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            aRows.add(event("par.a." + i));
            bRows.add(event("par.b." + i));
        }
        OutboxEventRepository repoA = mock(OutboxEventRepository.class);
        OutboxEventRepository repoB = mock(OutboxEventRepository.class);
        when(repoA.findBatchToPublish(any(Pageable.class))).thenReturn(aRows);
        when(repoB.findBatchToPublish(any(Pageable.class))).thenReturn(bRows);
        CountDownLatch bothStarted = new CountDownLatch(2);
        send(i -> {
            String key = OutboxConfirms.routingKey(i);
            CorrelationData correlation = OutboxConfirms.correlation(i);
            bothStarted.countDown();
            broker.schedule(() -> correlation.getFuture()
                            .complete(new CorrelationData.Confirm(key.startsWith("par.b."), "rejected")),
                    10, TimeUnit.MILLISECONDS);
            return null;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> new OutboxRelay(repoA, rabbit, audit, 10_000).relayPendingEvents());
            Future<?> b = pool.submit(() -> new OutboxRelay(repoB, rabbit, audit, 10_000).relayPendingEvents());
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(bothStarted.getCount()).isZero();
        aRows.forEach(e -> assertRetryable(e, 1));
        bRows.forEach(OutboxRelayConfirmationTest::assertPublished);
    }
}
