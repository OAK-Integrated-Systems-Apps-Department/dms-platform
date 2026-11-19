package com.oak.dms.outbox;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.messaging.RabbitMqConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBX-01 work must leave what the relay does with the rows it has been handed exactly as it was: success marks a row
 * published, a failed send counts an attempt, a row older than the retry window fails for good and is audited, one bad row does
 * not stop the others, rows go out in the order the repository returned them. Mock-based, so no database; the
 * locking itself is covered by {@code OutboxEventRepositoryQueryTest} and {@code OutboxRelayConcurrencyIT}.
 */
class OutboxRelayBehaviourTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
    private final OutboxRelay relay = new OutboxRelay(repository, rabbit, audit);

    private OutboxEvent event(String type) {
        return OutboxEvent.builder().outboxEventId(UUID.randomUUID()).tenantId(UUID.randomUUID()).aggregateType("test")
                .aggregateId(UUID.randomUUID()).eventType(type).payload("{\"k\":1}").build();
    }

    private void batch(OutboxEvent... events) {
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of(events));
    }

    {
        OutboxConfirms.stubClock(repository);                   // OBX-03: the database clock the relay schedules retries on
    }

    @BeforeEach
    void noBatchByDefault() {
        OutboxConfirms.stubAck(rabbit);                         // OBX-02: a send is only a success once the broker confirms
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of());
    }

    @Test
    void aSuccessfulSendMarksTheRowPublished() {
        OutboxEvent e = event("a.b");
        batch(e);

        relay.relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(e.getPublishedAt()).isNotNull();
        assertThat(e.getAttempts()).isZero();
        verify(repository).save(e);
    }

    @Test
    void theRelayAsksForTheFirstPageOfTheBatchSize() {
        relay.relayPendingEvents();

        verify(repository).findBatchToPublish(PageRequest.of(0, 100));
    }

    @Test
    void anEmptyBatchCompletesWithoutSendingAnythingOrSavingAnything() {
        relay.relayPendingEvents();

        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(repository, never()).save(any());
    }

    @Test
    void rowsAreSentInTheOrderTheRepositoryReturnedThem() {
        OutboxEvent first = event("order.1");
        OutboxEvent second = event("order.2");
        OutboxEvent third = event("order.3");
        batch(first, second, third);
        List<String> sent = new ArrayList<>();
        org.mockito.Mockito.doAnswer(i -> { sent.add(i.getArgument(1)); return OutboxConfirms.ack(i); }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.relayPendingEvents();

        assertThat(sent).containsExactly("order.1", "order.2", "order.3");
    }

    @Test
    void aFailedSendCountsAnAttemptAndLeavesTheRowPending() {
        OutboxEvent e = event("fail.once");
        batch(e);
        doThrow(new AmqpConnectException(new ConnectException("refused"))).when(rabbit).send(anyString(), eq("fail.once"), any(Message.class), any(CorrelationData.class));

        relay.relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).isNotBlank();
        assertThat(e.getPublishedAt()).isNull();
        verify(repository).save(e);
        verify(audit, never()).event(any());
    }

    @Test
    void aRowOlderThanTheRetryWindowFailsForGoodAndIsAudited() {
        OutboxEvent e = event("fail.always");
        e.setAttempts(4);
        e.setFirstFailedAt(java.time.OffsetDateTime.now().minusHours(25));
        batch(e);
        doThrow(new IllegalStateException("broker down")).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.failed);
        assertThat(e.getAttempts()).isEqualTo(5);
        verify(audit).event(AuditActionCatalog.OUTBOX_EVENT_PUBLISH_FAILED);
    }

    @Test
    void aRowBelowTheMaximumStaysPendingAndIsNotAudited() {
        OutboxEvent e = event("fail.fourth");
        e.setAttempts(3);
        batch(e);
        doThrow(new IllegalStateException("broker down")).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.relayPendingEvents();

        assertThat(e.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(4);
        verify(audit, never()).event(any());
    }

    @Test
    void oneFailingRowDoesNotStopTheOthersInTheSameBatch() {
        OutboxEvent good1 = event("mix.good1");
        OutboxEvent bad = event("mix.bad");
        OutboxEvent good2 = event("mix.good2");
        batch(good1, bad, good2);
        doThrow(new IllegalStateException("nope")).when(rabbit).send(anyString(), eq("mix.bad"), any(Message.class), any(CorrelationData.class));

        relay.relayPendingEvents();

        assertThat(good1.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(bad.getStatus()).isEqualTo(OutboxEvent.Status.pending);
        assertThat(bad.getAttempts()).isEqualTo(1);
        assertThat(good2.getStatus()).isEqualTo(OutboxEvent.Status.published);
    }

    @Test
    void theRoutingKeyAndExchangeAreTheEventTypeAndTheEventsExchange() {
        OutboxEvent e = event(RabbitMqConfig.ROUTING_KEY_OCR_COMPLETED);
        batch(e);

        relay.relayPendingEvents();

        verify(rabbit).send(eq(RabbitMqConfig.DMS_EVENTS_EXCHANGE), eq(RabbitMqConfig.ROUTING_KEY_OCR_COMPLETED), any(Message.class), any(CorrelationData.class));
    }
}
