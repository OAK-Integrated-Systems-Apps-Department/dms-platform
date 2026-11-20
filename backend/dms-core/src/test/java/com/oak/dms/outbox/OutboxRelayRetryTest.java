package com.oak.dms.outbox;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBX-03 without a database or a broker: what the relay writes onto a row that failed to publish - the attempt, the error,
 * and WHEN it may be tried again (database clock + backoff) - and when it gives up. The database clock is fixed and the jitter
 * is switched off, so every retry time is an exact expectation. The SQL that honours those times is
 * {@code OutboxRetrySchedulingIT}'s job.
 */
class OutboxRelayRetryTest {

    private static final long NOW_MS = 1_800_000_000_000L;                      // the database clock in these tests
    private static final Instant NOW = Instant.ofEpochMilli(NOW_MS);

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);

    {
        OutboxConfirms.stubInvoke(rabbit);
        when(repository.databaseClockMillis()).thenReturn(NOW_MS);
    }

    private OutboxRelay relay(OutboxRetryPolicy policy) {
        return new OutboxRelay(repository, rabbit, audit, 300, policy);
    }

    private static OutboxRetryPolicy exact() {
        return new OutboxRetryPolicy(10_000, 15 * 60_000, 0.2, 24 * 3_600_000L, 150, () -> 0.5);
    }

    private OutboxEvent event(String type, int attempts) {
        OutboxEvent e = OutboxEvent.builder().outboxEventId(UUID.randomUUID()).tenantId(UUID.randomUUID()).aggregateType("test")
                .aggregateId(UUID.randomUUID()).eventType(type).payload("{\"k\":1}").attempts(attempts)
                .createdAt(OffsetDateTime.ofInstant(NOW.minusSeconds(60), java.time.ZoneOffset.UTC)).build();
        return e;
    }

    private void batch(OutboxEvent... events) {
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of(events));
    }

    private void send(String key, org.mockito.stubbing.Answer<Object> answer) {
        doAnswer(answer).when(rabbit).send(anyString(), eq(key), any(Message.class), any(CorrelationData.class));
    }

    private static Instant nextAttempt(OutboxEvent e) {
        return e.getNextAttemptAt().toInstant();
    }

    // ---- scheduling

    @Test
    void theFirstFailureSchedulesTheNextAttemptTenSecondsAfterTheDatabaseClock() {
        OutboxEvent e = event("a.1", 0);
        batch(e);
        send("a.1", OutboxConfirms::nack);

        relay(exact()).relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(nextAttempt(e)).isEqualTo(NOW.plusSeconds(10));
        assertThat(e.getLastError()).isNotBlank();
        verify(repository).save(e);
    }

    @Test
    void theDelayGrowsWithTheNumberOfFailedAttempts_andIsCapped() {
        OutboxEvent second = event("g.2", 1);
        OutboxEvent fifth = event("g.5", 4);
        OutboxEvent tenth = event("g.10", 9);
        OutboxEvent hundredth = event("g.100", 99);
        batch(second, fifth, tenth, hundredth);
        OutboxConfirms.stubAck(rabbit);
        doAnswer(OutboxConfirms::nack).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay(exact()).relayPendingEvents();

        assertThat(nextAttempt(second)).as("attempt 2 failed: 20 s").isEqualTo(NOW.plusSeconds(20));
        assertThat(nextAttempt(fifth)).as("attempt 5 failed: 160 s").isEqualTo(NOW.plusSeconds(160));
        assertThat(nextAttempt(tenth)).as("attempt 10 failed: capped at 15 min").isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(nextAttempt(hundredth)).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void aRetryIsNeverScheduledInThePast() {
        OutboxEvent e = event("past.1", 3);
        batch(e);
        send("past.1", OutboxConfirms::silence);

        relay(new OutboxRetryPolicy(1, 1, 0.99, 86_400_000L, 150, () -> 0.0)).relayPendingEvents();

        assertThat(nextAttempt(e)).isAfter(NOW);
    }

    @Test
    void theRetryIsMeasuredFromTheDatabaseClockAfterTheBatch_notFromTheApplicationClock() {
        OutboxEvent e = event("clock.1", 0);
        batch(e);
        send("clock.1", OutboxConfirms::nack);
        when(repository.databaseClockMillis()).thenReturn(NOW_MS + 7_000);      // the database says it is 7 s later

        relay(exact()).relayPendingEvents();

        assertThat(nextAttempt(e)).isEqualTo(NOW.plusSeconds(7).plusSeconds(10));
    }

    @Test
    void ifTheDatabaseClockCannotBeReadTheWholeBatchIsRolledBack_noApplicationClockIsSubstituted() {
        OutboxEvent ok = event("clk.ok", 0);
        OutboxEvent bad = event("clk.bad", 0);
        batch(ok, bad);
        send("clk.ok", OutboxConfirms::ack);
        send("clk.bad", OutboxConfirms::nack);
        when(repository.databaseClockMillis()).thenThrow(new IllegalStateException("no clock"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> relay(exact()).relayPendingEvents())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no clock");

        verify(repository, never()).save(any());                       // nothing is recorded: the transaction rolls back
        assertThat(bad.getNextAttemptAt()).isNull();
        assertThat(bad.getFirstFailedAt()).isNull();
    }

    // ---- the retry window starts at the first failed attempt

    @Test
    void anEventThatWaitedForTheRelayForDaysStillGetsTheWholeWindowAfterItsFirstFailure() {
        OutboxEvent e = event("late.1", 0);
        e.setCreatedAt(OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(25)), java.time.ZoneOffset.UTC));   // pending 25 h
        batch(e);
        send("late.1", OutboxConfirms::nack);

        relay(exact()).relayPendingEvents();

        assertThat(e.getStatus()).as("not failed: its window has only just started").isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getFirstFailedAt().toInstant()).isEqualTo(NOW);
        assertThat(nextAttempt(e)).isEqualTo(NOW.plusSeconds(10));
        verify(audit, never()).event(any());
    }

    @Test
    void theWindowOriginIsSetOnTheFirstFailureAndNeverMoved() {
        OutboxEvent e = event("origin.1", 0);
        batch(e);
        send("origin.1", OutboxConfirms::nack);
        relay(exact()).relayPendingEvents();
        OffsetDateTime first = e.getFirstFailedAt();
        when(repository.databaseClockMillis()).thenReturn(NOW_MS + 600_000);        // ten minutes later, the retry fails again

        relay(exact()).relayPendingEvents();

        assertThat(e.getAttempts()).isEqualTo(2);
        assertThat(e.getFirstFailedAt()).as("the origin does not move with later failures").isEqualTo(first);
    }

    @Test
    void aRowThatAlreadyHadAttemptsBeforeTheColumnExistedGetsItsWindowFromTheFirstFailureTheRelayObserves() {
        OutboxEvent legacy = event("legacy.1", 3);                                   // attempts, but first_failed_at is NULL
        batch(legacy);
        send("legacy.1", OutboxConfirms::nack);

        relay(exact()).relayPendingEvents();

        assertThat(legacy.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(legacy.getAttempts()).isEqualTo(4);
        assertThat(legacy.getFirstFailedAt().toInstant()).as("nothing is invented for the past").isEqualTo(NOW);
    }

    @Test
    void aWindowOriginInTheFutureFromClockSkewIsNeverExhausted() {
        OutboxEvent e = event("skew.1", 1);
        e.setFirstFailedAt(OffsetDateTime.ofInstant(NOW.plus(Duration.ofHours(2)), java.time.ZoneOffset.UTC));
        batch(e);
        send("skew.1", OutboxConfirms::nack);

        relay(exact()).relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
    }

    // ---- every failure outcome is retried, none is assumed permanent

    @Test
    void aNack_aReturn_aTimeout_aThrownSend_andAnUnopenableChannelAreAllRetried_withOneAttemptCounted() {
        OutboxEvent nacked = event("k.nack", 0);
        OutboxEvent returned = event("k.return", 0);
        OutboxEvent silent = event("k.silent", 0);
        OutboxEvent thrown = event("k.thrown", 0);
        batch(nacked, returned, silent, thrown);
        send("k.nack", OutboxConfirms::nack);
        send("k.return", OutboxConfirms::returnedThenAck);
        send("k.silent", OutboxConfirms::silence);
        doThrow(new AmqpConnectException(new ConnectException("refused"))).when(rabbit)
                .send(anyString(), eq("k.thrown"), any(Message.class), any(CorrelationData.class));

        relay(exact()).relayPendingEvents();

        for (OutboxEvent e : List.of(nacked, returned, silent, thrown)) {
            assertThat(e.getStatus()).as(e.getEventType()).isEqualTo(OutboxEvent.Status.pending);
            assertThat(e.getAttempts()).as(e.getEventType()).isEqualTo(1);
            assertThat(nextAttempt(e)).as(e.getEventType()).isEqualTo(NOW.plusSeconds(10));
        }
        verify(audit, never()).event(any());
    }

    @Test
    void aChannelThatCannotBeOpenedSchedulesEveryRowOfTheBatch() {
        OutboxEvent a = event("nochan.a", 0);
        OutboxEvent b = event("nochan.b", 2);
        batch(a, b);
        doThrow(new AmqpConnectException(new ConnectException("refused"))).when(rabbit)
                .invoke(any(RabbitOperations.OperationsCallback.class));

        relay(exact()).relayPendingEvents();

        assertThat(nextAttempt(a)).isEqualTo(NOW.plusSeconds(10));
        assertThat(nextAttempt(b)).as("its third failure").isEqualTo(NOW.plusSeconds(40));
    }

    @Test
    void anUnroutableEventKeepsBeingRetriedInsideTheWindow_soAWorkerThatStartsLaterStillGetsIt() {
        OutboxEvent e = event("document.version.ocr.requested", 12);
        batch(e);
        send("document.version.ocr.requested", OutboxConfirms::returnedThenAck);

        relay(exact()).relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(13);
        assertThat(nextAttempt(e)).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(e.getLastError()).contains("NO_ROUTE");
    }

    @Test
    void aSuccessfulPublishDoesNotTouchTheRetryTimeOrReadTheClock() {
        OutboxEvent e = event("ok.1", 3);
        batch(e);
        OutboxConfirms.stubAck(rabbit);

        relay(exact()).relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(e.getAttempts()).isEqualTo(3);
        assertThat(e.getNextAttemptAt()).isNull();
        verify(repository, never()).databaseClockMillis();
    }

    // ---- giving up

    @Test
    void anEventOlderThanTheRetryWindowFailsForGoodAndIsAudited() {
        OutboxEvent e = event("old.1", 40);
        e.setFirstFailedAt(OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(25)), java.time.ZoneOffset.UTC));
        batch(e);
        send("old.1", OutboxConfirms::nack);

        relay(exact()).relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.failed);
        assertThat(e.getAttempts()).isEqualTo(41);
        assertThat(e.getNextAttemptAt()).as("a failed event has no next attempt").isNull();
        verify(audit).event(AuditActionCatalog.OUTBOX_EVENT_PUBLISH_FAILED);
    }

    @Test
    void aFailureExactlyAtTheWindowBoundaryIsTerminal_oneMillisecondEarlierSchedulesTheFinalAttempt() {
        OutboxEvent atBoundary = event("edge.at", 5);
        atBoundary.setFirstFailedAt(OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(24)), java.time.ZoneOffset.UTC));
        OutboxEvent justInside = event("edge.in", 5);
        justInside.setFirstFailedAt(OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(24)).plusMillis(1), java.time.ZoneOffset.UTC));
        batch(atBoundary, justInside);
        send("edge.at", OutboxConfirms::nack);
        send("edge.in", OutboxConfirms::nack);

        relay(OutboxRetryPolicy.defaults()).relayPendingEvents();

        assertThat(atBoundary.getStatus()).as("age == window: exhausted").isEqualTo(OutboxEvent.Status.failed);
        assertThat(justInside.getStatus()).as("one millisecond inside: the final attempt is allowed").isEqualTo(OutboxEvent.Status.pending);
        assertThat(nextAttempt(justInside)).as("and it may fall after the window ends, by at most the largest delay")
                .isBetween(NOW.plusMillis(1), NOW.plus(OutboxRetryPolicy.defaults().maxActualDelay()));
        assertThat(justInside.getAttempts()).as("attempts are never reset").isEqualTo(6);
    }

    @Test
    void anEventThatIsPendingForMoreThanTheWindowButHasNeverFailedIsNotExhaustedBySimplyBeingOld() {
        OutboxEvent e = event("old.never.failed", 0);
        e.setCreatedAt(OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(30)), java.time.ZoneOffset.UTC));
        batch(e);
        OutboxConfirms.stubAck(rabbit);

        relay(OutboxRetryPolicy.defaults()).relayPendingEvents();

        assertThat(e.getStatus()).as("published: age alone never fails an event").isEqualTo(OutboxEvent.Status.published);
        assertThat(e.getFirstFailedAt()).isNull();
        verify(repository, never()).databaseClockMillis();
    }

    @Test
    void anEventInsideTheWindowIsNotFailedHoweverManyTimesItHasFailed() {
        OutboxEvent e = event("many.1", 100);
        e.setFirstFailedAt(OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(23)), java.time.ZoneOffset.UTC));
        batch(e);
        send("many.1", OutboxConfirms::nack);

        relay(exact()).relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        verify(audit, never()).event(any());
    }

    @Test
    void theDefaultRunawayGuardFailsTheEventOnItsHundredAndFiftiethFailure_andNotBefore() {
        OutboxEvent last = event("guard.149", 149);
        OutboxEvent before = event("guard.148", 148);
        batch(last, before);
        send("guard.149", OutboxConfirms::nack);
        send("guard.148", OutboxConfirms::nack);

        relay(OutboxRetryPolicy.defaults()).relayPendingEvents();

        assertThat(last.getStatus()).isEqualTo(OutboxEvent.Status.failed);
        assertThat(last.getAttempts()).isEqualTo(150);
        assertThat(before.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(before.getAttempts()).isEqualTo(149);
    }

    @Test
    void theRunawayGuardFailsAnEventThatHasUsedAllItsAttempts() {
        OutboxEvent e = event("guard.1", 2);
        batch(e);
        send("guard.1", OutboxConfirms::nack);

        relay(new OutboxRetryPolicy(10_000, 900_000, 0.2, 86_400_000L, 3, () -> 0.5)).relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.failed);
        assertThat(e.getAttempts()).isEqualTo(3);
        verify(audit).event(AuditActionCatalog.OUTBOX_EVENT_PUBLISH_FAILED);
    }

    @Test
    void aPayloadThatCannotBeEncodedFailsAtOnce_becauseRetryingCannotChangeIt() {
        OutboxEvent e = event("bad.payload", 0);
        e.setPayload(null);
        batch(e);

        relay(exact()).relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.failed);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).contains("payload cannot be encoded");
        verify(audit).event(AuditActionCatalog.OUTBOX_EVENT_PUBLISH_FAILED);
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    // ---- batches

    @Test
    void aMixedBatchGivesEachRowItsOwnOutcomeAndItsOwnRetryTime() {
        OutboxEvent ok = event("mix.ok", 0);
        OutboxEvent firstFailure = event("mix.first", 0);
        OutboxEvent thirdFailure = event("mix.third", 2);
        OutboxEvent expired = event("mix.expired", 5);
        expired.setFirstFailedAt(OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(30)), java.time.ZoneOffset.UTC));
        batch(ok, firstFailure, thirdFailure, expired);
        send("mix.ok", OutboxConfirms::ack);
        send("mix.first", OutboxConfirms::nack);
        send("mix.third", OutboxConfirms::returnedThenAck);
        send("mix.expired", OutboxConfirms::nack);

        relay(exact()).relayPendingEvents();

        assertThat(ok.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(nextAttempt(firstFailure)).isEqualTo(NOW.plusSeconds(10));
        assertThat(nextAttempt(thirdFailure)).isEqualTo(NOW.plusSeconds(40));
        assertThat(expired.getStatus()).isEqualTo(OutboxEvent.Status.failed);
        verify(audit, org.mockito.Mockito.times(1)).event(AuditActionCatalog.OUTBOX_EVENT_PUBLISH_FAILED);
    }

    @Test
    void anInterruptedWaitLeavesTheUnheardRowsExactlyAsTheyWere_noAttemptAndNoNewRetryTime() throws Exception {
        OutboxEvent unheard = event("int.unheard", 2);
        batch(unheard);
        send("int.unheard", OutboxConfirms::silence);
        Thread worker = new Thread(() -> new OutboxRelay(repository, rabbit, audit, 60_000, exact()).relayPendingEvents());
        worker.start();
        Thread.sleep(500);
        worker.interrupt();
        worker.join(10_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(unheard.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(unheard.getAttempts()).isEqualTo(2);
        assertThat(unheard.getNextAttemptAt()).isNull();
        verify(repository, never()).save(unheard);
    }

    @Test
    void theMessageOnTheWireIsUnchanged() {
        OutboxEvent e = event("wire.1", 0);
        batch(e);
        List<Message> sent = new java.util.ArrayList<>();
        send("wire.1", i -> {
            sent.add(i.getArgument(2));
            return OutboxConfirms.ack(i);
        });

        relay(exact()).relayPendingEvents();

        assertThat(new String(sent.get(0).getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("{\"k\":1}");
        assertThat(sent.get(0).getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(sent.get(0).getMessageProperties().getDeliveryMode().name()).isEqualTo("PERSISTENT");
    }
}
