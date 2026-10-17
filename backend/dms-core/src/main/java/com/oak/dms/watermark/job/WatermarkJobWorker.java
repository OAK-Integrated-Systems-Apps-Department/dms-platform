package com.oak.dms.watermark.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.KeycloakIntegrationException;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.DetectedFileType;
import com.oak.dms.scanning.helper.FileTypeInspector;
import com.oak.dms.security.auth.JobIdentity;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import com.oak.dms.watermark.config.WatermarkExecutorConfig;
import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.WatermarkFormatService;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkServiceFactory;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Produces the watermarked copy, following {@code ExportJobWorker}'s shape
 * step for step: claim under a pessimistic lock, re-bind the requester's
 * identity, do the work as them, complete or fail.
 *
 * <h2>The snapshot is the instruction</h2>
 * The text and style were resolved on the request thread and frozen onto the
 * job. Nothing here re-resolves a placeholder or re-reads a template, so
 * what the audit trail recorded when the copy was asked for is necessarily
 * what ended up on the page.
 *
 * <h2>Checked again, as the user is NOW</h2>
 * Access is re-established at run time through the same
 * {@link DocumentContentReader} the request used. Between asking and running
 * a person can lose access to a folder, or the document can be deleted - the
 * check that mattered then is not the check that matters now.
 *
 * <h2>Why the format call runs on a second pool</h2>
 * POI and PDFBox ignore interrupts, so a file that overruns cannot be
 * stopped. Running it on its own thread means the job thread stays free to
 * time out, record the failure and move on, while the stuck thread is
 * abandoned and counted. See {@code WatermarkExecutorConfig}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dms.watermark", name = "enabled", havingValue = "true")
public class WatermarkJobWorker implements WatermarkJobQueue {

    private static final Set<WatermarkJobStatus> UNFINISHED =
            EnumSet.of(WatermarkJobStatus.pending, WatermarkJobStatus.processing);

    private final WatermarkJobRepository repository;
    private final DocumentContentReader documentContentReader;
    private final WatermarkServiceFactory formatServices;
    private final FileTypeInspector fileTypeInspector;
    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory storage;
    private final JobIdentity jobIdentity;
    private final AuditService auditService;
    private final WatermarkProperties properties;
    private final WatermarkMetrics metrics;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor jobExecutor;
    private final ThreadPoolTaskExecutor formatExecutor;
    private final TransactionTemplate transactions;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("java:S107")   // mirrors ExportJobWorker's own constructor
    public WatermarkJobWorker(WatermarkJobRepository repository,
                              DocumentContentReader documentContentReader,
                              WatermarkServiceFactory formatServices,
                              FileTypeInspector fileTypeInspector,
                              StorageLocationResolver storageLocationResolver,
                              ObjectStorageServiceFactory storage,
                              JobIdentity jobIdentity,
                              AuditService auditService,
                              WatermarkProperties properties,
                              WatermarkMetrics metrics,
                              ObjectMapper objectMapper,
                              @Qualifier(WatermarkExecutorConfig.JOB_EXECUTOR) ThreadPoolTaskExecutor jobExecutor,
                              @Qualifier(WatermarkExecutorConfig.FORMAT_EXECUTOR) ThreadPoolTaskExecutor formatExecutor,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.documentContentReader = documentContentReader;
        this.formatServices = formatServices;
        this.fileTypeInspector = fileTypeInspector;
        this.storageLocationResolver = storageLocationResolver;
        this.storage = storage;
        this.jobIdentity = jobIdentity;
        this.auditService = auditService;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.jobExecutor = jobExecutor;
        this.formatExecutor = formatExecutor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Queues the job. Called only after its row is committed - see
     * {@link WatermarkJobStarter}.
     *
     * @throws RejectedExecutionException when the bounded queue is full;
     *         the starter turns that into a failed row and a 503
     */
    @Override
    public void start(UUID jobId) {
        if (!running.add(jobId)) {
            return;   // already in flight on this instance
        }
        try {
            jobExecutor.execute(() -> {
                try {
                    run(jobId);
                } finally {
                    running.remove(jobId);
                }
            });
        } catch (RuntimeException e) {
            running.remove(jobId);
            throw e;
        }
    }

    /**
     * Picks up whatever the last instance left behind.
     * <p>
     * A row in {@code processing} at startup has no thread behind it - the
     * process that owned it is gone. One that has been there longer than
     * {@code dms.watermark.stuck-after} is failed rather than resumed: it
     * was most likely killed by the very file it was working on, and
     * retrying it on every restart would be a loop. Anything younger is
     * simply queued again.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        try {
            OffsetDateTime stuckBefore = OffsetDateTime.now().minus(properties.getStuckAfter());
            for (WatermarkJob job : repository.findByStatusIn(UNFINISHED)) {
                boolean stuck = job.getStatus() == WatermarkJobStatus.processing
                        && job.getStartedAt() != null && job.getStartedAt().isBefore(stuckBefore);
                if (stuck) {
                    log.warn("Watermark job {} was still processing after {} - failing it rather than resuming",
                            job.getJobId(), properties.getStuckAfter());
                    fail(job.getJobId(), WatermarkFailure.UNEXPECTED, null, Duration.ZERO, formatOf(job));
                } else {
                    log.info("Resuming watermark job {}", job.getJobId());
                    start(job.getJobId());
                }
            }
        } catch (RuntimeException e) {
            // Never stop the application coming up over this.
            log.error("Could not resume interrupted watermark jobs", e);
        }
    }

    // --------------------------------------------------------------- run

    void run(UUID jobId) {
        WatermarkJob job = claim(jobId);
        if (job == null) {
            return;
        }
        MDC.put("jobId", job.getJobId().toString());
        if (job.getCorrelationId() != null) {
            MDC.put("correlationId", job.getCorrelationId().toString());
        }
        long startedAt = System.nanoTime();
        String format = formatOf(job);
        // Counted once the job is claimed rather than when its row is
        // written, so started minus completed minus failed is the number
        // actually in flight. A job the pool refused never reaches here,
        // and is already visible as a failed row with WATERMARK_QUEUE_FULL.
        metrics.started(format);

        try {
            // As the requester, with the access they have NOW. A Keycloak
            // outage surfaces here or in the access check below, and is a
            // retryable failure rather than a verdict about the document.
            if (!jobIdentity.bind(job.getTenantId(), job.getRequestedBy())) {
                fail(jobId, WatermarkFailure.REQUESTER_INACTIVE, null, elapsed(startedAt), format);
                return;
            }
            produce(job, format, startedAt);
        } catch (KeycloakIntegrationException | IllegalStateException e) {
            // JobIdentity's own contract: a background job whose roles can't
            // be checked must fail and be retried, not quietly run with
            // less access than the user really has.
            log.warn("Watermark job {} could not establish the requester's identity", jobId, e);
            fail(jobId, WatermarkFailure.IDENTITY_UNAVAILABLE, null, elapsed(startedAt), format);
        } catch (RuntimeException e) {
            log.error("Watermark job {} failed unexpectedly", jobId, e);
            fail(jobId, WatermarkFailure.UNEXPECTED, null, elapsed(startedAt), format);
        } finally {
            TenantContext.clear();
            MDC.remove("jobId");
            MDC.remove("correlationId");
        }
    }

    private void produce(WatermarkJob job, String format, long startedAt) {
        UUID jobId = job.getJobId();
        DownloadedDocument source;
        try {
            source = documentContentReader.open(job.getDocumentId(), job.getVersionId());
        } catch (KeycloakIntegrationException | IllegalStateException e) {
            // NOT a verdict about the document. The access check inside
            // open() asks Keycloak for the requester's realm roles, and
            // AuthorizationService deliberately turns an outage there into
            // an IllegalStateException for a background job rather than
            // silently answering "no elevated role". Swallowing it here
            // would tell the user they had lost access to their own
            // document because Keycloak blipped. Let run() classify it.
            throw e;
        } catch (RuntimeException e) {
            log.info("Watermark job {} can no longer read its document: {}", jobId, e.toString());
            fail(jobId, WatermarkFailure.ACCESS_LOST, null, elapsed(startedAt), format);
            return;
        }

        byte[] bytes;
        try (InputStream content = source.content()) {
            bytes = content.readAllBytes();
        } catch (Exception e) {
            log.warn("Watermark job {} could not read its document's bytes", jobId, e);
            fail(jobId, WatermarkFailure.STORAGE_UNAVAILABLE, null, elapsed(startedAt), format);
            return;
        }

        if (!contentMatchesRecord(jobId, bytes, source)) {
            fail(jobId, WatermarkFailure.CONTENT_MISMATCH, null, elapsed(startedAt), format);
            return;
        }

        byte[] output;
        try {
            output = draw(bytes, job, source);
        } catch (WatermarkFailed e) {
            fail(jobId, e.failure, e.detail, elapsed(startedAt), format);
            return;
        }

        try {
            store(job, output, source);
        } catch (RuntimeException e) {
            log.error("Watermark job {} produced a copy but could not store it", jobId, e);
            fail(jobId, WatermarkFailure.STORAGE_UNAVAILABLE, null, elapsed(startedAt), format);
            return;
        }
        metrics.completed(format, elapsed(startedAt));
    }

    /**
     * The canonical type is re-checked against the bytes about to be drawn
     * on - not against the filename, and not against anything the client
     * said. If the two disagree the file is not what the system believes it
     * is, and the right answer is to stop rather than to hand it to a
     * library that expects something else.
     */
    private boolean contentMatchesRecord(UUID jobId, byte[] bytes, DownloadedDocument source) {
        try {
            int headLength = Math.min(bytes.length, FileTypeInspector.HEAD_BYTES);
            DetectedFileType detected = fileTypeInspector.inspect(source.filename(), bytes, headLength);
            if (detected.encrypted()) {
                log.info("Watermark job {} refused: the file is password-protected", jobId);
                return false;
            }
            if (!detected.mimeType().equals(source.contentType())) {
                log.warn("Watermark job {} refused: recorded type {} but the bytes are {}",
                        jobId, source.contentType(), detected.mimeType());
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // Includes FileTypeNotAllowedException, which can fire for a
            // file ingested while its extension was still permitted. The
            // ingestion wording would be confusing here, so it is logged
            // and translated rather than passed on.
            log.info("Watermark job {} refused by the content check: {}", jobId, e.toString());
            return false;
        }
    }

    /**
     * Runs the format service on the format pool and waits with a timeout.
     * <p>
     * On timeout the future is cancelled - which interrupts a thread that
     * will not look - and the thread is counted as abandoned. The job thread
     * returns either way, which is the whole point of the second pool.
     */
    private byte[] draw(byte[] bytes, WatermarkJob job, DownloadedDocument source) {
        WatermarkSpec spec = snapshotOf(job);
        WatermarkFormatService service = formatServices.forMimeType(source.contentType());
        WatermarkOptions options = new WatermarkOptions(spec, source.contentType(), source.filename());

        Future<byte[]> future;
        try {
            future = formatExecutor.submit(() -> service.watermark(new ByteArrayInputStream(bytes), options));
        } catch (RejectedExecutionException e) {
            // Every format thread is busy - in practice, abandoned.
            log.error("Watermark job {} rejected by the format pool ({} threads abandoned)",
                    job.getJobId(), metrics.abandonedThreads());
            throw new WatermarkFailed(WatermarkFailure.FORMAT_CAPACITY, null);
        }

        Duration timeout = properties.getFormatTimeout();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            int abandoned = metrics.threadAbandoned();
            log.error("Watermark job {} exceeded {} and could not be interrupted - "
                            + "{} format thread(s) now abandoned of {}",
                    job.getJobId(), timeout, abandoned, properties.getExecutor().getFormatMaxSize());
            throw new WatermarkFailed(WatermarkFailure.TIMED_OUT, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new WatermarkFailed(WatermarkFailure.UNEXPECTED, null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WatermarkException refused) {
                // The document itself - encrypted, signed, corrupt, too many
                // pages. Its message was written for the requester.
                throw new WatermarkFailed(WatermarkFailure.DOCUMENT_REFUSED, refused.getMessage());
            }
            log.error("Watermark job {} failed inside the format service", job.getJobId(), cause);
            throw new WatermarkFailed(WatermarkFailure.UNEXPECTED, null);
        }
    }

    /**
     * Into the tenant's STAGING location, beside bulk exports and under the
     * same key shape. Never the document location: that one holds promoted,
     * scanned versions and nothing else.
     */
    private void store(WatermarkJob job, byte[] output, DownloadedDocument source) {
        StorageLocation location = storageLocationResolver.resolveForTenant(job.getTenantId(),
                StoragePurpose.staging);
        String objectKey = job.getTenantId() + "/tmp/watermarks/" + job.getJobId() + "/"
                + source.filename();
        storage.resolve(location).upload(location, objectKey, new ByteArrayInputStream(output),
                output.length, source.contentType());
        complete(job.getJobId(), location.getStorageLocationId(), objectKey, output.length);
    }

    // ------------------------------------------------------- transitions

    /** pending -> processing, or picks a processing one back up after a restart. */
    private WatermarkJob claim(UUID jobId) {
        return transactions.execute(status -> repository.lockById(jobId)
                .filter(job -> UNFINISHED.contains(job.getStatus()))
                .map(job -> {
                    OffsetDateTime now = OffsetDateTime.now();
                    job.setStatus(WatermarkJobStatus.processing);
                    job.setStartedAt(now);
                    job.setUpdatedAt(now);
                    return repository.save(job);
                })
                .orElse(null));
    }

    private void complete(UUID jobId, UUID locationId, String objectKey, long size) {
        WatermarkJob done = transactions.execute(status -> {
            WatermarkJob job = repository.lockById(jobId).orElseThrow();
            OffsetDateTime now = OffsetDateTime.now();
            job.setStatus(WatermarkJobStatus.completed);
            job.setStorageLocationId(locationId);
            job.setOutputObjectKey(objectKey);
            job.setOutputSizeBytes(size);
            job.setCompletedAt(now);
            job.setExpiresAt(now.plus(properties.getOutputTtl()));
            job.setUpdatedAt(now);
            return repository.save(job);
        });
        audit(done, AuditActionCatalog.WATERMARK_COMPLETED, AuditOutcome.SUCCESS, null);
        log.info("Watermark job {} completed: {} bytes", jobId, size);
    }

    /**
     * Always reachable, and never leaves a job in {@code processing} - a row
     * stuck there is one nothing will ever finish and which counts against
     * the requester's active-job cap for ever.
     */
    private void fail(UUID jobId, WatermarkFailure failure, String detail, Duration took, String format) {
        WatermarkJob failed = transactions.execute(status -> repository.lockById(jobId).map(job -> {
            OffsetDateTime now = OffsetDateTime.now();
            job.setStatus(WatermarkJobStatus.failed);
            job.setFailureCode(failure.code());
            job.setFailureReason(truncate(failure.message(detail)));
            job.setCompletedAt(now);
            job.setUpdatedAt(now);
            // Any partial output is dropped: a job that failed must not
            // leave a half-written file somebody could be served.
            dropPartialOutput(job);
            job.setOutputObjectKey(null);
            job.setOutputSizeBytes(null);
            return repository.save(job);
        }).orElse(null));

        if (failed != null) {
            metrics.failed(format, failure, took);
            audit(failed, AuditActionCatalog.WATERMARK_FAILED, AuditOutcome.FAILURE, failure);
        }
    }

    private void dropPartialOutput(WatermarkJob job) {
        if (job.getOutputObjectKey() == null || job.getStorageLocationId() == null) {
            return;
        }
        try {
            storageLocationResolver.resolveForTenant(job.getTenantId(), StoragePurpose.staging);
            StorageLocation location = storageLocationResolver.resolveForTenant(job.getTenantId(),
                    StoragePurpose.staging);
            storage.resolve(location).delete(location, job.getOutputObjectKey());
        } catch (RuntimeException e) {
            // The sweeper picks up anything left behind.
            log.warn("Could not delete the partial output of watermark job {}", job.getJobId(), e);
        }
    }

    // ----------------------------------------------------------- helpers

    private WatermarkSpec snapshotOf(WatermarkJob job) {
        try {
            return objectMapper.readValue(job.getRequestSnapshot(), WatermarkSpec.class);
        } catch (Exception e) {
            throw new WatermarkFailed(WatermarkFailure.UNEXPECTED, null);
        }
    }

    /**
     * The metric tag. Derived from the recorded MIME type and deliberately
     * collapsed to a short, closed set - the raw type would be fine today
     * but is the sort of thing that grows.
     */
    private static String formatOf(WatermarkJob job) {
        String mime = job.getSourceMime() == null ? "" : job.getSourceMime();
        return switch (mime) {
            case "application/pdf" -> "pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "image/jpeg" -> "jpeg";
            case "image/png" -> "png";
            default -> "other";
        };
    }

    private static Duration elapsed(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    private static String truncate(String reason) {
        return reason != null && reason.length() > 1000 ? reason.substring(0, 1000) : reason;
    }

    private void audit(WatermarkJob job, String action, AuditOutcome outcome, WatermarkFailure failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentId", job.getDocumentId());
        details.put("versionId", job.getVersionId());
        details.put("templateId", job.getTemplateId());
        details.put("outputSizeBytes", job.getOutputSizeBytes());
        details.put("correlationId", job.getCorrelationId());
        if (failure != null) {
            details.put("failureCode", failure.code());
            details.put("retryable", failure.retryable());
        }
        var event = auditService.event(action)
                .tenantId(job.getTenantId())
                .objectType(AuditObjectTypes.WATERMARK_JOB)
                .objectId(job.getJobId())
                .outcome(outcome)
                .newValue(details);
        if (job.getFailureReason() != null) {
            event.failureReason(job.getFailureReason());
        }
        event.log();
    }

    /** Carries a decided failure out of the drawing step without unwinding through a generic catch. */
    private static final class WatermarkFailed extends RuntimeException {
        private final transient WatermarkFailure failure;
        private final transient String detail;

        private WatermarkFailed(WatermarkFailure failure, String detail) {
            super(null, null, false, false);
            this.failure = failure;
            this.detail = detail;
        }
    }
}
