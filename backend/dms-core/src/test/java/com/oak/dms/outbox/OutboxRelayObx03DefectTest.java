package com.oak.dms.outbox;

import com.oak.dms.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.net.ConnectException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * OBX-03, the defect itself. Written against the ORIGINAL (OBX-02) source first; it compiles against both the original and
 * the fixed relay because it uses only the three-argument constructor and reflection. On the original, all five tests fail:
 * a failed publish is retried at once, and the fifth failure - about eight seconds into a broker outage - permanently fails
 * an event that was perfectly recoverable.
 * <p>
 * The repository is a fake that behaves like the SQL does for the one thing a mock can show: only {@code pending} rows come
 * back. (Retry-time filtering lives in the SQL; {@code OutboxRetrySchedulingIT} covers it on PostgreSQL.)
 */
class OutboxRelayObx03DefectTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
    private final OutboxRelay relay = new OutboxRelay(repository, rabbit, audit);

    private OutboxEvent event(String type) {
        return OutboxEvent.builder().outboxEventId(UUID.randomUUID()).tenantId(UUID.randomUUID()).aggregateType("test")
                .aggregateId(UUID.randomUUID()).eventType(type).payload("{\"k\":1}").build();
    }

    private void repositoryReturnsTheEventWhileItIsPending(OutboxEvent e) {
        when(repository.findBatchToPublish(any(Pageable.class)))
                .thenAnswer(i -> e.getStatus() == OutboxEvent.Status.pending ? List.of(e) : List.of());
    }

    private void brokerIsDown() {
        reset(rabbit);
        OutboxConfirms.stubInvoke(rabbit);
        doThrow(new AmqpConnectException(new ConnectException("refused"))).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private void brokerIsBackAndConfirms() {
        reset(rabbit);
        OutboxConfirms.stubAck(rabbit);
    }

    @Test
    void fiveQuickFailuresDuringAShortOutageMustNotPermanentlyFailARecoverableEvent() {
        OutboxEvent e = event("ocr.requested");
        repositoryReturnsTheEventWhileItIsPending(e);
        brokerIsDown();

        for (int poll = 1; poll <= 5; poll++) {                    // five polls = about eight seconds at the 2 s interval
            relay.relayPendingEvents();
        }

        assertThat(e.getStatus()).as("a broker outage of seconds must not end an event's life").isEqualTo(OutboxEvent.Status.pending);
    }

    @Test
    void aBrokerThatRecoversMustStillGetTheEventsThatFailedDuringTheOutage() {
        OutboxEvent e = event("ocr.requested");
        repositoryReturnsTheEventWhileItIsPending(e);
        brokerIsDown();
        for (int poll = 1; poll <= 5; poll++) {
            relay.relayPendingEvents();
        }

        brokerIsBackAndConfirms();
        relay.relayPendingEvents();

        assertThat(e.getStatus()).as("delivered once the broker is back").isEqualTo(OutboxEvent.Status.published);
    }

    @Test
    void anUnroutableEventMustNotUseUpItsWholeRetryBudgetInSeconds() {
        OutboxEvent e = event("document.version.ocr.requested");   // the worker's queue does not exist yet
        repositoryReturnsTheEventWhileItIsPending(e);
        OutboxConfirms.stubInvoke(rabbit);
        doAnswer(OutboxConfirms::returnedThenAck).when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        for (int poll = 1; poll <= 5; poll++) {
            relay.relayPendingEvents();
        }

        assertThat(e.getStatus()).as("the worker may simply not have started yet").isEqualTo(OutboxEvent.Status.pending);
    }

    @Test
    void aFailedPublishMustScheduleItsNextAttempt_notBeRetriedAtTheNextPoll() throws Exception {
        assertThat(OutboxEvent.class.getDeclaredField("nextAttemptAt"))
                .as("the row needs a retry time, or every failure is retried at the next 2 s poll").isNotNull();
    }

    @Test
    void theBatchQueryMustSelectOnlyEventsWhoseRetryTimeHasArrived() throws Exception {
        String sql = OutboxEventRepository.class.getDeclaredMethod("lockPendingBatch", int.class)
                .getAnnotation(Query.class).value().replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql).as("without it an event waiting for its retry would be selected - and block the eligible ones behind it")
                .contains("next_attempt_at <= now()");
    }
}
