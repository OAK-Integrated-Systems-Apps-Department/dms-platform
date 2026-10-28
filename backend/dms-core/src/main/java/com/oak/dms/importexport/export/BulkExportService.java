package com.oak.dms.importexport.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessService;
import com.oak.dms.folder.FolderRepository;
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
import java.util.Set;
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
    private final FolderAccessService folderAccessService;
    private final DocumentAccessService documentAccessService;
    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;

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
        return new ExportPreviewResponse(selector.count(TenantContext.getTenantId(), forCaller(request)),
                properties.getMaxDocuments());
    }

    /** Saves the job and starts it once the transaction commits. */
    @Transactional
    public ExportJobResponse request(CreateExportRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        TemplateFormat format = validate(request);
        // Fix what the requester may see now - the worker runs without their login.
        request = forCaller(request);

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
                : request.byFolder() ? ExportType.bulk_folder
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
        details.put("documentCount", count);
        details.put("documentTypeId", request.documentTypeId());
        details.put("folderId", request.folderId());
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
        requireStillAccessible(job);
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
        String filename = filename(job);
        long size = job.getFileSizeBytes() != null ? job.getFileSizeBytes() : object.sizeBytes();
        return new DownloadedExport(filename, size, object.content());
    }

    /**
     * A finished ZIP must not outlive the access it was made with: refused once the requester can't
     * open one of its documents any more (moved into a restricted folder, taken off a list, a role
     * removed) or one was deleted - they can run the export again for what they can see now. An
     * export from before V61 has no list and is served as it is until it expires.
     */
    private void requireStillAccessible(ExportJob job) {
        if (job.getDocumentIds() == null || job.getDocumentIds().length == 0) {
            return;
        }
        Set<UUID> ids = Set.of(job.getDocumentIds());
        List<Document> live = documentRepository.findAllByDocumentIdInAndTenantIdAndDeletedFalse(ids,
                TenantContext.getTenantId());
        DocumentAccessService.DocumentAccessView access = documentAccessService.forCaller();
        long readable = live.stream().filter(access::canSee).count();
        if (readable < ids.size()) {
            throw new BulkImportException(HttpStatus.GONE, "EXPORT_ACCESS_CHANGED", (ids.size() - readable)
                    + " of the documents in this export were deleted or are no longer open to you, so it can't be"
                    + " downloaded any more. Start a new export.");
        }
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
        if (!request.filesIncluded()) {
            throw BulkImportException.badRequest("EXPORT_INVALID",
                    "Downloads hold the documents' files - there is no metadata-only download.");
        }
        // No metadata sheet any more; the column keeps its default for the job row.
        TemplateFormat format = TemplateFormat.xlsx;
        if (request.byIds()) {
            if (request.documentIds().size() > properties.getMaxDocuments()) {
                throw BulkImportException.badRequest("EXPORT_TOO_MANY_DOCUMENTS",
                        "An export can hold at most " + properties.getMaxDocuments() + " documents.");
            }
            return format;
        }
        if (request.byFolder()) {
            // 404 for a folder hidden from the caller, exactly as for one that doesn't exist.
            folderAccessService.require(request.folderId(), FolderAccessLevel.view);
            return format;
        }
        if (request.documentTypeId() == null) {
            throw BulkImportException.badRequest("EXPORT_INVALID",
                    "Choose the documents, a folder, or a document type to export.");
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

    /** The request with what's hidden from the caller set (V53/V54), replacing anything the client sent. */
    private CreateExportRequest forCaller(CreateExportRequest request) {
        DocumentAccessService.DocumentAccessView access = documentAccessService.forCaller();
        return request.withHidden(access.folders().hiddenFolderIds(), documentAccessService.hiddenRestrictedIds(access));
    }

    /** "Finance.zip" for a folder's download, as Google Drive names it; "documents-2026-10-05.zip" for a selection. */
    private String filename(ExportJob job) {
        try {
            CreateExportRequest request = objectMapper.readValue(job.getRequestParams(), CreateExportRequest.class);
            if (request.byFolder()) {
                String name = folderRepository
                        .findByFolderIdAndTenantIdAndDeletedFalse(request.folderId(), job.getTenantId())
                        .map(folder -> ExportArchiveBuilder.safeName(folder.getFolderName(), ""))
                        .orElse("");
                if (!name.isEmpty()) {
                    return name + ".zip";
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Export job {} has unreadable request_params", job.getExportJobId());
        }
        return "documents-" + job.getCreatedAt().toLocalDate() + ".zip";
    }

    private String json(CreateExportRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new java.io.IOException("Could not store the export request", e));
        }
    }
}
