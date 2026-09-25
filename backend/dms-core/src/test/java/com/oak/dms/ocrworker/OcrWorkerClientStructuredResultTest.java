package com.oak.dms.ocrworker;

import com.oak.dms.common.exception.StructuredExtractionUnavailableException;
import com.oak.dms.common.exception.UpstreamServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link OcrWorkerClient}'s structured-result calls against a mocked
 * ocr-worker HTTP API: status-code mapping and the internal token.
 */
class OcrWorkerClientStructuredResultTest {

    private static final String BASE = "http://ocr-worker:8090";

    private MockRestServiceServer server;
    private OcrWorkerClient client;
    private final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        OcrWorkerProperties properties = new OcrWorkerProperties();
        properties.setBaseUrl(BASE);
        properties.setInternalToken("internal-token");
        client = new OcrWorkerClient(restTemplate, properties);
    }

    @Test
    void getReturnsTheWorkersJsonAndSendsTheInternalToken() {
        server.expect(requestTo(BASE + "/ocr/structured/" + versionId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "internal-token"))
                .andRespond(withSuccess("{\"status\":\"completed\",\"fields\":{\"employee_id\":\"EMP-1023\"}}", MediaType.APPLICATION_JSON));

        Map<String, Object> result = client.getStructuredResult(versionId);

        assertThat(result).containsEntry("status", "completed");
        assertThat(result.get("fields")).isEqualTo(Map.of("employee_id", "EMP-1023"));
        server.verify();
    }

    @Test
    void getMapsWorker404ToNull() {
        server.expect(requestTo(BASE + "/ocr/structured/" + versionId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"no structured result\"}"));

        assertThat(client.getStructuredResult(versionId)).isNull();
    }

    @Test
    void getMapsWorker5xxToUpstreamServiceException() {
        server.expect(requestTo(BASE + "/ocr/structured/" + versionId)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getStructuredResult(versionId)).isInstanceOf(UpstreamServiceException.class);
    }

    @Test
    void postQueuesWithoutWaitingAndReturnsThePendingResult() {
        server.expect(requestTo(BASE + "/ocr/structured/" + versionId))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "internal-token"))
                .andExpect(content().json("{\"wait\":false}"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"pending\"}"));

        assertThat(client.requestStructuredExtraction(versionId)).containsEntry("status", "pending");
        server.verify();
    }

    @Test
    void postMapsWorker409ToAConflictCarryingTheWorkersReason() {
        server.expect(requestTo(BASE + "/ocr/structured/" + versionId))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"structured extraction is disabled (LLM_EXTRACTION_MODE=off)\"}"));

        assertThatThrownBy(() -> client.requestStructuredExtraction(versionId))
                .isInstanceOf(StructuredExtractionUnavailableException.class)
                .hasMessage("structured extraction is disabled (LLM_EXTRACTION_MODE=off)");
    }
}
