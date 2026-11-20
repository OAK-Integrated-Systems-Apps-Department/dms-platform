package com.oak.dms.outbox;

import com.oak.dms.audit.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBX-02, the defect itself. Written against the ORIGINAL relay first (it compiles against both the original and the fixed
 * relay: it only uses the three-argument constructor). On the original, all three tests fail, because it marked a row
 * {@code published} as soon as {@code send} returned, whatever the broker said afterwards.
 * <p>
 * The broker is a controlled stand-in: a mocked {@link RabbitTemplate} that answers through the {@link CorrelationData} it is
 * handed (the way the real template reports confirms and returns), whichever {@code send} overload is called. These tests
 * show what the relay does with a given outcome; they do NOT prove how a real RabbitMQ behaves.
 */
class OutboxRelayObx02DefectTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);

    private OutboxEvent event() {
        return OutboxEvent.builder().outboxEventId(UUID.randomUUID()).tenantId(UUID.randomUUID()).aggregateType("test")
                .aggregateId(UUID.randomUUID()).eventType("user.created").payload("{\"k\":1}").build();
    }

    private OutboxEvent relayOnceWhenTheBrokerAnswers(org.mockito.stubbing.Answer<Object> brokerAnswer) {
        OutboxEvent event = event();
        when(repository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of(event));
        OutboxConfirms.stubInvoke(rabbit);
        // Whatever overload the relay uses, the broker's answer is delivered through the correlation if there is one.
        doAnswer(brokerAnswer).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        doAnswer(invocation -> null).when(rabbit).send(anyString(), anyString(), any(Message.class));

        new OutboxRelay(repository, rabbit, audit).relayPendingEvents();
        return event;
    }

    @Test
    void aBrokerNackMustNotMarkTheRowPublished() {
        OutboxEvent e = relayOnceWhenTheBrokerAnswers(OutboxConfirms::nack);

        assertThat(e.getStatus()).as("a nacked publish is not delivered").isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getPublishedAt()).isNull();
    }

    @Test
    void anUnroutableMessageMustNotMarkTheRowPublished() {
        OutboxEvent e = relayOnceWhenTheBrokerAnswers(OutboxConfirms::returnedThenAck);

        assertThat(e.getStatus()).as("a returned (unroutable) message is not delivered").isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
    }

    @Test
    void aPublishThatIsNeverConfirmedMustNotMarkTheRowPublished() {
        OutboxEvent e = relayOnceWhenTheBrokerAnswers(OutboxConfirms::silence);

        assertThat(e.getStatus()).as("an unconfirmed publish is not known to be delivered").isEqualTo(OutboxEvent.Status.pending);
        assertThat(e.getAttempts()).isEqualTo(1);
    }
}
