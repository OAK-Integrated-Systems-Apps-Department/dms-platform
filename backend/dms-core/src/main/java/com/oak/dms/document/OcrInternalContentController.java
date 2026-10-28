package com.oak.dms.document;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.ocrworker.OcrJobTokenCodec;
import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.ProcessingJob;
import com.oak.dms.ocrworker.ProcessingJobRepository;
import com.oak.dms.security.auth.TenantContext;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one way ocr-worker gets a document's plaintext bytes.
 *
 * <h2>Why this exists</h2>
 * The worker used to read MinIO directly. It cannot any more: every object is
 * encrypted at rest by {@code EncryptingObjectStorageService}, so a direct
 * read returns ciphertext, and a presigned URL is refused outright for an
 * encrypted object. The alternative - teaching the worker to decrypt - would
 * put data-key unwrapping and key-service access in a second process, which is
 * a much larger thing to get wrong than one endpoint.
 *
 * <h2>What guards it</h2>
 * Five checks, none of which the others can stand in for:
 * <ol>
 *   <li><b>Static token</b> ({@code X-Internal-Token}) - the second factor,
 *       shared with the worker through the same secrets file it already uses
 *       for the other direction.</li>
 *   <li><b>Per-job grant</b> ({@code X-Ocr-Job-Token}) - an HMAC naming one
 *       job, one version, one tenant and an expiry. A leaked static token
 *       alone reads nothing.</li>
 *   <li><b>The grant must match the path</b> - a token for version A cannot
 *       read version B, which is the whole point of binding it to a version.</li>
 *   <li><b>The job must still be live</b> and belong to that version, so a
 *       finished or cancelled job's token stops working even before it
 *       expires.</li>
 *   <li><b>The scan gate</b> - {@code VersionContent} refuses anything not
 *       scanned clean (or released by an administrator), so an unscanned or
 *       quarantined file is unreachable here exactly as it is everywhere
 *       else.</li>
 * </ol>
 * Tenant comes from the version row, never from a header: the worker has no
 * user and must not be able to assert whose document it is reading.
 *
 * <h2>Streaming, and why the response is not a byte[]</h2>
 * A 25 MB PDF read into a byte[] is 25 MB of heap per concurrent read, and the
 * watermark module already had to size its executor around exactly that
 * arithmetic. This streams from storage to socket.
 *
 * <h2>Auditing once per job</h2>
 * The worker reads a document once per job, but may retry; auditing every read
 * would turn a retry storm into an audit storm and bury the real event. The
 * first read for a job is audited and the rest are counted against it.
 *
 * <h2>Not reachable from outside</h2>
 * {@code /internal/**} must not be proxied by nginx/ingress - see
 * docs/ocr/internal-endpoint.md for the rule and the test that proves it.
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/internal/v1/ocr")
@RequiredArgsConstructor
public class OcrInternalContentController {

    private final DocumentVersionRepository documentVersionRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final VersionContent versionContent;
    private final OcrJobTokenCodec jobTokenCodec;
    private final OcrProperties ocrProperties;
    private final AuditService auditService;
    private final OcrArtifactWriter artifactWriter;

    /**
     * Jobs whose first read has already been audited, with a count of the
     * reads since. Bounded by the number of OCR jobs in flight, which the
     * per-tenant queue limit already caps; entries are dropped as jobs finish.
     */
    private final Map<UUID, AtomicInteger> readsPerJob = new ConcurrentHashMap<>();

    /** Reads per job, over the job's life. A worker that exceeds this is malfunctioning or malicious. */
    private static final int MAX_READS_PER_JOB = 20;

    @GetMapping("/versions/{versionId}/content")
    public ResponseEntity<StreamingResponseBody> content(
            @PathVariable UUID versionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String staticToken,
            @RequestHeader(value = "X-Ocr-Job-Token", required = false) String jobToken,
            HttpServletRequest request) {

        if (!ocrProperties.isEnabled()) {
            // Off means off: no endpoint, not an endpoint that refuses.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is nothing at this address.");
        }
        requireStaticToken(staticToken);
        OcrJobTokenCodec.Grant grant = jobTokenCodec.verify(jobToken)
                .orElseThrow(() -> unauthorized("missing or invalid job token"));

        // The grant names a version; the path names a version. They must agree,
        // or a valid token for one document would read any other.
        if (!grant.versionId().equals(versionId)) {
            throw unauthorized("job token is not for this version");
        }

        DocumentVersion version = documentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("document_versions", versionId));
        // Tenant from the row, not the token: the token says which tenant the
        // worker believes it is reading for, and this is where that belief is
        // checked rather than trusted.
        if (!version.getTenantId().equals(grant.tenantId())) {
            throw unauthorized("job token is not for this tenant");
        }
        // A job grant is only good while its job is. An adhoc grant has no job
        // to check - it is short-lived instead, and was minted only while
        // serving a user who had already passed permission and access checks.
        if (grant.purpose() == OcrJobTokenCodec.Purpose.job) {
            requireLiveJob(grant.jobId(), versionId);
        }
        rateLimit(grant);

        // VersionContent reads TenantContext and enforces the scan gate, the
        // storage-purpose check and the purge check - the same code path a
        // user download takes, so there is one set of rules, not two.
        TenantContext.set(version.getTenantId(), null);
        try {
            DownloadedDocument document = versionContent.open(version);
            auditFirstRead(grant, version, request);
            InputStream content = document.content();
            StreamingResponseBody body = out -> {
                try (InputStream in = content) {
                    in.transferTo(out);
                }
            };
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE,
                            document.contentType() != null ? document.contentType()
                                    : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(document.sizeBytes()))
                    // Nothing caches a decrypted document.
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(body);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The OCR result JSON for a version.
     *
     * <p>Idempotent: the key is derived from the version, so a retried job
     * overwrites its own output rather than leaving an orphan, and the row
     * upserts on the same basis (V83). A worker that retries three times
     * leaves one object and one row.
     */
    @PutMapping(value = "/versions/{versionId}/output", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> putOutput(
            @PathVariable UUID versionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String staticToken,
            @RequestHeader(value = "X-Ocr-Job-Token", required = false) String jobToken,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            HttpServletRequest request) throws java.io.IOException {
        return putArtifact(versionId, staticToken, jobToken, contentLength, request,
                OcrArtifactWriter.Artifact.output, MediaType.APPLICATION_JSON_VALUE,
                ocrProperties.getMaxOutputBytes());
    }

    /** The searchable PDF. Same idempotence and the same checks, different cap. */
    @PutMapping(value = "/versions/{versionId}/rendition", consumes = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<Map<String, String>> putRendition(
            @PathVariable UUID versionId,
            @RequestHeader(value = "X-Internal-Token", required = false) String staticToken,
            @RequestHeader(value = "X-Ocr-Job-Token", required = false) String jobToken,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            HttpServletRequest request) throws java.io.IOException {
        return putArtifact(versionId, staticToken, jobToken, contentLength, request,
                OcrArtifactWriter.Artifact.searchable_pdf, MediaType.APPLICATION_PDF_VALUE,
                ocrProperties.getMaxRenditionBytes());
    }

    /**
     * Shared by both writes. Validates, in this order: the feature is on, both
     * credentials, that the grant owns this version and tenant, that the job is
     * live (a write only ever belongs to a job - an adhoc grant may read, never
     * write), the declared size, and the content type. Only then is a byte
     * stored.
     */
    private ResponseEntity<Map<String, String>> putArtifact(UUID versionId, String staticToken, String jobToken,
                                             Long contentLength, HttpServletRequest request,
                                             OcrArtifactWriter.Artifact artifact, String expectedContentType,
                                             long maxBytes) throws java.io.IOException {
        if (!ocrProperties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "There is nothing at this address.");
        }
        requireStaticToken(staticToken);
        OcrJobTokenCodec.Grant grant = jobTokenCodec.verify(jobToken)
                .orElseThrow(() -> unauthorized("missing or invalid job token"));
        if (!grant.versionId().equals(versionId)) {
            throw unauthorized("job token is not for this version");
        }
        // Writes require a job. An adhoc grant exists so the review screen can
        // render a page; it must never be able to replace a document's OCR
        // output, which is a far larger thing than reading one page.
        if (grant.purpose() != OcrJobTokenCodec.Purpose.job) {
            throw unauthorized("an adhoc grant may not write artifacts");
        }

        DocumentVersion version = documentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("document_versions", versionId));
        if (!version.getTenantId().equals(grant.tenantId())) {
            throw unauthorized("job token is not for this tenant");
        }
        requireLiveJob(grant.jobId(), versionId);

        if (contentLength == null || contentLength <= 0) {
            // Required so the cap is enforced BEFORE anything is read, not
            // after a chunked body has already filled a disk.
            throw new ResponseStatusException(HttpStatus.LENGTH_REQUIRED,
                    "Content-Length is required for an OCR artifact.");
        }
        if (contentLength > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "This OCR artifact is larger than " + maxBytes + " bytes.");
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith(expectedContentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Expected " + expectedContentType + " for this artifact.");
        }

        TenantContext.set(version.getTenantId(), null);
        try (InputStream body = request.getInputStream()) {
            // The key comes back so the worker can put it in the completed
            // event's extracted_text_ref. dms-core owns the key format now;
            // returning it is what keeps that a single definition rather than
            // the same string built in Java and again in Python, which would
            // drift the first time either changed.
            String key = artifactWriter.write(version, artifact, body, contentLength, expectedContentType);
            return ResponseEntity.ok(Map.of("objectKey", key));
        } finally {
            TenantContext.clear();
        }
    }

    private void requireStaticToken(String supplied) {
        String expected = ocrProperties.getInternalServiceToken();
        if (expected == null || expected.isBlank()) {
            // No configured secret means no second factor, so nothing is
            // served - rather than silently accepting every caller.
            log.error("dms.ocr.internal-service-token is not set - refusing every internal OCR read");
            throw unauthorized("internal token is not configured");
        }
        if (supplied == null || !MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized("missing or invalid internal token");
        }
    }

    /**
     * The job must exist, be for this version, and still be running. Checked
     * against the database rather than trusted from the token, so revoking
     * access is a matter of the job's own lifecycle: finish it, fail it or
     * cancel it and its token stops working immediately.
     */
    private void requireLiveJob(UUID jobId, UUID versionId) {
        Optional<ProcessingJob> job = processingJobRepository.findById(jobId);
        if (job.isEmpty()
                || !versionId.equals(job.get().getReferenceId())
                || !ProcessingJob.JOB_TYPE_OCR.equals(job.get().getJobType())
                || !(ProcessingJob.STATUS_QUEUED.equals(job.get().getStatus())
                        || ProcessingJob.STATUS_PROCESSING.equals(job.get().getStatus()))) {
            throw unauthorized("no live OCR job for this version");
        }
    }

    /**
     * Counted per grant, not per caller: a job grant is one job's budget, and
     * an adhoc grant - which has no job id - is keyed on its version instead,
     * so a review screen paging through a document shares one allowance.
     */
    private void rateLimit(OcrJobTokenCodec.Grant grant) {
        UUID key = grant.purpose() == OcrJobTokenCodec.Purpose.job ? grant.jobId() : grant.versionId();
        int reads = readsPerJob.computeIfAbsent(key, id -> new AtomicInteger()).incrementAndGet();
        int limit = grant.purpose() == OcrJobTokenCodec.Purpose.job
                ? MAX_READS_PER_JOB
                : ocrProperties.getMaxAdhocReadsPerVersion();
        if (reads > limit) {
            log.warn("OCR grant for {} has read its document {} times - refusing further reads", key, reads);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "This grant has read its document too many times.");
        }
    }

    /** One DOCUMENT_VIEWED per grant, not per read - see the class note. */
    private void auditFirstRead(OcrJobTokenCodec.Grant grant, DocumentVersion version, HttpServletRequest request) {
        UUID key = grant.purpose() == OcrJobTokenCodec.Purpose.job ? grant.jobId() : grant.versionId();
        if (readsPerJob.get(key).get() != 1) {
            return;
        }
        auditService.event(AuditActionCatalog.DOCUMENT_VIEWED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(version.getDocumentId())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "via", "ocr-worker",
                        "jobId", grant.jobId() != null ? grant.jobId().toString() : "adhoc",
                        "versionId", version.getVersionId().toString()))
                .log();
    }

    private static ResponseStatusException unauthorized(String reason) {
        // The reason is logged, never returned: telling a caller which of the
        // five checks they failed is telling them how to pass it.
        log.warn("Refused an internal OCR content read: {}", reason);
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authorised.");
    }

    /** Dropped when a job reaches a terminal state, so the map stays the size of the work in flight. */
    public void forget(UUID jobId) {
        readsPerJob.remove(jobId);
    }

    /** How long a job grant is valid. Short: a job that hasn't started reading in this long has stalled. */
    public static Duration grantLifetime(OcrProperties properties) {
        return Duration.ofMinutes(properties.getJobTokenMinutes());
    }

    /** Issued at enqueue; see {@code OcrEnqueueService}. */
    public static Instant expiryFrom(Instant issuedAt, OcrProperties properties) {
        return issuedAt.plus(grantLifetime(properties));
    }
}
