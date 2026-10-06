package com.oak.dms.watermark.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentContentReader.VersionDetails;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.watermark.config.WatermarkAvailability;
import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.dto.WatermarkRequest;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.entity.WatermarkTemplate;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.WatermarkServiceFactory;
import com.oak.dms.watermark.job.WatermarkJobStarter;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The single entry point for producing a watermarked copy: everything that
 * happens on the REQUEST thread, before a worker is involved.
 *
 * <h2>What is decided here and never revisited</h2>
 * The watermark text and style are resolved and frozen onto the job. The
 * worker draws that snapshot. This is deliberate and load-bearing: it is
 * what lets the audit entry written here be trusted as a record of what
 * ended up on the page, even though the drawing happens later and the user,
 * the template or the clock may all have moved on in between.
 *
 * <h2>Why validation is front-loaded</h2>
 * Every refusal that can be worked out without reading the document's bytes
 * happens before a job row exists - unsupported format, too large, no font
 * for the script, text that cannot fit, a retired template, an unknown
 * placeholder. The alternative is a 202 followed by a job that quietly
 * fails, which tells the person nothing they can act on and leaves a failed
 * row behind for somebody to explain.
 *
 * <h2>Idempotency</h2>
 * {@code Idempotency-Key} means "a double-click must not produce two
 * copies". The hash it is checked against covers the NORMALISED REQUEST,
 * not the resolved text - {@code {date}} and {@code {watermarkId}} move on
 * every attempt, so hashing the resolved text would make an honest retry
 * look like a different request and 409.
 * <p>
 * Two simultaneous requests with the same key race for the partial unique
 * index. The loser catches the violation and returns the winner's job
 * rather than an error: from the user's point of view one click produced
 * one job, which is exactly what the key promised.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatermarkService {

    private final WatermarkJobRepository jobRepository;
    private final WatermarkTemplateService templateService;
    private final WatermarkStyleValidator styleValidator;
    private final PlaceholderResolver placeholderResolver;
    private final TextSanitizer sanitizer;
    private final DocumentContentReader documentContentReader;
    private final WatermarkServiceFactory formatServices;
    private final WatermarkRenderGuard renderGuard;
    private final WatermarkAvailability availability;
    private final WatermarkProperties properties;
    private final WatermarkJobStarter starter;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /**
     * Accepts a request for a watermarked copy, or explains why not.
     *
     * @param idempotencyKey the {@code Idempotency-Key} header, or null
     * @return the job - newly created, or the existing one this key already
     *         refers to
     */
    @Transactional
    public WatermarkJobResponse apply(UUID documentId, WatermarkRequest request, String idempotencyKey) {
        availability.require();

        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String requestHash = hashOf(documentId, request);

        // Before anything else: has this exact request already been accepted?
        Optional<WatermarkJob> existing = idempotencyKey == null ? Optional.empty()
                : jobRepository.findLiveByIdempotencyKey(tenantId, userId, idempotencyKey);
        if (existing.isPresent()) {
            return sameRequestOrConflict(existing.get(), requestHash, idempotencyKey);
        }

        VersionDetails version = documentContentReader.describe(documentId, request.versionId());
        checkFormatAndSize(version);
        checkActiveJobCap(tenantId, userId);

        // The id has to exist before the text is resolved: {watermarkId}
        // draws it, and drawing one value while storing another would make
        // the placeholder worse than useless.
        UUID jobId = UUID.randomUUID();
        WatermarkTemplate template = request.hasTemplate()
                ? templateService.requireUsable(request.templateId()) : null;
        WatermarkSpec spec = buildSpec(request, template, jobId, version);

        // Fails here, on the request thread, rather than in a worker: no
        // font for the script, or text that cannot fit the page at a
        // readable size. Both are things the person can act on.
        renderGuard.check(spec);

        WatermarkJob job;
        try {
            job = insert(jobId, tenantId, userId, version, template, spec, idempotencyKey, requestHash);
        } catch (DuplicateAccepted raced) {
            // Lost the race for the key. The winner's job IS the answer -
            // one click, one job - so nothing further is started or audited
            // here; the thread that won did both.
            return WatermarkJobResponse.from(raced.winner, textOf(raced.winner));
        }
        auditRequested(job, spec, version);
        starter.startAfterCommit(job.getJobId());
        return WatermarkJobResponse.from(job, spec.text());
    }

    // --------------------------------------------------------- validation

    /**
     * The same key with a different body is a mistake worth reporting - most
     * likely a client reusing a key it should have regenerated. Returning
     * the first job would silently give them a copy of the wrong document.
     */
    private WatermarkJobResponse sameRequestOrConflict(WatermarkJob existing, String requestHash, String key) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw WatermarkException.conflict("WATERMARK_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key '" + key + "' was already used for a different watermark request. "
                            + "Use a new key.");
        }
        return WatermarkJobResponse.from(existing, textOf(existing));
    }

    private void checkFormatAndSize(VersionDetails version) {
        if (!formatServices.isSupported(version.mimeType())) {
            throw WatermarkException.unsupportedType(
                    "Watermarking isn't available for this kind of file yet. Supported: PDF, Word (.docx), "
                            + "Excel (.xlsx), PowerPoint (.pptx), JPEG and PNG.");
        }
        // From the version row, so nothing is read before the cap applies.
        DataSize max = maxBytesFor(version.mimeType());
        if (version.sizeBytes() > max.toBytes()) {
            throw WatermarkException.tooLarge("This file is " + (version.sizeBytes() / (1024 * 1024))
                    + "MB, and " + max.toMegabytes() + "MB is the most that can be watermarked for this format.");
        }
    }

    private DataSize maxBytesFor(String mimeType) {
        String key = switch (mimeType == null ? "" : mimeType) {
            case "application/pdf" -> "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "image/jpeg", "image/png" -> "image";
            default -> "";
        };
        return properties.getMaxBytes().getOrDefault(key, properties.getDefaultMaxBytes());
    }

    /**
     * The hard cap, and the one that survives Redis being down - it is a
     * count of rows, not a token bucket.
     */
    private void checkActiveJobCap(UUID tenantId, UUID userId) {
        int max = properties.getMaxActiveJobsPerUser();
        long active = jobRepository.countByTenantIdAndRequestedByAndStatusIn(tenantId, userId,
                EnumSet.of(WatermarkJobStatus.pending, WatermarkJobStatus.processing));
        if (active >= max) {
            throw WatermarkException.rateLimited("WATERMARK_TOO_MANY_ACTIVE_JOBS",
                    "You already have " + active + " watermark" + (active == 1 ? "" : "s")
                            + " being produced. Wait for " + (active == 1 ? "it" : "one") + " to finish.");
        }
    }

    // ------------------------------------------------------------- spec

    private WatermarkSpec buildSpec(WatermarkRequest request, WatermarkTemplate template,
                                    UUID jobId, VersionDetails version) {
        boolean hasText = request.hasCustomText();
        if (request.hasTemplate() == hasText) {
            throw WatermarkException.badRequest("WATERMARK_TEXT_OR_TEMPLATE",
                    request.hasTemplate()
                            ? "Choose a template or type your own text, not both."
                            : "Choose a watermark template, or type the text you want.");
        }

        String source = template != null ? template.getDefaultText() : request.customText();
        String text = placeholderResolver.resolve(source, new PlaceholderResolver.Context(
                jobId, version, OffsetDateTime.now(), placeholderResolver.effectiveUser()));
        if (text.isBlank()) {
            throw WatermarkException.badRequest("WATERMARK_TEXT_EMPTY",
                    "The watermark text came out empty once the placeholders were filled in.");
        }

        WatermarkProperties.Style defaults = properties.getStyle();
        return new WatermarkSpec(
                text,
                first(styleValidator.fontSize(request.fontSize()), template == null ? null : template.getFontSize(),
                        defaults.getFontSize()),
                first(styleValidator.opacity(request.opacity()), template == null ? null : template.getOpacity(),
                        defaults.getOpacity()),
                first(styleValidator.rotation(request.rotation()), template == null ? null : template.getRotation(),
                        defaults.getRotation()),
                first(styleValidator.color(request.color()), template == null ? null : template.getColor(),
                        defaults.getColor().toLowerCase(Locale.ROOT)),
                first(styleValidator.placement(request.placement()),
                        template == null ? null : template.getPlacement(),
                        WatermarkPlacement.fromValue(defaults.getPlacement())),
                first(styleValidator.pageScope(request.pageScope()),
                        template == null ? null : template.getPageScope(),
                        WatermarkPageScope.fromValue(defaults.getPageScope())));
    }

    /** request &gt; template &gt; system default, in that order. */
    private static <T> T first(T fromRequest, T fromTemplate, T systemDefault) {
        return fromRequest != null ? fromRequest : fromTemplate != null ? fromTemplate : systemDefault;
    }

    // ------------------------------------------------------------ insert

    private WatermarkJob insert(UUID jobId, UUID tenantId, UUID userId, VersionDetails version,
                                WatermarkTemplate template, WatermarkSpec spec,
                                String idempotencyKey, String requestHash) {
        WatermarkJob job = WatermarkJob.builder()
                .jobId(jobId)
                .tenantId(tenantId)
                .requestedBy(userId)
                .documentId(version.documentId())
                .versionId(version.versionId())
                .templateId(template == null ? null : template.getTemplateId())
                .templateVersion(template == null ? null : template.getVersion())
                .status(WatermarkJobStatus.pending)
                .requestSnapshot(json(spec))
                .sourceMime(version.mimeType())
                .sourceSizeBytes(version.sizeBytes())
                .correlationId(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .build();
        try {
            return jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            // Two simultaneous requests with the same key. One won the
            // partial unique index; this one returns the winner's job rather
            // than an error, because from the user's side a single click
            // produced a single job - which is what the key promised.
            WatermarkJob winner = idempotencyKey == null ? null
                    : jobRepository.findLiveByIdempotencyKey(tenantId, userId, idempotencyKey).orElse(null);
            if (winner == null) {
                throw e;
            }
            log.debug("Lost the race for Idempotency-Key '{}' - returning job {}", idempotencyKey,
                    winner.getJobId());
            throw new DuplicateAccepted(winner);
        }
    }

    /**
     * Carries the winner of an idempotency race back out of the insert, so
     * the caller returns it instead of the job it was building. A control
     * exception rather than a null return: losing the race is not an error
     * path the caller should have to remember to check for.
     */
    static final class DuplicateAccepted extends RuntimeException {
        final transient WatermarkJob winner;

        DuplicateAccepted(WatermarkJob winner) {
            super(null, null, false, false);
            this.winner = winner;
        }
    }

    // ------------------------------------------------------------ helpers

    /**
     * SHA-256 of the normalised REQUEST - never the resolved text, which
     * carries {@code {date}} and {@code {watermarkId}} and so differs on
     * every attempt. Hashing that would make an honest retry 409.
     */
    private String hashOf(UUID documentId, WatermarkRequest request) {
        String normalised = String.join("",
                documentId.toString(),
                String.valueOf(request.templateId()),
                sanitizer.text(request.customText() == null ? "" : request.customText()),
                String.valueOf(request.versionId()),
                String.valueOf(request.fontSize()),
                String.valueOf(request.opacity()),
                String.valueOf(request.rotation()),
                request.color() == null ? "null" : request.color().toLowerCase(Locale.ROOT),
                String.valueOf(request.placement()),
                String.valueOf(request.pageScope()));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private String json(WatermarkSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A WatermarkSpec must always serialise", e);
        }
    }

    /** The resolved text out of a stored snapshot, for a response about an existing job. */
    public String textOf(WatermarkJob job) {
        try {
            return objectMapper.readValue(job.getRequestSnapshot(), WatermarkSpec.class).text();
        } catch (JsonProcessingException e) {
            log.warn("Watermark job {} has an unreadable request_snapshot", job.getJobId(), e);
            return "";
        }
    }

    /**
     * Written on the REQUEST thread, which is the only place impersonation
     * is captured: the worker runs as the effective user with no "acting as"
     * of its own. The two later events reference the job id and join back to
     * this one.
     */
    private void auditRequested(WatermarkJob job, WatermarkSpec spec, VersionDetails version) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentId", job.getDocumentId());
        details.put("versionId", job.getVersionId());
        details.put("versionNumber", version.versionNumber());
        details.put("templateId", job.getTemplateId());
        details.put("templateVersion", job.getTemplateVersion());
        details.put("text", spec.text());
        details.put("placement", spec.placement().getValue());
        details.put("pageScope", spec.pageScope().getValue());
        details.put("sourceMime", job.getSourceMime());
        details.put("correlationId", job.getCorrelationId());

        auditService.event(AuditActionCatalog.WATERMARK_REQUESTED)
                .objectType(AuditObjectTypes.WATERMARK_JOB)
                .objectId(job.getJobId())
                .resourceName(version.title())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(details)
                .log();
    }
}
