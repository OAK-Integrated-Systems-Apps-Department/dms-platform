package com.oak.dms.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.messaging.RabbitMqConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks down the BYTES {@link OutboxRelay} puts on the wire.
 * <p>
 * This is the test that was missing when both directions of the Phase 1
 * OCR queue path silently died in the live stack. The relay published
 * {@code convertAndSend(exchange, routingKey, event.getPayload())} -- a
 * {@code String} through a {@link Jackson2JsonMessageConverter}, which
 * serialized the already-serialized payload a SECOND time into a quoted
 * JSON string literal. Every consumer then got a string where an object
 * was expected: dms-core's own {@code OcrEventsConsumer} dead-lettered
 * every {@code ocr.completed} event with {@code MismatchedInputException},
 * and ocr-worker's Python consumer blew up with {@code AttributeError:
 * 'str' object has no attribute 'get'} on every {@code ocr.requested}.
 * <p>
 * Both existing tests missed it by testing either side of the relay but
 * never the relay itself: {@code DocumentUploadOcrQueueingIT} asserts the
 * outbox ROW is written and stops there, and
 * {@code OcrEventsConsumerLiveIT} publishes a {@code Map} by hand (which
 * the converter encodes correctly) instead of letting the relay publish.
 * So these assertions are deliberately about the wire format, not about
 * the relay's bookkeeping.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayWireFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PAYLOAD_JSON = """
            {"schema_version":1,\
            "event_type":"document.version.ocr.requested",\
            "tenant_id":"7c0b3d6c-2c1f-4c69-9b4a-8d5a4ab4bd21",\
            "document_id":"3b1e0b8e-4a0f-4a2f-9a4b-2c5e6f7a8b9c",\
            "version_id":"9f8e7d6c-5b4a-4938-8271-615049382716",\
            "correlation_id":null}""";

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private AuditService auditService;

    private OutboxEvent pendingEvent() {
        return OutboxEvent.builder()
                .outboxEventId(UUID.randomUUID())
                .tenantId(UUID.fromString("7c0b3d6c-2c1f-4c69-9b4a-8d5a4ab4bd21"))
                .aggregateType("document")
                .aggregateId(UUID.fromString("3b1e0b8e-4a0f-4a2f-9a4b-2c5e6f7a8b9c"))
                .eventType(RabbitMqConfig.ROUTING_KEY_OCR_COMPLETED)
                .payload(PAYLOAD_JSON)
                .build();
    }

    private Message relayOneAndCaptureMessage() {
        OutboxEvent event = pendingEvent();
        when(outboxEventRepository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxRelay(outboxEventRepository, rabbitTemplate, auditService).relayPendingEvents();

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                eq(RabbitMqConfig.DMS_EVENTS_EXCHANGE),
                eq(RabbitMqConfig.ROUTING_KEY_OCR_COMPLETED),
                captor.capture());
        return captor.getValue();
    }

    @Test
    void publishesThePayloadAsARawJsonObjectNotAReEncodedString() throws Exception {
        Message message = relayOneAndCaptureMessage();

        JsonNode body = MAPPER.readTree(new String(message.getBody(), StandardCharsets.UTF_8));

        assertThat(body.isObject())
                .as("wire body must be the JSON object itself, not a quoted string containing it")
                .isTrue();
        assertThat(body).isEqualTo(MAPPER.readTree(PAYLOAD_JSON));
        assertThat(body.get("version_id").asText()).isEqualTo("9f8e7d6c-5b4a-4938-8271-615049382716");
    }

    @Test
    void marksTheBodyAsPersistentJson() {
        Message message = relayOneAndCaptureMessage();
        MessageProperties properties = message.getMessageProperties();

        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getContentEncoding()).isEqualTo("UTF-8");
        assertThat(properties.getDeliveryMode())
                .as("an outbox event that survives a broker restart is the whole point of the pattern")
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void setsNoTypeIdHeaderSoEachListenersOwnParameterTypeGoverns() {
        Message message = relayOneAndCaptureMessage();

        assertThat(message.getMessageProperties().getHeaders())
                .as("__TypeId__=java.lang.String was the fingerprint of the double-encoding bug")
                .doesNotContainKey("__TypeId__");
    }

    @Test
    void aRealJacksonConverterDeserializesTheWireBodyIntoAMap() {
        Message message = relayOneAndCaptureMessage();

        Object converted = new Jackson2JsonMessageConverter().fromMessage(message);

        assertThat(converted)
                .as("OcrEventsConsumer.onMessage takes Map<String, Object> -- this is what it will actually receive")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = (Map<String, Object>) converted;
        assertThat(asMap).containsEntry("event_type", "document.version.ocr.requested");
        assertThat(asMap).containsEntry("version_id", "9f8e7d6c-5b4a-4938-8271-615049382716");
    }

    @Test
    void theOldConvertAndSendPathWouldHaveProducedAStringWhichIsExactlyTheBugThisPrevents() {
        // Not testing production code -- pinning down WHY the relay must not
        // use the converter, so nobody "simplifies" jsonMessage() back into a
        // convertAndSend(String) call. If Spring AMQP ever changes this
        // behavior, this test fails and the comment gets revisited.
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();

        Message doubleEncoded = converter.toMessage(PAYLOAD_JSON, new MessageProperties());
        Object converted = converter.fromMessage(doubleEncoded);

        assertThat(converted)
                .as("a String payload round-trips through the converter as a String, never as a Map")
                .isInstanceOf(String.class);
        assertThat(doubleEncoded.getMessageProperties().getHeaders())
                .containsEntry("__TypeId__", "java.lang.String");
    }

    @Test
    void marksTheRowPublishedOnSuccess() {
        OutboxEvent event = pendingEvent();
        when(outboxEventRepository.findBatchToPublish(any(Pageable.class))).thenReturn(List.of(event));

        new OutboxRelay(outboxEventRepository, rabbitTemplate, auditService).relayPendingEvents();

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.published);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
        verify(outboxEventRepository).save(event);
    }
}
