package com.oak.dms.watermark.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.repository.WatermarkJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reading a watermark job back, and fetching what it produced.
 *
 * <h2>The result endpoint re-checks everything</h2>
 * A finished watermark is a second copy of a document, sitting in storage
 * with a download link, carrying somebody's name. The checks that mattered
 * when it was requested are not the checks that matter when it is collected:
 * in between, the document may have been deleted, the requester may have
 * lost access to its folder, or the output may have expired and been swept.
 * So {@link #download} re-establishes all of it rather than trusting that
 * the job exists.
 * <p>
 * Access is re-checked by going back through
 * {@link DocumentContentReader#describe}, which is the same path the request
 * used - so "can this person still open the document?" is answered by the
 * one piece of code that knows, rather than by a copy of its rules.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatermarkJobService {

    private final WatermarkJobRepository jobRepository;
    private final WatermarkService watermarkService;
    private final DocumentContentReader documentContentReader;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory storage;
    private final AuditService auditService;

    /** What was downloaded, and under what name. */
    public record DownloadedWatermark(InputStream content, long sizeBytes, String contentType, String filename) {
    }

    /**
     * One of the caller's OWN jobs. Another user's job in the same tenant is
     * a 404: a watermarked copy carries the name of whoever asked for it, so
     * even inside one tenant it isn't somebody else's to look at.
     */
    @Transactional(readOnly = true)
    public WatermarkJobResponse get(UUID jobId) {
        WatermarkJob job = own(jobId);
        return WatermarkJobResponse.from(job, watermarkService.textOf(job));
    }

    @Transactional
    public DownloadedWatermark download(UUID jobId) {
        WatermarkJob job = own(jobId);

        if (job.getStatus() != WatermarkJobStatus.completed || job.getOutputObjectKey() == null) {
            throw WatermarkException.badRequest("WATERMARK_NOT_READY",
                    switch (job.getStatus()) {
                        case pending, processing -> "This watermarked copy is still being produced.";
                        case failed -> "This watermark could not be produced"
                                + (job.getFailureReason() == null ? "." : ": " + job.getFailureReason());
                        default -> "This watermarked copy is no longer available.";
                    });
        }
        if (job.getPurgedAt() != null
                || (job.getExpiresAt() != null && job.getExpiresAt().isBefore(OffsetDateTime.now()))) {
            // GONE rather than NOT_FOUND: it existed, the caller is right to
            // think it did, and asking again for a new one is the fix.
            throw new WatermarkException(org.springframework.http.HttpStatus.GONE, "WATERMARK_EXPIRED",
                    "This watermarked copy has expired and been deleted. Ask for a new one.");
        }

        // Re-checked NOW, not as it was when the job was requested: the
        // document may have been deleted, or the folder closed to them,
        // since. Throws 404 if either.
        var version = documentContentReader.describe(job.getDocumentId(), job.getVersionId());

        StorageLocation location = storageLocationRepository
                .findByStorageLocationIdAndDeletedFalse(job.getStorageLocationId())
                .orElseThrow(() -> new WatermarkException(org.springframework.http.HttpStatus.GONE,
                        "WATERMARK_EXPIRED",
                        "This watermarked copy is no longer available. Ask for a new one."));
        var object = storage.resolve(location).download(location, job.getOutputObjectKey());

        auditDownloaded(job, version.title());
        return new DownloadedWatermark(object.content(),
                job.getOutputSizeBytes() == null ? object.sizeBytes() : job.getOutputSizeBytes(),
                job.getSourceMime(), filenameFor(version.originalFilename()));
    }

    /**
     * {@code report-watermarked.pdf}. The extension is kept so the file
     * opens in the right application; the suffix makes it obvious at a
     * glance that this is not the original, which matters when someone has
     * both in a downloads folder.
     */
    static String filenameFor(String originalFilename) {
        String name = originalFilename == null || originalFilename.isBlank() ? "document" : originalFilename;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return stem + "-watermarked" + extension;
    }

    private WatermarkJob own(UUID jobId) {
        return jobRepository
                .findByJobIdAndTenantIdAndRequestedBy(jobId, TenantContext.getTenantId(), TenantContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("WatermarkJob", jobId));
    }

    /**
     * HIGH severity, like EXPORT_DOWNLOADED: this is the moment a copy of a
     * document leaves the system - one carrying the name of the person
     * taking it.
     */
    private void auditDownloaded(WatermarkJob job, String title) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentId", job.getDocumentId());
        details.put("versionId", job.getVersionId());
        details.put("templateId", job.getTemplateId());
        details.put("outputSizeBytes", job.getOutputSizeBytes());
        details.put("correlationId", job.getCorrelationId());

        auditService.event(AuditActionCatalog.WATERMARK_RESULT_DOWNLOADED)
                .objectType(AuditObjectTypes.WATERMARK_JOB)
                .objectId(job.getJobId())
                .resourceName(title)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(details)
                .log();
    }
}
