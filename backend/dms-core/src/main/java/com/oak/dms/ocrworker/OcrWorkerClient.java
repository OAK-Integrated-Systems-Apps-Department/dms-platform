package com.oak.dms.ocrworker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * dms-core's only outbound channel to ocr-worker, for the two read-only
 * routes the review screen needs (Phase 4c): a rendered page image, and
 * "what does this box say".
 * <p>
 * ocr-worker publishes no port and every route on it requires
 * {@code X-Internal-Token} -- so the browser cannot reach it, and must
 * not: the intended path is frontend -> dms-core (Keycloak-authenticated,
 * tenant-scoped) -> ocr-worker. This class is the middle arrow. The
 * tenant check happens in {@code DocumentServiceImpl} BEFORE anything
 * here is called; nothing in this class knows about tenants, and it must
 * never be called with a version the caller has not already been shown to
 * own.
 * <p>
 * Everything else still goes over RabbitMQ. This is deliberately not a
 * general-purpose passthrough: OCR processing is queued work with
 * retries, leases and an outbox, and turning it into a synchronous HTTP
 * call would lose all of that. Only operations that are genuinely
 * request/response -- a human is waiting and there is nothing to retry --
 * belong here.
 */
@Slf4j
@Component
public class OcrWorkerClient {

    /**
     * The grant the worker hands straight back to {@code /internal/v1/ocr}
     * when it needs the document's bytes. Named the same on both legs of the
     * round trip so there is one header to follow through the logs.
     */
    static final String READ_GRANT_HEADER = "X-Ocr-Job-Token";

    private final RestClient restClient;
    private final OcrProperties properties;
    private final OcrJobTokenCodec jobTokenCodec;

    public OcrWorkerClient(OcrProperties properties, OcrJobTokenCodec jobTokenCodec) {
        this.properties = properties;
        this.jobTokenCodec = jobTokenCodec;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getWorkerBaseUrl())
                .defaultHeader("X-Internal-Token", properties.getInternalServiceToken())
                .build();
    }

    /**
     * A short-lived grant for a read with no OCR job behind it - the review
     * screen's page image and region read.
     *
     * <p>Minted here, while serving a request that has already passed the
     * caller's {@code os.*} permission and document-level access, and good for
     * {@code dms.ocr.adhoc-token-minutes} (default 2). That short life is what
     * stands in for the live-job check a job grant gets: there is no job to
     * revoke, so the window is small instead.
     */
    private String adhocGrant(UUID versionId, UUID tenantId) {
        Instant now = Instant.now();
        return jobTokenCodec.issueAdhoc(versionId, tenantId, now,
                now.plus(Duration.ofMinutes(properties.getAdhocTokenMinutes())));
    }

    /** A rendered page plus the pixel size the highlights are placed against. */
    public record PageImage(byte[] png, int widthPx, int heightPx, int pageCount) {
    }

    /**
     * @param tenantId the version's tenant, used to mint the adhoc read grant
     *                 the worker presents back to {@code /internal/v1/ocr} -
     *                 the worker cannot read storage itself. The caller has
     *                 already passed their {@code os.*} permission and
     *                 document-level access by the time this is called; that
     *                 is what the short-lived grant stands for.
     */
    public PageImage fetchPageImage(UUID versionId, UUID tenantId, int page, Integer dpi) {
        String grant = adhocGrant(versionId, tenantId);
        return exchange(() -> {
            var response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/ocr/page-image/{versionId}").queryParam("page", page);
                        if (dpi != null) {
                            uriBuilder.queryParam("dpi", dpi);
                        }
                        return uriBuilder.build(versionId);
                    })
                    .header(READ_GRANT_HEADER, grant)
                    .accept(MediaType.IMAGE_PNG)
                    .retrieve()
                    .toEntity(byte[].class);

            var headers = response.getHeaders();
            return new PageImage(
                    response.getBody(),
                    intHeader(headers.getFirst("X-Page-Width")),
                    intHeader(headers.getFirst("X-Page-Height")),
                    intHeader(headers.getFirst("X-Page-Count")));
        });
    }

    /**
     * OCRs one normalized box and returns what it says. Writes nothing --
     * see ocr-worker's {@code /ocr/rubber-band/preview}, which exists
     * precisely because the finalizing route would replace the version's
     * entire field set with the one crop's result.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> previewRubberBand(
            UUID versionId, UUID tenantId, int page, double x0, double y0, double x1, double y1) {
        String grant = adhocGrant(versionId, tenantId);
        return exchange(() -> restClient.post()
                .uri("/ocr/rubber-band/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .header(READ_GRANT_HEADER, grant)
                .body(Map.of(
                        "version_id", versionId.toString(),
                        "page", page,
                        "x0", x0, "y0", y0, "x1", x1, "y1", y1))
                .retrieve()
                .body(Map.class));
    }

    /**
     * ocr-worker's status codes already mean the right thing to a browser
     * (404 unknown version, 409 not scanned clean yet, 422 a selection
     * that cannot be OCR'd, 504 too slow), so they are passed through
     * rather than collapsed into a 500 -- the review screen shows a
     * different message for each. Only a connection failure is translated,
     * because "ocr-worker is down" is a 503 from dms-core's point of view
     * and not something the reviewer did.
     */
    private <T> T exchange(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (org.springframework.web.client.RestClientResponseException exc) {
            HttpStatusCode status = exc.getStatusCode();
            throw new ResponseStatusException(status, friendlyFor(status), exc);
        } catch (ResourceAccessException exc) {
            log.warn("ocr-worker unreachable at {}", properties.getWorkerBaseUrl(), exc);
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "The document service is not responding. Try again in a moment.", exc);
        }
    }

    static String friendlyFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "That document version no longer exists.";
            case 409 -> "This document has not finished its virus scan yet.";
            case 422 -> "That part of the page can't be read. Try selecting a slightly larger area.";
            case 504 -> "Reading that took too long. Try a smaller selection.";
            // 401/500 mean a misconfiguration or a bug on our side, not
            // anything the reviewer can act on, so they get the same
            // non-specific sentence rather than a hint about internals.
            default -> "Could not read that part of the document.";
        };
    }

    private static int intHeader(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException exc) {
            return 0;
        }
    }

    /** Header names this client reads, exposed for the test. */
    static final List<String> SIZE_HEADERS = List.of("X-Page-Width", "X-Page-Height", "X-Page-Count");
}
