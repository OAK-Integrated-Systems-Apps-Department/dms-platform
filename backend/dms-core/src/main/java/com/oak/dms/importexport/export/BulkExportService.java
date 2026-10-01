package com.oak.dms.importexport.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ExportJobProperties;
import com.oak.dms.importexport.dto.CreateExportRequest;
import com.oak.dms.importexport.dto.ExportJobResponse;
import com.oak.dms.importexport.dto.ExportPreviewResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.importexport.enums.ExportType;
import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk export (Module 2.1, FIE-FR-010/011), request side: what an export
 * would contain, starting one, downloading the result, and the purge of
 * expired files. Jobs belong to the user who requested them, like "My
 * exports" ({@link com.oak.dms.importexport.service.ExportJobService#listMine}).
 * Needs {@link TenantContext}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkExportService {

    private static final List<ExportJobStatus> ACTIVE = List.of(ExportJobStatus.pending, ExportJobStatus.processing);

    private final ExportJobRepository repository;
    private final ExportDocumentSelector selector;
    private final ExportJobWorker worker;
    private final DocumentTypeRepository documentTypeRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final AuditService auditService;
    private final ExportJobProperties properties;
    private final ObjectMapper objectMapper;

    /** A finished export ready to stream. */
    public record DownloadedExport(String filename, long sizeBytes, java.io.InputStream content) {
    }

    /** Every non-deleted document type of the tenant - documents of an inactive type can still be exported. */
    @Transactional(readOnly = true)
    public List<ImportDocumentTypeOption> documentTypes() {
        return documentTypeRepository.findAllByTenantOrderedByDisplayOrder(TenantContext.getTenantId()).stream()
                .filter(type -> !type.isDeleted())
                .map(type -> new ImportDocumentTypeOption(type.getDocumentTypeId(), type.getTypeName(), type.getTypeCode()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExportPreviewResponse preview(CreateExportRequest request) {
        validate(request);
        return new ExportPreviewResponse(selector.count(TenantContext.getTenantId(), request), properties.getMaxDocuments());
    }

    /** Saves the job and starts it once the transaction commits. */
    @Transactional
    public ExportJobResponse request(CreateExportRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        TemplateFormat format = validate(request);

        if (repository.countByTenantIdAndRequestedByAndStatusIn(tenantId, userId, ACTIVE)
                >= properties.getMaxActiveJobsPerUser()) {
            throw BulkImportException.conflict("EXPORT_LIMIT_REACHED", "You already have "
                    + properties.getMaxActiveJobsPerUser() + " exports in progress. Wait for one to finish.");
        }
        long count = selector.count(tenantId, request);
        if (count == 0) {
            throw BulkImportException.badRequest("EXPORT_EMPTY", "No documents match this export.");
        }
        if (count > properties.getMaxDocuments()) {
            throw BulkImportException.badRequest("EXPORT_TOO_MANY_DOCUMENTS", count + " documents match, but an export "
                    + "can hold at most " + properties.getMaxDocuments() + ". Narrow the date range.");
        }

        ExportType type = !request.filesIncluded() ? ExportType.report_data
                : request.byIds() && request.documentIds().size() == 1 ? ExportType.single_document
                : ExportType.bulk_selection;
        ExportJob job = repository.save(ExportJob.builder()
                .tenantId(tenantId)
                .requestedBy(userId)
                .exportType(type)
                .metadataFormat(format)
                .requestParams(json(request))
                .documentCount((int) count)
                .correlationId(UUID.randomUUID())
                .build());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("exportType", type.name());
        details.put("metadataFormat", format.name());
        details.put("documentCount", count);
        details.put("documentTypeId", request.documentTypeId());
        details.put("createdFrom", request.createdFrom() != null ? request.createdFrom().toString() : null);
        details.put("createdTo", request.createdTo() != null ? request.createdTo().toString() : null);
        details.put("includeFiles", request.filesIncluded());
        auditService.event(AuditActionCatalog.EXPORT_REQUESTED)
                .objectType(AuditObjectTypes.EXPORT_JOB)
                .objectId(job.getExportJobId())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(details)
                .log();

        UUID jobId = job.getExportJobId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    worker.start(jobId);
                }
            });
        } else {
            worker.start(jobId);
        }
        return ExportJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public ExportJobResponse get(UUID jobId) {
        return ExportJobResponse.from(own(jobId));
    }

    /** The finished ZIP, while it hasn't expired. Audited as EXPORT_DOWNLOADED. */
    public DownloadedExport download(UUID jobId) {
        ExportJob job = own(jobId);
        if (job.getStatus() == ExportJobStatus.expired
                || (job.getExpiresAt() != null && job.getExpiresAt().isBefore(OffsetDateTime.now()))) {
            throw new BulkImportException(HttpStatus.GONE, "EXPORT_EXPIRED",
                    "This export has expired and its file was removed. Start a new export.");
        }
        if (job.getStatus() != ExportJobStatus.completed || job.getFileObjectKey() == null) {
            throw BulkImportException.conflict("EXPORT_NOT_READY", "This export isn't ready to download yet.");
        }
        StorageLocation location = storageLocationRepository
                .findByStorageLocationIdAndDeletedFalse(job.getStorageLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("ExportJob file", jobId));
        DownloadedObject object = objectStorageServiceFactory.resolve(location).download(location, job.getFileObjectKey());

        auditService.event(AuditActionCatalog.EXPORT_DOWNLOADED)
                .objectType(AuditObjectTypes.EXPORT_JOB)
                .objectId(jobId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("documentCount", job.getDocumentCount(),
                        "fileSizeBytes", job.getFileSizeBytes() != null ? job.getFileSizeBytes() : object.sizeBytes()))
                .log();
        String filename = "export-" + job.getCreatedAt().toLocalDate() + "-" + jobId.toString().substring(0, 8) + ".zip";
        long size = job.getFileSizeBytes() != null ? job.getFileSizeBytes() : object.sizeBytes();
        return new DownloadedExport(filename, size, object.content());
    }

    /**
     * Deletes the files of completed exports past their expiry and marks
     * them expired (FIE: exports are auto-purged). Hourly; a file that
     * can't be deleted is retried on the next run.
     */
    @Scheduled(fixedDelayString = "${dms.export.purge-interval:PT1H}", initialDelayString = "PT5M")
    public void purgeExpired() {
        for (ExportJob job : repository.findByStatusAndExpiresAtBefore(ExportJobStatus.completed, OffsetDateTime.now())) {
            try {
                if (job.getFileObjectKey() != null && job.getStorageLocationId() != null) {
                    storageLocationRepository.findByStorageLocationIdAndDeletedFalse(job.getStorageLocationId())
                            .ifPresent(location -> objectStorageServiceFactory.resolve(location)
                                    .delete(location, job.getFileObjectKey()));
                }
                repository.lockById(job.getExportJobId()).ifPresent(locked -> {
                    locked.setStatus(ExportJobStatus.expired);
                    locked.setFileObjectKey(null);
                    locked.setUpdatedAt(OffsetDateTime.now());
                    repository.save(locked);
                });
                log.info("Purged expired export {}", job.getExportJobId());
            } catch (RuntimeException e) {
                log.warn("Could not purge export {}: {}", job.getExportJobId(), e.getMessage());
            }
        }
    }

    private TemplateFormat validate(CreateExportRequest request) {
        if (request == null) {
            throw BulkImportException.badRequest("EXPORT_INVALID", "Say what to export.");
        }
        TemplateFormat format = request.metadataFormat() == null || request.metadataFormat().isBlank()
                ? TemplateFormat.xlsx : TemplateFormat.fromValue(request.metadataFormat());
        if (request.byIds()) {
            if (request.documentIds().size() > properties.getMaxDocuments()) {
                throw BulkImportException.badRequest("EXPORT_TOO_MANY_DOCUMENTS",
                        "An export can hold at most " + properties.getMaxDocuments() + " documents.");
            }
            return format;
        }
        if (request.documentTypeId() == null) {
            throw BulkImportException.badRequest("EXPORT_INVALID", "Choose the documents, or a document type to export.");
        }
        documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(request.documentTypeId(),
                        TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", request.documentTypeId()));
        if (request.createdFrom() != null && request.createdTo() != null
                && request.createdFrom().isAfter(request.createdTo())) {
            throw BulkImportException.badRequest("EXPORT_INVALID", "The start date is after the end date.");
        }
        return format;
    }

    private ExportJob own(UUID jobId) {
        return repository.findByExportJobIdAndTenantId(jobId, TenantContext.getTenantId())
                .filter(job -> job.getRequestedBy().equals(TenantContext.getUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("ExportJob", jobId));
    }

    private String json(CreateExportRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new java.io.IOException("Could not store the export request", e));
        }
    }
}
