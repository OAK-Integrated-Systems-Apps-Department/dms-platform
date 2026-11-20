package com.oak.dms.outbox;

import org.mockito.invocation.InvocationOnMock;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Test double for "what the broker answers to a publish", for a mocked {@link RabbitTemplate}. The relay passes a
 * {@link CorrelationData} as the fourth argument of {@code send}; the real template completes that object's future when the
 * broker confirms (or nacks) and sets {@code returned} on it when the message comes back unroutable. These helpers do the
 * same thing from a mock, deterministically.
 * <p>
 * They simulate controlled publisher outcomes. They do NOT prove how a real RabbitMQ answers - that is
 * {@code OutboxRelayBrokerIT}'s job.
 */
final class OutboxConfirms {

    private OutboxConfirms() {
    }

    static CorrelationData correlation(InvocationOnMock invocation) {
        return invocation.getArgument(3);
    }

    static String routingKey(InvocationOnMock invocation) {
        return invocation.getArgument(1);
    }

    /** The broker confirms the publish. */
    static Object ack(InvocationOnMock invocation) {
        correlation(invocation).getFuture().complete(new CorrelationData.Confirm(true, null));
        return null;
    }

    /** The broker negatively acknowledges the publish. */
    static Object nack(InvocationOnMock invocation) {
        correlation(invocation).getFuture().complete(new CorrelationData.Confirm(false, "queue full"));
        return null;
    }

    /** The broker returns the message as unroutable and then (as it does) confirms the publish. */
    static Object returnedThenAck(InvocationOnMock invocation) {
        CorrelationData correlation = correlation(invocation);
        Message message = invocation.getArgument(2);
        correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", invocation.getArgument(0), routingKey(invocation)));
        correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        return null;
    }

    /** The broker never answers. */
    static Object silence(InvocationOnMock invocation) {
        return null;
    }

    /** The database clock the relay reads when it schedules a retry: the real time, as a real database would say. */
    static void stubClock(OutboxEventRepository repository) {
        when(repository.databaseClockMillis()).thenAnswer(invocation -> System.currentTimeMillis());
    }

    /** Every publish is confirmed. */
    static void stubAck(RabbitTemplate template) {
        stubInvoke(template);
        doAnswer(OutboxConfirms::ack).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    /**
     * The relay publishes a batch inside {@code template.invoke(...)} (one dedicated channel). A mock does not run the
     * callback by itself: this makes {@code invoke} hand the callback the same mock, so the {@code send} stubs apply.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void stubInvoke(RabbitTemplate template) {
        doAnswer(invocation -> ((RabbitOperations.OperationsCallback) invocation.getArgument(0)).doInRabbit(template))
                .when(template).invoke(any(RabbitOperations.OperationsCallback.class));
    }
}
