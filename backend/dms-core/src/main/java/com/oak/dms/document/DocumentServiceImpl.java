package com.oak.dms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DocumentReviewResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.document.dto.OcrFieldResultResponse;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import com.oak.dms.document.dto.ReviewFieldResponse;
import com.oak.dms.document.dto.ReviewQueueItemResponse;
import com.oak.dms.document.dto.RubberBandRequest;
import com.oak.dms.document.dto.RubberBandResponse;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.document.dto.SaveReviewResponse;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderAccessService;
import com.oak.dms.folder.FolderService;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.ocrworker.OcrEnqueueService;
import com.oak.dms.ocrworker.ProcessingJob;
import com.oak.dms.ocrworker.OcrErrorMessages;
import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.ocrworker.ProcessingJobRepository;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * See {@link DocumentService}. Only ever touches storage through
 * {@code ObjectStorageServiceFactory} - no AWS SDK, SeaweedFS-specific, or
 * {@code java.nio.file} type appears anywhere in this class.
 * <p>
 * Never creates a {@link DocumentVersion} itself: uploads go through
 * {@link IngestionGate}, and only {@code DocumentVersionPromoter} creates
 * versions, for scanned-clean files.
 *
 * <h2>OCR (merge note)</h2>
 * The OCR branch's own {@code uploadDocument(MultipartFile, ...)} is gone.
 * It wrote the object itself, created the version itself and started OCR in
 * the same call - all three of which are now somebody else's job, and the
 * first two were only ever safe because that branch had no scan gate. What
 * survived from it is the field-selection validation, which still runs here,
 * and the job/event insert, which moved to {@link OcrEnqueueService} so that
 * the promoter can call it at the one moment a clean version exists.
 * <p>
 * Everything else the OCR branch added - the status poll, the review queue,
 * the review screen, the rubber-band read and the save - is unchanged in
 * substance. Only its authorization moved: each is now behind an {@code os.*}
 * permission AND document-level access (see {@code DocumentController}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentServiceImpl implements DocumentService {

    private static final String EVENT_TYPE_OCR_REQUESTED = "document.version.ocr.requested";
    private static final String AGGREGATE_TYPE_DOCUMENT_VERSION = "document_version";

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentMapper documentMapper;
    private final IngestionGate ingestionGate;
    private final AuditService auditService;
    private final FolderService folderService;
    private final FolderAccessService folderAccessService;
    private final DocumentAccessService documentAccessService;
    private final com.oak.dms.folder.AccessPrincipals accessPrincipals;
    private final DocumentTypeRepository documentTypeRepository;
    private final VersionContent versionContent;

    // OCR. Note the absence of an ObjectStorageService: nothing on the OCR
    // path reads or writes an object directly any more - the worker asks
    // dms-core for bytes over the internal endpoint, and dms-core serves them
    // through the encrypting storage layer like every other read.
    private final DocumentTypeFieldService documentTypeFieldService;
    private final DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    private final DocumentOcrLineItemRepository documentOcrLineItemRepository;
    private final DocumentOcrCorrectionRepository documentOcrCorrectionRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final OcrWorkerClient ocrWorkerClient;
    private final OcrEnqueueService ocrEnqueueService;
    private final DocumentMetadataService documentMetadataService;
    private final MetadataValidationService metadataValidationService;
    private final ObjectMapper objectMapper;
    private final OcrProperties ocrProperties;

    /**
     * Not transactional: the scan gate commits each of its own steps (staging,
     * scanning and promotion must not share one long transaction - and a
     * cloud verdict may arrive long after this returns). The document and its
     * first version are created by {@link DocumentVersionPromoter} only once
     * the file is clean; DOCUMENT_UPLOADED is audited there, at promotion.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                                  long contentLength, InputStream content, UUID folderId) {
        return uploadDocument(documentTypeId, title, originalFilename, contentLength, content, folderId, false);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                                  long contentLength, InputStream content, UUID folderId,
                                                  boolean refuseDuplicates) {
        return uploadDocument(documentTypeId, title, originalFilename, contentLength, content, folderId,
                refuseDuplicates, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                                  long contentLength, InputStream content, UUID folderId,
                                                  boolean refuseDuplicates, List<String> requestedFields) {
        if (folderId != null) {
            // 404/403 now, rather than a document landing nowhere (or somewhere it mustn't) later
            folderService.requireEditable(folderId);
        }
        IngestionRequest request =
                IngestionRequest.newDocument(documentTypeId, title, originalFilename, contentLength, content, folderId);
        request = withOcr(request, documentTypeId, requestedFields, false);
        return ingestionGate.submit(refuseDuplicates ? request.refusingDuplicates() : request);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse uploadDocumentFromJob(UUID documentTypeId, String title, String originalFilename,
                                                         long contentLength, InputStream content, UUID folderId) {
        return uploadDocumentFromJob(documentTypeId, title, originalFilename, contentLength, content, folderId, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse uploadDocumentFromJob(UUID documentTypeId, String title, String originalFilename,
                                                         long contentLength, InputStream content, UUID folderId,
                                                         List<String> requestedFields) {
        if (folderId != null) {
            folderService.requireLive(folderId);
        }
        IngestionRequest request =
                IngestionRequest.newDocument(documentTypeId, title, originalFilename, contentLength, content, folderId);
        return ingestionGate.submit(withOcr(request, documentTypeId, requestedFields, true));
    }

    /**
     * Attaches the OCR field selection to an ingestion request, after checking
     * it against the document type's schema.
     * <p>
     * Validated HERE, at request time, rather than at promotion: a bad field
     * list is the caller's mistake and they are still on the phone, so they get
     * a 400 now instead of a version that silently never gets OCR'd. The gate
     * carries the list through to the promoter, which is where OCR is actually
     * started - only once the file is clean.
     *
     * @param fromJob a bulk-import batch, which is validated the same way but
     *                queued at low priority so interactive scans aren't starved
     */
    private IngestionRequest withOcr(IngestionRequest request, UUID documentTypeId,
                                     List<String> requestedFields, boolean fromJob) {
        if (!ocrProperties.isEnabled() || requestedFields == null || requestedFields.isEmpty()) {
            return request;
        }
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(documentTypeId);
        List<String> requested = new ArrayList<>(new LinkedHashSet<>(requestedFields));
        validateRequestedFields(schema, requested, true);
        return request.withOcr(requested, fromJob);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DocumentListItem> listDocuments(UUID folderId, boolean allFolders, String query, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        String q = query == null || query.isBlank() ? null : query.strip();
        DocumentAccessView access = documentAccessService.forCaller();
        if (folderId != null && !allFolders) {
            folderAccessService.require(access.folders(), folderId, FolderAccessLevel.view);
        }
        Page<Document> page;
        if (!access.seesEverything()) {
            // Folder access (V53) and document access (V54) both apply.
            String title = q == null ? "" : q;
            page = allFolders
                    ? documentRepository.findVisible(tenantId, title, nonEmpty(access.folders().hiddenFolderIds()),
                    access.grantedIds(), pageable)
                    : folderId != null
                    ? documentRepository.findVisibleInFolder(tenantId, title, folderId, access.grantedIds(), pageable)
                    : documentRepository.findVisibleAtTopLevel(tenantId, title, access.grantedIds(), pageable);
        } else if (allFolders) {
            page = q == null ? documentRepository.findByTenantIdAndDeletedFalse(tenantId, pageable)
                    : documentRepository.findByTenantIdAndDeletedFalseAndTitleContainingIgnoreCase(tenantId, q, pageable);
        } else if (folderId != null) {
            page = q == null ? documentRepository.findByTenantIdAndFolderIdAndDeletedFalse(tenantId, folderId, pageable)
                    : documentRepository.findByTenantIdAndFolderIdAndDeletedFalseAndTitleContainingIgnoreCase(tenantId,
                    folderId, q, pageable);
        } else {
            page = q == null ? documentRepository.findByTenantIdAndFolderIdIsNullAndDeletedFalse(tenantId, pageable)
                    : documentRepository.findByTenantIdAndFolderIdIsNullAndDeletedFalseAndTitleContainingIgnoreCase(
                    tenantId, q, pageable);
        }

        return toItems(page, access);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DocumentListItem> listSharedWithMe(String query, Pageable pageable) {
        DocumentAccessView access = documentAccessService.forCaller();
        String title = query == null ? "" : query.strip();
        Page<Document> page = documentRepository.findSharedWith(TenantContext.getTenantId(), title,
                TenantContext.getUserId(), accessPrincipals.callerRoleIds(), pageable);
        return toItems(page, access);
    }

    /** List rows for a page of documents: current version, type name, and the caller's access. */
    private Page<DocumentListItem> toItems(Page<Document> page, DocumentAccessView access) {
        UUID tenantId = TenantContext.getTenantId();
        List<UUID> versionIds = page.getContent().stream()
                .map(d -> d.getCurrentVersionId() != null ? d.getCurrentVersionId() : d.getLatestVersionId())
                .toList();
        Map<UUID, DocumentVersion> versions = new HashMap<>();
        documentVersionRepository.findAllById(versionIds).stream()
                .filter(v -> tenantId.equals(v.getTenantId()))
                .forEach(v -> versions.put(v.getVersionId(), v));
        Map<UUID, String> typeNames = new HashMap<>();
        documentTypeRepository.findAllById(page.getContent().stream().map(Document::getDocumentTypeId).distinct().toList())
                .forEach(t -> typeNames.put(t.getDocumentTypeId(), t.getTypeName()));

        return page.map(d -> {
            DocumentVersion v = versions.get(d.getCurrentVersionId() != null ? d.getCurrentVersionId() : d.getLatestVersionId());
            return new DocumentListItem(d.getDocumentId(), d.getTitle(), d.getDocumentTypeId(),
                    typeNames.get(d.getDocumentTypeId()), d.getFolderId(),
                    v != null ? v.getOriginalFilename() : null, v != null ? v.getSizeBytes() : null,
                    v != null ? v.getMimeType() : null, v != null ? v.getVersionNumber() : null,
                    d.getOwnerUserId(), d.getCreatedAt(), d.getAccessMode() == FolderAccessMode.restricted,
                    access.level(d).name());
        });
    }

    @Override
    public int moveDocuments(List<UUID> documentIds, UUID folderId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentAccessView access = documentAccessService.forCaller();
        String folderName = folderId == null ? null
                : folderAccessService.require(access.folders(), folderId, FolderAccessLevel.edit).getFolderName();
        Set<UUID> wanted = new LinkedHashSet<>(documentIds);
        List<Document> documents = documentRepository.findAllByDocumentIdInAndTenantIdAndDeletedFalse(wanted, tenantId);
        // A document hidden from the caller counts as not found, like any other.
        if (documents.size() != wanted.size() || documents.stream().anyMatch(d -> !access.canSee(d))) {
            throw new ResourceNotFoundException("Document", "one or more of the selected documents");
        }
        // Moving needs edit on the document - for one that follows its folder, edit in that folder.
        for (Document document : documents) {
            documentAccessService.require(access, document, FolderAccessLevel.edit);
        }
        int moved = 0;
        for (Document document : documents) {
            if (Objects.equals(document.getFolderId(), folderId)) {
                continue;
            }
            UUID from = document.getFolderId();
            document.setFolderId(folderId);
            document.setUpdatedBy(TenantContext.getUserId());
            document.setUpdatedAt(OffsetDateTime.now());
            documentRepository.save(document);
            moved++;
            Map<String, Object> before = new HashMap<>();
            before.put("folderId", from);
            Map<String, Object> after = new HashMap<>();
            after.put("folderId", folderId);
            after.put("folderName", folderName);
            auditService.event(AuditActionCatalog.DOCUMENT_MOVED)
                    .objectType(AuditObjectTypes.DOCUMENT)
                    .objectId(document.getDocumentId())
                    .resourceName(document.getTitle())
                    .outcome(AuditOutcome.SUCCESS)
                    .previousValue(before)
                    .newValue(after)
                    .log();
        }
        return moved;
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID documentId) {
        return documentMapper.toResponse(findVisible(documentId, FolderAccessLevel.view));
    }

    /**
     * Read-write on purpose: the DOCUMENT_DOWNLOADED (or DOCUMENT_VIEWED) audit
     * row joins this transaction and is only written when it commits - a
     * read-only transaction silently dropped it.
     */
    @Override
    @Transactional
    public DownloadedDocument downloadDocument(UUID documentId) {
        return serve(findVisible(documentId, FolderAccessLevel.view), AuditActionCatalog.DOCUMENT_DOWNLOADED, null);
    }

    @Override
    @Transactional
    public DownloadedDocument previewDocument(UUID documentId) {
        return serve(findVisible(documentId, FolderAccessLevel.view), AuditActionCatalog.DOCUMENT_VIEWED, null);
    }

    @Override
    @Transactional
    public DownloadedDocument downloadDocumentFromJob(UUID documentId, String job) {
        return serve(findOwnedByCallerTenant(documentId), AuditActionCatalog.DOCUMENT_DOWNLOADED, job);
    }

    /** The document's current (or latest) servable version, audited as {@code action}. */
    private DownloadedDocument serve(Document document, String action, String job) {
        UUID tenantId = TenantContext.getTenantId();
        UUID documentId = document.getDocumentId();

        UUID versionId = document.getCurrentVersionId() != null
                ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        DocumentVersion version = documentVersionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));

        DownloadedDocument downloaded = versionContent.open(version);

        var event = auditService.event(action)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS);
        if (job != null) {
            event.newValue(Map.of("via", job));
        }
        event.log();
        return downloaded;
    }

    @Override
    public void deleteDocument(UUID documentId) {
        Document document = findVisible(documentId, FolderAccessLevel.edit);

        document.setDeleted(true);
        document.setDeletedAt(OffsetDateTime.now());
        document.setUpdatedBy(TenantContext.getUserId());
        document.setUpdatedAt(OffsetDateTime.now());
        documentRepository.save(document);

        log.info("Deleted document {}", documentId);

        // Soft delete: it goes to the recycle bin, restorable until its tenant's
        // retention period ends, then RecycleBinService purges it - every
        // version's file and key destroyed.
        auditService.event(AuditActionCatalog.DOCUMENT_DELETED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    private Document findOwnedByCallerTenant(UUID documentId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    /**
     * The caller's tenant's document, if its folder lets them in at {@code needed}:
     * a document hidden from them (its folder or its own list, V53/V54) is 404, like one that doesn't exist.
     */
    private Document findVisible(UUID documentId, FolderAccessLevel needed) {
        return documentAccessService.require(documentAccessService.forCaller(), findOwnedByCallerTenant(documentId),
                needed);
    }

    /** For a NOT IN clause, which must never be empty. */
    private static Set<UUID> nonEmpty(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of(new UUID(0, 0)) : ids;
    }

    // ----------------------------------------------------------- OCR
    @Override
    @Transactional
    public OcrRerunResponse prepareRerun(UUID versionId, List<String> fields) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentVersion version = requireVersion(versionId, tenantId);
        Document document = documentRepository.findById(version.getDocumentId())
                .orElseThrow(() -> new ResourceNotFoundException("documents", version.getDocumentId()));
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(document.getDocumentTypeId());

        List<String> requested = fields != null ? fields : List.of();
        // Re-running a single optional field must not force the caller to
        // re-supply every mandatory field too -- so, unlike upload, this
        // does NOT enforce mandatory-inclusion, only subset-of-schema.
        validateRequestedFields(schema, requested, false);

        Set<String> effective = new LinkedHashSet<>(readJson(version.getRequestedFields()));
        if (requested.isEmpty()) {
            // Empty means "re-run everything already requested" -- effective
            // set is unchanged, just re-triggered.
        } else {
            effective.addAll(requested);
        }
        List<String> effectiveList = new ArrayList<>(effective);

        version.setRequestedFields(writeJson(effectiveList));
        version.setOcrStatus("pending");
        documentVersionRepository.save(version);

        // Fresh job row + outbox event ONLY if none is already active for
        // this version -- V36's partial unique index allows at most one
        // (queued/processing) processing_jobs row per (reference_type,
        // reference_id), so inserting unconditionally here could violate
        // it. A rerun requested while one is already in flight is
        // therefore a silent no-op on the job/event side: the in-flight
        // run still completes and still picks up the updated
        // requestedFields set just written above, since ocr-worker
        // re-resolves requested_fields from document_versions at claim
        // time, not from the event payload (Phase 1 plan Decision 1).
        // Cancelling/restarting an in-flight run is out of scope for
        // Phase 1.
        List<ProcessingJob> activeJobs = processingJobRepository.findByReferenceTypeAndReferenceIdAndJobTypeAndStatusIn(
                ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION, versionId, ProcessingJob.JOB_TYPE_OCR,
                List.of(ProcessingJob.STATUS_QUEUED, ProcessingJob.STATUS_PROCESSING));
        if (activeJobs.isEmpty()) {
            queueOcrJobAndPublishRequested(tenantId, document.getDocumentId(), versionId, null);
        } else {
            log.info("Skipping new OCR job for version {}: a job is already active (job_id={})", versionId, activeJobs.get(0).getJobId());
        }

        return new OcrRerunResponse(versionId, version.getOcrStatus(), effectiveList);
    }
    /**
     * Re-run: hand the request to the one service that may queue OCR.
     *
     * The job insert and outbox publish that used to live here moved to
     * OcrEnqueueService when the merge made promotion the only place a
     * version appears. Keeping a second copy here would have meant two
     * places that could queue OCR, with only one of them honouring
     * dms.ocr.enabled and the per-tenant limit.
     */
    private void queueOcrJobAndPublishRequested(UUID tenantId, UUID documentId, UUID versionId, UUID correlationId) {
        ocrEnqueueService.enqueue(tenantId, documentId, versionId, correlationId,
                OcrEnqueueService.Source.interactive);
    }

    @Override
    @Transactional(readOnly = true)
    public OcrStatusResponse getOcrStatus(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("documents", documentId));
        if (!document.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("documents", documentId);
        }
        DocumentVersion version = documentVersionRepository.findById(document.getLatestVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("document_versions", document.getLatestVersionId()));

        // ocr-worker JSON-encodes a multi_select field's value to a string
        // before writing document_ocr_field_results (see its own
        // repository.py#upsert_field_results comment) -- that has to be
        // decoded back into a real List<String> here, or the frontend's
        // `value: string | string[] | null` contract would see the raw
        // encoded string (literally "[\"a\",\"b\"]") instead of an array.
        Map<String, String> dataTypeByFieldKey = documentTypeFieldService.getSchema(document.getDocumentTypeId()).fields().stream()
                .collect(java.util.stream.Collectors.toMap(DocumentTypeSchemaResponse.SchemaField::fieldKey, DocumentTypeSchemaResponse.SchemaField::dataType));

        List<DocumentOcrFieldResult> rows = documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId);

        Map<String, OcrFieldResultResponse> fields = new LinkedHashMap<>();
        for (DocumentOcrFieldResult row : rows) {
            OcrFieldResultResponse.Source source = row.getSourcePage() != null
                    ? new OcrFieldResultResponse.Source(row.getSourcePage(), readBbox(row.getSourceBbox()))
                    : null;
            boolean multiSelect = "multi_select".equals(dataTypeByFieldKey.get(row.getFieldKey()));
            Object value = row.getValue() != null && multiSelect ? readJson(row.getValue()) : row.getValue();
            fields.put(row.getFieldKey(), new OcrFieldResultResponse(
                    value,
                    row.getConfidence() != null ? row.getConfidence().doubleValue() : null,
                    source,
                    row.getStatus()));
        }

        String collapsedStatus = collapseOcrStatus(version.getOcrStatus());
        if ("completed".equals(collapsedStatus) && ocrProperties.isPollReapplyEnabled()) {
            // Safety net, not the primary path (Phase 1 B.1): applyOcrFields
            // runs as a side effect of dms-core's own OcrEventsConsumer
            // reacting to the document.version.ocr.completed event
            // ocr-worker publishes. Re-running the (idempotent,
            // override-safe) mapping here, from whatever
            // document_ocr_field_results currently holds, means a client
            // that only ever polls (never receives the event, or polls
            // before the event arrives) still converges to the correct
            // metadata values.
            //
            // It is behind `dms.ocr.poll-reapply-enabled` because this
            // exact code silently masked a real, total failure of the
            // event path for a whole phase (see
            // docs/ocr/phase1-verification.md "Defects found" #1). Setting
            // that flag false is how the event path gets proven on its own
            // merits; leave it true in normal operation.
            try {
                Map<String, Object> ocrFieldsRaw = OcrFieldResultMapper.toOcrFieldsRaw(rows);
                if (!ocrFieldsRaw.isEmpty()) {
                    documentMetadataService.applyOcrFields(documentId, ocrFieldsRaw);
                }
                // Stamp the marker so OcrFieldsReconciler does not later
                // warn about a version this path already rescued. Without
                // it the reconciler would report a lost event for every
                // document the poll had quietly fixed -- which is precisely
                // the silent-rescue behaviour that let a total event-path
                // failure go unnoticed for a phase.
                documentVersionRepository.markOcrFieldsApplied(version.getVersionId(), OffsetDateTime.now());
            } catch (Exception e) {
                log.warn("Could not map OCR fields onto document {} metadata during status poll: {}", documentId, e.getMessage());
            }
        }

        // Error surfacing (Phase 1 B.6): ocr-worker records its classified
        // failure as "CODE: message" in processing_jobs.last_error; the
        // client gets the parsed code (so it can decide whether a retry is
        // even possible) plus a friendly sentence, never the raw text.
        String errorCode = null;
        String friendlyError = null;
        if ("failed".equals(collapsedStatus)) {
            String lastError = processingJobRepository
                    .findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                            ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION, version.getVersionId(), ProcessingJob.JOB_TYPE_OCR)
                    .map(ProcessingJob::getLastError)
                    .orElse(null);
            errorCode = OcrErrorMessages.codeOf(lastError);
            friendlyError = OcrErrorMessages.friendlyMessageFor(errorCode);
        }

        return new OcrStatusResponse(documentId, collapsedStatus, fields, errorCode, friendlyError);
    }

    /** Collapses every real {@code document_versions.ocr_status} value down to the four states the polling contract exposes.
     * 'pending' gets its own "queued" bucket (Phase 1 B.8) rather than
     * collapsing into "processing": since B.1, upload/rerun set 'pending'
     * (queued, unclaimed) and only an ocr-worker claim sets 'processing',
     * so the distinction is now real and worth showing.
     */
    // Package-private (not private) so it can be unit-tested directly,
    // same as validateRequestedFields below -- it has no instance state.
    static String collapseOcrStatus(String ocrStatus) {
        return switch (ocrStatus) {
            case "completed", "not_required" -> "completed";
            case "failed", "unsupported" -> "failed";
            case "pending" -> "queued";
            default -> "processing";
        };
    }

    /**
     * @param enforceMandatory upload requires every mandatory field to be
     *                         requested (there'd otherwise be no way for it
     *                         to ever get a value); a re-run of one optional
     *                         field does not.
     */
    // Package-private (not private) so it can be unit-tested directly
    // without standing up the rest of uploadDocument's dependencies (file
    // storage, metadata validation, etc.) -- it has no instance state.
    static void validateRequestedFields(DocumentTypeSchemaResponse schema, List<String> requestedFields, boolean enforceMandatory) {
        Set<String> visibleFieldKeys = schema.fields().stream()
                .filter(f -> !f.hidden())
                .map(DocumentTypeSchemaResponse.SchemaField::fieldKey)
                .collect(java.util.stream.Collectors.toSet());

        List<ValidationError> errors = new ArrayList<>();
        for (String fieldKey : requestedFields) {
            if (!visibleFieldKeys.contains(fieldKey)) {
                errors.add(new ValidationError(fieldKey, "UNKNOWN_REQUESTED_FIELD",
                        "'" + fieldKey + "' is not a field on this document type.", "error"));
            }
        }

        if (enforceMandatory) {
            Set<String> requestedSet = Set.copyOf(requestedFields);
            for (DocumentTypeSchemaResponse.SchemaField field : schema.fields()) {
                if (!field.hidden() && field.mandatory() && !requestedSet.contains(field.fieldKey())) {
                    errors.add(new ValidationError(field.fieldKey(), "MANDATORY_FIELD_NOT_REQUESTED",
                            "Field '" + field.label() + "' is mandatory and must be requested for OCR extraction.", "error"));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new MetadataValidationFailedException(errors);
        }
    }

    private DocumentVersion requireVersion(UUID versionId, UUID tenantId) {
        DocumentVersion version = documentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("document_versions", versionId));
        if (!version.getTenantId().equals(tenantId)) {
            // Same "not found" a genuinely-missing row would produce --
            // never reveal that a version exists in a different tenant.
            throw new ResourceNotFoundException("document_versions", versionId);
        }
        return version;
    }

    /**
     * JSON of whatever shape it actually is, for a column whose contents
     * are not a list of strings -- {@code raw_cells} is an object keyed by
     * column name (see ocr-worker's {@code line_items.py}).
     * <p>
     * {@link #readJson} cannot be used here: it coerces to
     * {@code List<String>} and swallows the failure as an empty list, so a
     * JSON object would come back as "no raw cells" rather than as an
     * error. That is exactly backwards for a field whose whole job is to
     * show a reviewer what the parse lost.
     */
    private Object readAnyJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable JSON in a line item's raw_cells; omitting it");
            return null;
        }
    }

    private List<String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values != null ? values : List.of());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private double[] readBbox(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<Number> raw = objectMapper.readValue(json, List.class);
            double[] bbox = new double[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                bbox[i] = raw.get(i).doubleValue();
            }
            return bbox;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // MERGE NOTE - sha256Hex and sanitizeForStorageKey were deleted here,
    // along with DocumentServiceStorageKeySanitizationTest.
    //
    // sanitizeForStorageKey patched a real gap the OCR branch found live: its
    // object key embedded file.getOriginalFilename() raw, so a name containing
    // "../" flowed straight into the key. That gap cannot exist on this
    // branch. Keys are built by DocumentVersionPromoter#buildObjectKey from
    // UUIDs and a version number only - the filename never reaches the key,
    // and original_filename is stored as a plain column. The fix is obsoleted
    // by the design, not dropped; if key construction ever takes a
    // caller-supplied string again, that sanitisation must come back with it.
    //
    // sha256Hex went with the old upload, which hashed the bytes itself. The
    // gate computes the hash now (ScanResult.fileHash) and the promoter copies
    // it onto the version.

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewQueueItemResponse> getReviewQueue(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        // Access-filtered in the query, not after it - see
        // findReviewQueueVisible for why filtering a page afterwards is wrong.
        DocumentAccessView access = documentAccessService.forCaller();
        Page<DocumentVersion> versions = access.seesEverything()
                ? documentVersionRepository.findReviewQueue(tenantId, pageable)
                : documentVersionRepository.findReviewQueueVisible(tenantId,
                        nonEmpty(access.folders().hiddenFolderIds()), nonEmpty(access.grantedIds()), pageable);
        return toQueueItems(versions);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewQueueItemResponse> getSavedReviews(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentAccessView access = documentAccessService.forCaller();
        Page<DocumentVersion> versions = access.seesEverything()
                ? documentVersionRepository.findSavedReviews(tenantId, pageable)
                : documentVersionRepository.findSavedReviewsVisible(tenantId,
                        nonEmpty(access.folders().hiddenFolderIds()), nonEmpty(access.grantedIds()), pageable);
        return toQueueItems(versions);
    }

    private Page<ReviewQueueItemResponse> toQueueItems(Page<DocumentVersion> versions) {
        // One findAllById for the page's documents rather than a lookup per
        // row: the queue is read by a human refreshing a list, and 25
        // separate primary-key selects to render one screen is the classic
        // N+1 that only shows up once the queue is busy -- which is exactly
        // when it matters.
        Map<UUID, Document> documents = documentRepository
                .findAllById(versions.getContent().stream().map(DocumentVersion::getDocumentId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(Document::getDocumentId, d -> d));

        return versions.map(version -> {
            Document document = documents.get(version.getDocumentId());
            // The job is only needed to recover the error code for a FAILED
            // version -- a flagged-but-completed one carries its reasons in
            // the version row itself, so there is nothing to look up and no
            // reason to pay for the query.
            ProcessingJob job = "failed".equals(version.getOcrStatus())
                    ? processingJobRepository.findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                            ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION,
                            version.getVersionId(),
                            ProcessingJob.JOB_TYPE_OCR).orElse(null)
                    : null;
            return ReviewQueueMapper.toItem(document, version, job, objectMapper);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentReviewResponse getReview(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = requireDocument(documentId, tenantId);
        DocumentVersion version = documentVersionRepository.findById(document.getLatestVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("document_versions", document.getLatestVersionId()));

        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(document.getDocumentTypeId());
        Map<String, DocumentTypeSchemaResponse.SchemaField> schemaByKey = schema.fields().stream()
                .collect(java.util.stream.Collectors.toMap(
                        DocumentTypeSchemaResponse.SchemaField::fieldKey, f -> f));

        Map<String, DocumentOcrFieldResult> rowsByKey = documentOcrFieldResultRepository
                .findByDocumentIdAndTenantId(documentId, tenantId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        DocumentOcrFieldResult::getFieldKey, r -> r, (a, b) -> a, LinkedHashMap::new));

        // Driven off what was REQUESTED, not off what came back. A
        // mandatory field OCR never found is the most important thing on
        // this screen and the one with no result row -- iterating the rows
        // would drop it silently and show the reviewer a tidy screen with
        // no sign of the thing that sent them here.
        List<String> requested = readJson(version.getRequestedFields());
        LinkedHashSet<String> keys = new LinkedHashSet<>(requested);
        // Anything OCR found that was never requested still gets shown
        // rather than hidden: a value in the database that the screen does
        // not display is worse than an unexpected row.
        keys.addAll(rowsByKey.keySet());

        List<ReviewFieldResponse> fields = keys.stream()
                .sorted(java.util.Comparator.comparingInt(key -> {
                    DocumentTypeSchemaResponse.SchemaField field = schemaByKey.get(key);
                    return field != null ? field.displayOrder() : Integer.MAX_VALUE;
                }))
                .map(key -> {
                    DocumentTypeSchemaResponse.SchemaField schemaField = schemaByKey.get(key);
                    DocumentOcrFieldResult row = rowsByKey.get(key);
                    boolean multiSelect = schemaField != null && "multi_select".equals(schemaField.dataType());
                    Object value = row != null && row.getValue() != null && multiSelect
                            ? readJson(row.getValue())
                            : (row != null ? row.getValue() : null);
                    // Only when the parse changed it. That difference is
                    // where a wrong value usually comes from, and showing
                    // the same string twice everywhere else hides it.
                    String rawValue = row != null && row.getRawValue() != null
                            && !row.getRawValue().equals(row.getValue())
                            ? row.getRawValue()
                            : null;
                    OcrFieldResultResponse.Source source = row != null && row.getSourcePage() != null
                            ? new OcrFieldResultResponse.Source(row.getSourcePage(), readBbox(row.getSourceBbox()))
                            : null;
                    return new ReviewFieldResponse(
                            key,
                            schemaField != null ? schemaField.label() : key,
                            schemaField != null && schemaField.mandatory(),
                            schemaField != null ? schemaField.dataType() : "text",
                            value,
                            rawValue,
                            row != null && row.getConfidence() != null ? row.getConfidence().doubleValue() : null,
                            // No row at all means OCR was asked and came
                            // back with nothing, which is what "not_found"
                            // means -- not an absence of information.
                            row != null ? row.getStatus() : "not_found",
                            source);
                })
                .toList();

        List<DocumentReviewResponse.LineItemResponse> lineItems = documentOcrLineItemRepository
                .findByVersionIdAndTenantIdOrderByLineNumberAsc(version.getVersionId(), tenantId)
                .stream()
                .map(item -> new DocumentReviewResponse.LineItemResponse(
                        item.getLineNumber(),
                        item.getDescription(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getLineTotal(),
                        item.getConfidence() != null ? item.getConfidence().doubleValue() : null,
                        item.getSourcePage(),
                        readBbox(item.getSourceBbox()),
                        readAnyJson(item.getRawCells())))
                .toList();

        // The queue's own row-builder, reused rather than re-derived: the
        // summary the reviewer read in the list and the one at the top of
        // the screen they clicked through to must be the same sentence, and
        // two implementations of "why is this here" would drift.
        ProcessingJob job = processingJobRepository
                .findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
                        ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION,
                        version.getVersionId(),
                        ProcessingJob.JOB_TYPE_OCR)
                .orElse(null);
        ReviewQueueItemResponse queueRow = ReviewQueueMapper.toItem(document, version, job, objectMapper);

        return new DocumentReviewResponse(
                document.getDocumentId(),
                version.getVersionId(),
                document.getTitle(),
                version.getOriginalFilename(),
                version.getMimeType(),
                collapseOcrStatus(version.getOcrStatus()),
                // A document that failed before its file could be opened has
                // no page count at all. One page is the honest fallback for a
                // viewer that must show something -- and the preview endpoint
                // will say so itself if that page cannot be rendered either.
                version.getPageCount() != null ? version.getPageCount() : 1,
                version.isRequiresReview(),
                queueRow.reasons(),
                queueRow.summary(),
                fields,

                lineItems,
                queueRow.errorCode(),
                queueRow.errorCode() != null
                        ? OcrErrorMessages.friendlyMessageFor(queueRow.errorCode())
                        : null);
    }

    // Deliberately NOT @Transactional: the tenant check is one indexed
    // primary-key read, and then this blocks on ocr-worker rendering a
    // page -- seconds, sometimes. Holding a pooled connection open across
    // that is exactly the long-transaction pattern Phase 1 removed from
    // the OCR path in the first place. The two reads below open and close
    // their own.
    @Override
    public OcrWorkerClient.PageImage getPageImage(UUID documentId, int page) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = requireDocument(documentId, tenantId);
        // The tenant check happens HERE, before the version id leaves
        // dms-core. ocr-worker has no notion of who is asking -- it answers
        // for any version id presented with the internal token -- so this is
        // the only thing standing between one tenant and another's pages.
        DocumentVersion version = requireVersion(document.getLatestVersionId(), tenantId);
        return ocrWorkerClient.fetchPageImage(version.getVersionId(), page, ocrProperties.getPreviewDpi());
    }

    // Not @Transactional, for the same reason as getPageImage above.
    @Override
    public RubberBandResponse readRegion(UUID documentId, RubberBandRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = requireDocument(documentId, tenantId);
        DocumentVersion version = requireVersion(document.getLatestVersionId(), tenantId);

        Map<String, Object> answer = ocrWorkerClient.previewRubberBand(
                version.getVersionId(), request.page(),
                request.x0(), request.y0(), request.x1(), request.y1());

        Object confidence = answer.get("confidence");
        return new RubberBandResponse(
                request.page(),
                new double[]{request.x0(), request.y0(), request.x1(), request.y1()},
                (String) answer.getOrDefault("text", ""),
                confidence instanceof Number number ? number.doubleValue() : null);
    }


    @Override
    @Transactional
    public SaveReviewResponse saveReview(UUID documentId, SaveReviewRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        Document document = requireDocument(documentId, tenantId);
        DocumentVersion version = requireVersion(document.getLatestVersionId(), tenantId);

        // The server's own view of what OCR produced, not the client's --
        // an acceptance records OCR's value, and taking that from the
        // request would let a stale or wrong echo be stored as though it
        // were what the model said.
        DocumentReviewResponse current = getReview(documentId);
        Map<String, ReviewFieldResponse> fieldsByKey = new LinkedHashMap<>();
        for (ReviewFieldResponse field : current.fields()) {
            fieldsByKey.put(field.fieldKey(), field);
        }

        List<SaveReviewRequest.Decision> decisions =
                request != null && request.decisions() != null ? request.decisions() : List.of();

        int accepted = 0;
        int corrected = 0;
        Map<String, Object> valuesToApply = new LinkedHashMap<>();
        OffsetDateTime now = OffsetDateTime.now();

        for (SaveReviewRequest.Decision decision : decisions) {
            ReviewFieldResponse field = fieldsByKey.get(decision.fieldKey());
            if (field == null) {
                // A decision about a field this document does not have is
                // an accuracy observation about nothing. Dropped, not
                // stored.
                log.warn("Ignoring review decision for unknown field {} on document {}",
                        decision.fieldKey(), documentId);
                continue;
            }

            String action = ReviewDecisionApplier.effectiveAction(field, decision);
            String value = ReviewDecisionApplier.valueFor(field, decision);

            documentOcrCorrectionRepository.save(DocumentOcrCorrection.builder()
                    .tenantId(tenantId)
                    .documentId(documentId)
                    .versionId(version.getVersionId())
                    .fieldKey(field.fieldKey())
                    .action(action)
                    // Only a real correction has a source; an acceptance
                    // was not produced by anything.
                    .source(DocumentOcrCorrection.ACTION_CORRECTED.equals(action)
                            ? sourceOf(decision)
                            : null)
                    .originalValue(ReviewDecisionApplier.asText(field.value()))
                    .originalConfidence(field.confidence() != null
                            ? java.math.BigDecimal.valueOf(field.confidence())
                            : null)
                    .originalStatus(field.status())
                    .correctedValue(value)
                    .correctedBy(userId)
                    .correctedAt(now)
                    .build());

            if (DocumentOcrCorrection.ACTION_CORRECTED.equals(action)) {
                corrected++;
                // Only a changed value is worth writing back. Re-saving an
                // accepted value would touch document_metadata_values (and
                // its audit trail) for every field on every review.
                //
                // A multi_select value is a LIST. It is joined for display
                // and stored joined in the correction log (one text column,
                // one reviewer decision), but it has to be split back before
                // it reaches saveValues -- which would otherwise store
                // "finance, q3" as a single tag with that literal name.
                valuesToApply.put(field.fieldKey(), splitIfMultiSelect(field, value));
            } else {
                accepted++;
            }
        }

        if (!valuesToApply.isEmpty()) {
            validateCorrections(document, valuesToApply);
            // enforceMandatory=false: a reviewer fixing three of four
            // missing fields must be able to save the three. The document
            // stays in the queue for the fourth, which is the honest
            // outcome -- refusing the save would lose their work instead.
            //
            // That flag also suppresses MetadataValidationService entirely
            // (see writeValues), not just its mandatory arm -- which is why
            // validateCorrections above runs it explicitly first. Without
            // that, a reviewer could type anything into a field with
            // configured rules and it would be stored unchecked.
            documentMetadataService.saveValues(documentId, valuesToApply, false);
        }

        List<String> remaining = ReviewDecisionApplier.unsettled(current.fields(), decisions);
        boolean stillNeedsReview = !remaining.isEmpty();
        version.setRequiresReview(stillNeedsReview);
        documentVersionRepository.save(version);

        log.info("Review saved for document {} version {}: {} accepted, {} corrected, requiresReview={}",
                documentId, version.getVersionId(), accepted, corrected, stillNeedsReview);

        return new SaveReviewResponse(
                documentId, version.getVersionId(), accepted, corrected, stillNeedsReview, remaining);
    }



    /**
     * Runs the reviewer's corrected values through
     * {@link MetadataValidationService}, the same rules an ordinary manual
     * save is held to -- option membership, max length, patterns, ranges.
     * <p>
     * It cannot simply be left to {@code saveValues}: that validates only
     * when {@code enforceMandatory} is true, and the review path passes
     * false so a reviewer can fix three of four missing fields without the
     * fourth rejecting the save. The flag gates the WHOLE validate() call,
     * not just its mandatory arm, so without this a corrected value would
     * be stored with its rules unchecked.
     * <p>
     * Two filters on the result, both necessary:
     * <ul>
     *   <li>{@code MANDATORY_FIELD_MISSING} is dropped. {@code validate}
     *       walks every field in the schema, so a partial submission would
     *       otherwise fail on every mandatory field the reviewer did not
     *       touch -- which is the situation this endpoint exists to work
     *       through, not an error.</li>
     *   <li>Errors about fields not in this submission are dropped. The
     *       reviewer is answerable for what they typed, not for what was
     *       already on the document.</li>
     * </ul>
     * Rejecting before anything is written, rather than per field, is
     * deliberate: the whole save is one transaction, so a mid-batch
     * failure would roll back the corrections already recorded and the
     * reviewer would not know which ones survived.
     */
    private void validateCorrections(Document document, Map<String, Object> corrections) {
        List<ValidationError> blocking = metadataValidationService
                .validate(document.getDocumentTypeId(), null, corrections).stream()
                .filter(error -> "error".equals(error.severity()))
                .filter(error -> !"MANDATORY_FIELD_MISSING".equals(error.errorCode()))
                .filter(error -> corrections.containsKey(error.fieldKey()))
                .toList();
        if (!blocking.isEmpty()) {
            throw new MetadataValidationFailedException(blocking);
        }
    }

    /** See the comment at its call site in {@code saveReview}. */
    static Object splitIfMultiSelect(ReviewFieldResponse field, String value) {
        if (!"multi_select".equals(field.dataType())) {
            return value;
        }
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /**
     * ocr-worker's own vocabulary, not the client's: anything that is not
     * a recognised source is recorded as null rather than stored as a
     * value no query will ever match.
     */
    private static String sourceOf(SaveReviewRequest.Decision decision) {
        if (DocumentOcrCorrection.SOURCE_REGION.equals(decision.source())) {
            return DocumentOcrCorrection.SOURCE_REGION;
        }
        if (DocumentOcrCorrection.SOURCE_TYPED.equals(decision.source())) {
            return DocumentOcrCorrection.SOURCE_TYPED;
        }
        return null;
    }

    private Document requireDocument(UUID documentId, UUID tenantId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("documents", documentId));
        if (!document.getTenantId().equals(tenantId)) {
            // Deliberately the same 404 as "no such document" -- a 403 would
            // confirm that this id exists in some other tenant.
            throw new ResourceNotFoundException("documents", documentId);
        }
        return document;
    }
}
