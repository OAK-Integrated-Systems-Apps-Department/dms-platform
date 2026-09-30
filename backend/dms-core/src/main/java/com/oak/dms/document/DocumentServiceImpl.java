package com.oak.dms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DocumentReviewResponse;
import com.oak.dms.document.dto.DocumentVersionResponse;
import com.oak.dms.document.dto.OcrFieldResultResponse;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import com.oak.dms.document.dto.ReviewFieldResponse;
import com.oak.dms.document.dto.ReviewQueueItemResponse;
import com.oak.dms.document.dto.RubberBandRequest;
import com.oak.dms.document.dto.RubberBandResponse;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.document.dto.SaveReviewResponse;
import com.oak.dms.metadata.DocumentType;
import com.oak.dms.metadata.DocumentTypeFieldService;
import com.oak.dms.metadata.DocumentTypeRepository;
import com.oak.dms.metadata.MetadataValidationService;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.ocrworker.OcrErrorMessages;
import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.ocrworker.ProcessingJob;
import com.oak.dms.ocrworker.ProcessingJobRepository;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    // Tika's facade is thread-safe and meant to be reused across calls
    // (its own javadoc) -- not a Spring bean since it needs no
    // configuration/DI, just detection.
    private static final Tika TIKA = new Tika();

    // Path separators and control characters only -- the MinIO object key
    // is built by joining this against tenantId/documentId/versionId with
    // "/" ourselves (see below); a filename containing one could otherwise
    // inject extra path segments into the key. NFC-normalized afterward so
    // visually-identical Unicode filenames always produce the same key.
    private static final Pattern UNSAFE_STORAGE_KEY_CHARS = Pattern.compile("[/\\\\\\p{Cntrl}]");

    /** Matches ocr-worker's own routing key constant (config.py's ROUTING_KEY_OCR_REQUESTED) exactly. */
    private static final String EVENT_TYPE_OCR_REQUESTED = "document.version.ocr.requested";
    private static final String AGGREGATE_TYPE_DOCUMENT_VERSION = "document_version";

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    private final ObjectStorageService objectStorageService;
    private final ProcessingJobRepository processingJobRepository;
    private final DocumentOcrLineItemRepository documentOcrLineItemRepository;
    private final DocumentOcrCorrectionRepository documentOcrCorrectionRepository;
    private final OcrWorkerClient ocrWorkerClient;
    private final OutboxEventPublisher outboxEventPublisher;
    private final DocumentMetadataService documentMetadataService;
    private final MetadataValidationService metadataValidationService;
    private final ObjectMapper objectMapper;

    /** See {@code dms.ocr.poll-reapply-enabled}. Phase 2 moved the
     * {@code dms.ocr.*} keys into {@link com.oak.dms.ocrworker.OcrProperties}
     * once the reconciler added four more of them -- a single scalar was
     * fine as a {@code @Value}, five are not.
     */
    private final OcrProperties ocrProperties;

    @Override
    @Transactional
    public DocumentVersionResponse uploadDocument(MultipartFile file, String documentTypeName, Map<String, Object> metadataValues,
                                                   List<String> requestedFields) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Document types are now a governed catalog (module 2.7 - Metadata &
        // Document-Type Management), seeded per tenant with CONTRACT/
        // INVOICE/POLICY/GENERAL (V30) and managed via DocumentTypeService's
        // own create/update flow (its own permission checks, display-order
        // normalization, etc.). This upload path only looks one up by its
        // typeCode -- it deliberately does NOT auto-create a missing one,
        // since doing so here would let any user who can upload a file
        // silently mint new document types, bypassing whatever permission
        // the real creation flow enforces.
        String typeCode = documentTypeName.trim().toUpperCase();
        DocumentType documentType = documentTypeRepository
                .findByTenantIdAndTypeCodeAndDeletedFalse(tenantId, typeCode)
                .orElseThrow(() -> new ResourceNotFoundException("document_types", typeCode));

        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(documentType.getDocumentTypeId());
        List<String> effectiveRequestedFields = requestedFields != null ? requestedFields : List.of();
        // Upload is the one place a missing mandatory field IS a hard
        // reject on requestedFields (unlike metadataValues below, which may
        // legitimately be blank at upload time -- OCR fills those in
        // afterward): a mandatory field the user never asked OCR to look
        // for, and never filled in manually either, would have no path to
        // ever getting a value.
        validateRequestedFields(schema, effectiveRequestedFields, true);

        // Upload never requires mandatory fields to already be filled in --
        // that's the whole point of OCR field-mapping (see
        // DocumentMetadataService#applyOcrFields): the user picks a
        // document type, uploads the file, and OCR fills in the fields
        // afterward. This only rejects real format errors (e.g. an
        // unparseable date/number) in whatever values were given, never a
        // missing-mandatory-field error.
        Map<String, Object> values = metadataValues != null ? metadataValues : Map.of();
        List<ValidationError> errors = metadataValidationService.validate(documentType.getDocumentTypeId(), null, values);
        List<ValidationError> blocking = errors.stream()
                .filter(e -> "error".equals(e.severity()) && !"MANDATORY_FIELD_MISSING".equals(e.errorCode()))
                .toList();
        if (!blocking.isEmpty()) {
            throw new MetadataValidationFailedException(blocking);
        }

        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String storageObjectKey = "tenants/%s/documents/%s/versions/%s/%s"
                .formatted(tenantId, documentId, versionId, sanitizeForStorageKey(originalFilename));

        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        // Real gap found live: file.getContentType() is a client-supplied
        // header, trusted outright before this -- Tika sniffs the actual
        // bytes instead. Falls back to the client-supplied header only
        // when Tika's own detection is inconclusive (its own contract:
        // "application/octet-stream" for content it can't identify),
        // matching the "never silently guess, but don't discard a real
        // signal either" discipline the ocr-worker side already follows
        // for the same problem.
        String detectedMimeType = TIKA.detect(content);
        String mimeType = !"application/octet-stream".equals(detectedMimeType)
                ? detectedMimeType
                : (file.getContentType() != null ? file.getContentType() : "application/octet-stream");

        objectStorageService.putSourceObject(storageObjectKey, content, mimeType);

        // documents.latest_version_id <-> document_versions.document_id are
        // mutually NOT NULL foreign keys into each other -- see V32's own
        // comment for why both were declared DEFERRABLE INITIALLY DEFERRED
        // specifically so this works: both rows (client-generated UUIDs,
        // assigned above) are persisted in one transaction, and the FK
        // check only runs at commit, by which point both exist.
        Document document = Document.builder()
                .documentId(documentId)
                .tenantId(tenantId)
                .documentTypeId(documentType.getDocumentTypeId())
                .title(originalFilename)
                .latestVersionId(versionId)
                .currentVersionId(versionId)
                .ownerUserId(userId)
                .createdBy(userId)
                .build();
        documentRepository.save(document);

        // Section 2.2 (malware scanning) has no real implementation yet --
        // backend/workers/scan-worker/ doesn't exist (see ocr-worker's own
        // README "Known upstream gap"). This upload path stands in for it
        // by setting scan_status='clean' immediately -- the same
        // documented gap ocr-worker's own dev-trigger endpoint and fixture
        // scripts already work around, never silently pretended away.
        //
        // ocr_status starts at 'pending' (Phase 1 B.1) -- "queued, not yet
        // claimed" -- not 'processing', which is reserved for a live claim
        // held by an actual ocr-worker pool worker (see V36's leasing
        // columns). queueOcrJobAndPublishRequested, below, is what actually
        // makes this version eligible for OCR: it inserts the
        // processing_jobs row and the document.version.ocr.requested
        // outbox event in this SAME transaction, replacing the old
        // synchronous HTTP call to ocr-worker that used to run after this
        // method returned.
        DocumentVersion version = DocumentVersion.builder()
                .versionId(versionId)
                .documentId(documentId)
                .tenantId(tenantId)
                .versionNumber(1)
                .majorVersion(1)
                .minorVersion(0)
                .storageObjectKey(storageObjectKey)
                .originalFilename(originalFilename)
                .mimeType(mimeType)
                .fileHash(sha256Hex(content))
                .sizeBytes(content.length)
                .createdReason("initial_upload")
                .scanStatus("clean")
                .ocrStatus("pending")
                .requestedFields(writeJson(effectiveRequestedFields))
                .createdBy(userId)
                .build();
        documentVersionRepository.save(version);

        if (!values.isEmpty()) {
            // Already validated above; no need to re-validate here.
            documentMetadataService.saveValues(documentId, values, false);
        }

        queueOcrJobAndPublishRequested(tenantId, documentId, versionId, null);

        log.info("Uploaded document {} version {} ({} bytes) for tenant {}, requestedFields={}",
                documentId, versionId, content.length, tenantId, effectiveRequestedFields);

        return new DocumentVersionResponse(
                documentId, versionId, document.getTitle(), originalFilename,
                version.getMimeType(), version.getSizeBytes(), version.getScanStatus(),
                version.getOcrStatus(), version.getCreatedAt());
    }

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

    /** Inserts a fresh 'queued' processing_jobs row and publishes a
     * document.version.ocr.requested outbox event, both in the caller's
     * own transaction (Phase 1 B.1) -- replaces the old synchronous HTTP
     * call to ocr-worker that used to run after the caller's transaction
     * committed. No resolved field specs in the payload: ocr-worker
     * re-queries document_versions.requested_fields itself at claim time
     * (Phase 1 plan Decision 1), so this stays a cheap, minimal event.
     */
    private void queueOcrJobAndPublishRequested(UUID tenantId, UUID documentId, UUID versionId, UUID correlationId) {
        processingJobRepository.save(ProcessingJob.builder()
                .jobId(UUID.randomUUID())
                .tenantId(tenantId)
                .jobType(ProcessingJob.JOB_TYPE_OCR)
                .referenceType(ProcessingJob.REFERENCE_TYPE_DOCUMENT_VERSION)
                .referenceId(versionId)
                .status(ProcessingJob.STATUS_QUEUED)
                .attempts(0)
                .correlationId(correlationId)
                .build());

        // LinkedHashMap, not Map.of: correlation_id must serialize as a
        // real JSON null when absent (Map.of throws on a null value), to
        // match ocr-worker's own build_ocr_requested_payload contract
        // exactly (docs/events/ocr-scanning-events.md).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("event_type", EVENT_TYPE_OCR_REQUESTED);
        payload.put("tenant_id", tenantId.toString());
        payload.put("document_id", documentId.toString());
        payload.put("version_id", versionId.toString());
        payload.put("correlation_id", correlationId != null ? correlationId.toString() : null);
        payload.put("occurred_at", OffsetDateTime.now().toString());
        outboxEventPublisher.publish(tenantId, AGGREGATE_TYPE_DOCUMENT_VERSION, versionId, EVENT_TYPE_OCR_REQUESTED, payload);
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

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Real gap found live: the MinIO object key previously embedded
     * file.getOriginalFilename() completely raw, with no stripping of
     * path separators or control characters -- a filename containing
     * e.g. "../" would flow straight into the key. Used ONLY when
     * building storageObjectKey; document_versions.original_filename
     * keeps storing the raw, unmodified name.
     */
    static String sanitizeForStorageKey(String filename) {
        String normalized = Normalizer.normalize(filename, Normalizer.Form.NFC);
        String stripped = UNSAFE_STORAGE_KEY_CHARS.matcher(normalized).replaceAll("_");
        return stripped.isBlank() ? "upload" : stripped;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewQueueItemResponse> getReviewQueue(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        Page<DocumentVersion> versions = documentVersionRepository.findReviewQueue(tenantId, pageable);
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
            // enforceMandatory=false: a reviewer fixing three of four
            // missing fields must be able to save the three. The document
            // stays in the queue for the fourth, which is the honest
            // outcome -- refusing the save would lose their work instead.
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
