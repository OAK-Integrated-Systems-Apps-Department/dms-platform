package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.filter.AuditRequestContext;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkExportDownload;
import com.oak.dms.importexport.dto.BulkExportFilters;
import com.oak.dms.importexport.dto.BulkExportJobResponse;
import com.oak.dms.importexport.dto.BulkExportPreviewResponse;
import com.oak.dms.importexport.dto.BulkExportSelection;
import com.oak.dms.importexport.dto.CreateBulkExportRequest;
import com.oak.dms.importexport.dto.ExportableDocumentResponse;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import com.oak.dms.importexport.entity.ExportJob.MetadataFormat;
import com.oak.dms.importexport.exception.ActiveExportLimitException;
import com.oak.dms.importexport.exception.BulkExportSelectionException;
import com.oak.dms.importexport.exception.ExportFileUnavailableException;
import com.oak.dms.importexport.export.BulkExportRequestParams;
import com.oak.dms.importexport.export.BulkExportRequestParams.SelectionLine;
import com.oak.dms.importexport.export.ExportableDocumentQuery;
import com.oak.dms.importexport.export.ExportableDocumentRow;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.importexport.service.BulkExportService;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * See {@link BulkExportService}. Which documents a caller may export is
 * decided in one place, {@code BulkExportAccessPolicy}, applied by
 * {@link ExportableDocumentQuery} here and document by document in the
 * worker. A request freezes the document IDs it resolves to into the job,
 * so the export contains what the user saw when they asked, not whatever
 * the filters match by the time the worker runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BulkExportServiceImpl implements BulkExportService {

    public static final String AGGREGATE_TYPE = "export_job";
    public static final String REQUESTED_EVENT_TYPE = "export_job.requested";
    public static final String COMPLETED_EVENT_TYPE = "export_job.completed";
    public static final String EXPIRING_SOON_EVENT_TYPE = "export_job.expiring_soon";

    static final List<ExportJobStatus> ACTIVE_STATUSES = List.of(ExportJobStatus.pending, ExportJobStatus.processing);
    static final int MAX_LIST_PAGE_SIZE = 50;
    static final int MAX_TITLE_FILTER_LENGTH = 500;
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("exportJobId"));
    private static final DateTimeFormatter ZIP_FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ExportableDocumentQuery exportableDocumentQuery;
    private final ExportJobRepository exportJobRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final UserRepository userRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ImportExportProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public Page<ExportableDocumentResponse> listDocuments(BulkExportFilters filters, Pageable pageable) {
        BulkExportFilters given = filters != null ? filters : BulkExportFilters.none();
        validate(given);
        return exportableDocumentQuery
                .page(TenantContext.getTenantId(), TenantContext.getUserId(), given, pageable)
                .map(BulkExportServiceImpl::toDocumentResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public BulkExportPreviewResponse preview(BulkExportSelection selection) {
        BulkExportSelection normalised = normalise(selection);
        ExportableDocumentQuery.Totals totals = exportableDocumentQuery
                .totals(TenantContext.getTenantId(), TenantContext.getUserId(), normalised);
        int maxDocuments = properties.getMaxDocumentsPerExport();
        long maxBytes = properties.getMaxExportSize().toBytes();
        boolean within = totals.documentCount() > 0
                && totals.documentCount() <= maxDocuments
                && totals.totalBytes() <= maxBytes;
        return new BulkExportPreviewResponse(totals.documentCount(), totals.totalBytes(), maxDocuments, maxBytes,
                within);
    }

    @Override
    public BulkExportJobResponse request(CreateBulkExportRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        BulkExportSelection selection = normalise(request.selection());
        MetadataFormat format = request.metadataFormat() != null ? request.metadataFormat() : MetadataFormat.xlsx;

        long active = exportJobRepository.countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(tenantId, userId,
                ACTIVE_STATUSES);
        if (active >= properties.getMaxActiveExportsPerUser()) {
            throw new ActiveExportLimitException("You already have " + active + " export(s) in progress. Wait for "
                    + (active == 1 ? "it" : "one") + " to finish before requesting another.");
        }

        ExportableDocumentQuery.Totals totals = exportableDocumentQuery.totals(tenantId, userId, selection);
        requireWithinLimits(totals);
        List<UUID> documentIds = exportableDocumentQuery.resolveIds(tenantId, userId, selection,
                properties.getMaxDocumentsPerExport());

        BulkExportRequestParams params = new BulkExportRequestParams(
                selection.documentIds() != null ? BulkExportRequestParams.MODE_DOCUMENTS
                        : BulkExportRequestParams.MODE_FILTERS,
                filterValues(selection.filters()), selectionLines(tenantId, selection, documentIds.size()),
                documentIds, totals.totalBytes());
        OffsetDateTime now = OffsetDateTime.now();
        // The job carries the request's correlation id - the one
        // BULK_EXPORT_REQUESTED is recorded under - so the worker can bind it
        // and its events (each DOCUMENT_DOWNLOADED, BULK_EXPORT_COMPLETED)
        // join the same sequence. Off a request there is none; mint one and
        // bind it here too so the requested event still matches the job.
        AuditRequestContext requestContext = AuditRequestContext.currentOrNull();
        UUID correlationId = requestContext != null ? requestContext.getCorrelationId() : UUID.randomUUID();
        ExportJob job;
        try (AuditRequestContext.Scope scope =
                     requestContext == null ? AuditRequestContext.bindBackground(correlationId) : null) {
            // documentCount holds the requested count until the worker sets
            // what actually went into the ZIP.
            job = exportJobRepository.save(ExportJob.builder()
                    .tenantId(tenantId)
                    .exportType(ExportJob.ExportType.bulk_selection)
                    .metadataFormat(format)
                    .requestParams(params.toJson(objectMapper))
                    .status(ExportJobStatus.pending)
                    .documentCount(documentIds.size())
                    .correlationId(correlationId)
                    .createdBy(userId)
                    .createdAt(now)
                    .updatedBy(userId)
                    .updatedAt(now)
                    .build());

            outboxEventPublisher.publish(tenantId, AGGREGATE_TYPE, job.getExportJobId(), REQUESTED_EVENT_TYPE,
                    requestedPayload(job));

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("documentCount", documentIds.size());
            details.put("totalBytes", totals.totalBytes());
            details.put("metadataFormat", format.name());
            details.put("selectionMode", params.selectionMode());
            details.put("filters", params.filters());
            auditService.event(AuditActionCatalog.BULK_EXPORT_REQUESTED)
                    .tenantId(tenantId)
                    .actor(userId, TenantContext.getDisplayNameOrNull())
                    .objectType(AuditObjectTypes.EXPORT_JOB)
                    .objectId(job.getExportJobId())
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(details)
                    .log();
        }

        log.info("Requested bulk export {} for tenant {} ({} documents, {} bytes, {})", job.getExportJobId(),
                tenantId, documentIds.size(), totals.totalBytes(), format);
        return BulkExportJobResponse.from(job, now);
    }

    /** Payload of {@value #REQUESTED_EVENT_TYPE} - what the worker needs to find the job. */
    public static Map<String, Object> requestedPayload(ExportJob job) {
        return Map.of("exportJobId", job.getExportJobId(), "correlationId", job.getCorrelationId());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BulkExportJobResponse> listMine(Pageable pageable) {
        PageRequest request = PageRequest.of(pageable.getPageNumber(),
                Math.min(Math.max(pageable.getPageSize(), 1), MAX_LIST_PAGE_SIZE), NEWEST_FIRST);
        OffsetDateTime now = OffsetDateTime.now();
        return exportJobRepository
                .findAllByTenantIdAndCreatedByAndDeletedFalse(TenantContext.getTenantId(), TenantContext.getUserId(),
                        request)
                .map(job -> BulkExportJobResponse.from(job, now));
    }

    @Override
    @Transactional(readOnly = true)
    public BulkExportJobResponse getMine(UUID exportJobId) {
        return BulkExportJobResponse.from(findMine(exportJobId), OffsetDateTime.now());
    }

    // NOT readOnly: the BULK_EXPORT_DOWNLOADED write below joins this
    // transaction (see AuditService#persist's @Transactional).
    @Override
    public BulkExportDownload openDownload(UUID exportJobId) {
        ExportJob job = findMine(exportJobId);
        if (job.getStatus() == ExportJobStatus.expired) {
            throw ExportFileUnavailableException.expired();
        }
        if (job.getStatus() != ExportJobStatus.completed || job.getFileObjectKey() == null) {
            throw ExportFileUnavailableException.notReady(job.getStatus().name());
        }
        if (job.getExpiresAt() == null || !job.getExpiresAt().isAfter(OffsetDateTime.now())) {
            // Past expiry but not cleaned up yet - expired all the same.
            throw ExportFileUnavailableException.expired();
        }
        StorageLocation location = storageLocationRepository
                .findByStorageLocationIdAndDeletedFalse(job.getFileStorageLocationId())
                .orElseThrow(() -> new IllegalStateException("Storage location " + job.getFileStorageLocationId()
                        + " of export job " + exportJobId + " not found"));
        DownloadedObject file = objectStorageServiceFactory.resolve(location).download(location,
                job.getFileObjectKey());

        auditService.event(AuditActionCatalog.BULK_EXPORT_DOWNLOADED)
                .tenantId(job.getTenantId())
                .actor(TenantContext.getUserId(), TenantContext.getDisplayNameOrNull())
                .objectType(AuditObjectTypes.EXPORT_JOB)
                .objectId(exportJobId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "documentCount", job.getDocumentCount(),
                        "fileSizeBytes", job.getFileSizeBytes() != null ? job.getFileSizeBytes() : file.sizeBytes()))
                .log();

        long size = job.getFileSizeBytes() != null ? job.getFileSizeBytes() : file.sizeBytes();
        return new BulkExportDownload(zipFilename(job), size, file.content());
    }

    /** {@code <yyyyMMdd-HHmmss>-export.zip}, the time the ZIP was built, in UTC. */
    static String zipFilename(ExportJob job) {
        OffsetDateTime at = job.getCompletedAt() != null ? job.getCompletedAt() : job.getCreatedAt();
        return ZIP_FILENAME_TIMESTAMP.format(at.withOffsetSameInstant(ZoneOffset.UTC)) + "-export.zip";
    }

    private ExportJob findMine(UUID exportJobId) {
        return exportJobRepository.findByExportJobIdAndTenantIdAndCreatedByAndDeletedFalse(exportJobId,
                        TenantContext.getTenantId(), TenantContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("ExportJob", exportJobId));
    }

    /** Exactly one of documentIds / filters; IDs de-duplicated, in the order given. */
    BulkExportSelection normalise(BulkExportSelection selection) {
        if (selection == null || (selection.documentIds() == null && selection.filters() == null)) {
            throw new BulkExportSelectionException("EXPORT_SELECTION_REQUIRED",
                    "Choose the documents to export, either one by one or with filters.");
        }
        if (selection.documentIds() != null && selection.filters() != null) {
            throw new BulkExportSelectionException("EXPORT_SELECTION_AMBIGUOUS",
                    "Choose the documents to export either one by one or with filters, not both.");
        }
        if (selection.filters() != null) {
            validate(selection.filters());
            return selection;
        }
        if (selection.documentIds().stream().anyMatch(Objects::isNull)) {
            throw new BulkExportSelectionException("EXPORT_SELECTION_INVALID", "A selected document ID is empty.");
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(selection.documentIds()));
        if (ids.isEmpty()) {
            throw new BulkExportSelectionException("EXPORT_NOTHING_SELECTED", "Select at least one document to export.");
        }
        if (ids.size() > properties.getMaxDocumentsPerExport()) {
            throw tooManyDocuments(ids.size());
        }
        return new BulkExportSelection(ids, null);
    }

    private static void validate(BulkExportFilters filters) {
        if (filters.createdFrom() != null && filters.createdTo() != null
                && !filters.createdFrom().isBefore(filters.createdTo())) {
            throw new BulkExportSelectionException("EXPORT_SELECTION_INVALID",
                    "The 'created from' date must be before the 'created to' date.");
        }
        if (filters.titleContains() != null && filters.titleContains().length() > MAX_TITLE_FILTER_LENGTH) {
            throw new BulkExportSelectionException("EXPORT_SELECTION_INVALID",
                    "The title filter can be at most " + MAX_TITLE_FILTER_LENGTH + " characters.");
        }
    }

    private void requireWithinLimits(ExportableDocumentQuery.Totals totals) {
        if (totals.documentCount() == 0) {
            throw new BulkExportSelectionException("EXPORT_NOTHING_SELECTED",
                    "None of the selected documents can be exported. Only documents you can view whose current"
                            + " version has passed the malware scan can be exported.");
        }
        if (totals.documentCount() > properties.getMaxDocumentsPerExport()) {
            throw tooManyDocuments(totals.documentCount());
        }
        long maxBytes = properties.getMaxExportSize().toBytes();
        if (totals.totalBytes() > maxBytes) {
            throw new BulkExportSelectionException("EXPORT_TOO_LARGE",
                    "The selected documents total " + totals.totalBytes() + " bytes; an export can be at most "
                            + maxBytes + " bytes. Narrow the selection and try again.");
        }
    }

    private BulkExportSelectionException tooManyDocuments(long count) {
        return new BulkExportSelectionException("EXPORT_TOO_MANY_DOCUMENTS",
                count + " documents are selected; an export can contain at most "
                        + properties.getMaxDocumentsPerExport() + ". Narrow the selection and try again.");
    }

    private static Map<String, String> filterValues(BulkExportFilters filters) {
        Map<String, String> values = new LinkedHashMap<>();
        if (filters == null) {
            return values;
        }
        putIfSet(values, "documentTypeId", filters.documentTypeId());
        putIfSet(values, "createdFrom", filters.createdFrom());
        putIfSet(values, "createdTo", filters.createdTo());
        putIfSet(values, "uploadedBy", filters.uploadedBy());
        putIfSet(values, "titleContains", filters.titleContains() != null && !filters.titleContains().isBlank()
                ? filters.titleContains().trim() : null);
        putIfSet(values, "importBatchId", filters.importBatchId());
        return values;
    }

    private static void putIfSet(Map<String, String> values, String key, Object value) {
        if (value != null) {
            values.put(key, value.toString());
        }
    }

    /** The selection as the "About this export" sheet describes it, names resolved now. */
    private List<SelectionLine> selectionLines(UUID tenantId, BulkExportSelection selection, int documentCount) {
        List<SelectionLine> lines = new ArrayList<>();
        if (selection.documentIds() != null) {
            lines.add(new SelectionLine("Selection", documentCount + " documents picked one by one"));
            return lines;
        }
        BulkExportFilters filters = selection.filters();
        lines.add(new SelectionLine("Selection", "Documents matching the filters below"));
        int before = lines.size();
        if (filters.documentTypeId() != null) {
            lines.add(new SelectionLine("Document type", documentTypeRepository
                    .findByDocumentTypeIdAndTenantIdAndDeletedFalse(filters.documentTypeId(), tenantId)
                    .map(DocumentType::getTypeName)
                    .orElse(filters.documentTypeId().toString())));
        }
        if (filters.createdFrom() != null) {
            lines.add(new SelectionLine("Created from", filters.createdFrom().toString()));
        }
        if (filters.createdTo() != null) {
            lines.add(new SelectionLine("Created before", filters.createdTo().toString()));
        }
        if (filters.uploadedBy() != null) {
            lines.add(new SelectionLine("Uploaded by", userRepository
                    .findByUserIdAndTenantIdAndDeletedFalse(filters.uploadedBy(), tenantId)
                    .map(User::getDisplayName)
                    .orElse(filters.uploadedBy().toString())));
        }
        if (filters.titleContains() != null && !filters.titleContains().isBlank()) {
            lines.add(new SelectionLine("Title contains", filters.titleContains().trim()));
        }
        if (filters.importBatchId() != null) {
            lines.add(new SelectionLine("Bulk import", filters.importBatchId().toString()));
        }
        if (lines.size() == before) {
            lines.add(new SelectionLine("Filters", "None - every document you can export"));
        }
        return lines;
    }

    private static ExportableDocumentResponse toDocumentResponse(ExportableDocumentRow row) {
        return new ExportableDocumentResponse(row.documentId(), row.title(), row.documentTypeId(),
                row.documentTypeName(), row.uploadedById(), row.uploadedByName(), row.createdAt(), row.sizeBytes());
    }
}
