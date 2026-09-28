package com.oak.dms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.common.exception.UpstreamServiceException;
import com.oak.dms.document.dto.DocumentVersionResponse;
import com.oak.dms.document.dto.OcrFieldResultResponse;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import com.oak.dms.metadata.DocumentType;
import com.oak.dms.metadata.DocumentTypeFieldService;
import com.oak.dms.metadata.DocumentTypeRepository;
import com.oak.dms.metadata.MetadataValidationService;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    /**
     * The one fixed row V39__seed_platform_default_storage_location.sql
     * seeds -- see that migration's own comment and DocumentVersion
     * #storageLocationId for why this exists: document_versions.storage_
     * location_id is a real NOT NULL column (from the pluggable-storage
     * feature's V32, merged in only to fix a migration-numbering
     * collision), but this branch has no pluggable-storage abstraction of
     * its own and never reads this value back. Every version this branch
     * creates points at the same row unconditionally.
     */
    private static final UUID PLATFORM_DEFAULT_STORAGE_LOCATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    private final ObjectStorageService objectStorageService;
    private final OcrWorkerClient ocrWorkerClient;
    private final DocumentMetadataService documentMetadataService;
    private final MetadataValidationService metadataValidationService;
    private final ObjectMapper objectMapper;

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
                .formatted(tenantId, documentId, versionId, originalFilename);

        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        objectStorageService.putSourceObject(storageObjectKey, content, file.getContentType());

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
        // ocr_status starts at 'processing', not 'pending': OCR now always
        // auto-runs on upload (triggerOcrAsync, fired by the controller
        // right after this transaction commits) rather than waiting for a
        // separate manual "Run OCR" action.
        DocumentVersion version = DocumentVersion.builder()
                .versionId(versionId)
                .documentId(documentId)
                .tenantId(tenantId)
                .versionNumber(1)
                .majorVersion(1)
                .minorVersion(0)
                .storageLocationId(PLATFORM_DEFAULT_STORAGE_LOCATION_ID)
                .storageObjectKey(storageObjectKey)
                .originalFilename(originalFilename)
                .mimeType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                .fileHash(sha256Hex(content))
                .sizeBytes(content.length)
                .createdReason("initial_upload")
                .scanStatus("clean")
                .ocrStatus("processing")
                .requestedFields(writeJson(effectiveRequestedFields))
                .createdBy(userId)
                .build();
        documentVersionRepository.save(version);

        if (!values.isEmpty()) {
            // Already validated above; no need to re-validate here.
            documentMetadataService.saveValues(documentId, values, false);
        }

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
        version.setOcrStatus("processing");
        documentVersionRepository.save(version);

        return new OcrRerunResponse(versionId, version.getOcrStatus(), effectiveList);
    }

    // Runs on a separate thread from Spring's async executor -- MUST be
    // called only after whatever transaction created/updated this version
    // has already committed (see DocumentService#triggerOcrAsync's own doc
    // comment). tenantId/userId are explicit parameters, not read from
    // TenantContext, because that ThreadLocal does not propagate onto this
    // thread.
    @Override
    @Async
    public void triggerOcrAsync(UUID versionId, List<String> requestedFields, UUID tenantId, UUID userId) {
        TenantContext.set(tenantId, userId);
        try {
            Map<String, Object> result;
            try {
                // ocr-worker needs each requested field's dataType/options
                // (it has no catalog access of its own -- dms-core owns the
                // field catalog), not just the bare field_key strings this
                // method receives; resolve them against the document's
                // schema before calling out.
                DocumentVersion version = documentVersionRepository.findById(versionId).orElse(null);
                List<DocumentTypeSchemaResponse.SchemaField> requestedFieldSpecs = List.of();
                if (version != null) {
                    Document versionDocument = documentRepository.findById(version.getDocumentId()).orElse(null);
                    if (versionDocument != null) {
                        DocumentTypeSchemaResponse versionSchema = documentTypeFieldService.getSchema(versionDocument.getDocumentTypeId());
                        Set<String> requestedSet = Set.copyOf(requestedFields != null ? requestedFields : List.of());
                        requestedFieldSpecs = versionSchema.fields().stream()
                                .filter(f -> requestedSet.contains(f.fieldKey()))
                                .toList();
                    }
                }
                result = ocrWorkerClient.processVersion(versionId, requestedFieldSpecs);
            } catch (UpstreamServiceException e) {
                // ocr-worker's own pipeline owns writing ocr_status on every
                // path it actually reaches (see DocumentVersion.ocrStatus's
                // doc comment) -- but a network-level failure here means it
                // never got the request at all, so nobody else will ever
                // flip this version out of 'processing'. This is the one
                // case dms-core writes that column itself.
                log.warn("OCR request failed for version {}: {}", versionId, e.getMessage());
                markVersionFailed(versionId, tenantId);
                return;
            }

            if (result != null && result.get("fields") instanceof Map<?, ?> rawFields) {
                try {
                    UUID documentId = documentVersionRepository.findById(versionId)
                            .map(DocumentVersion::getDocumentId)
                            .orElse(null);
                    if (documentId != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> fields = (Map<String, Object>) rawFields;
                        documentMetadataService.applyOcrFields(documentId, fields);
                    }
                } catch (Exception e) {
                    log.warn("Could not map OCR fields onto version {} metadata: {}", versionId, e.getMessage());
                }
            }
        } finally {
            TenantContext.clear();
        }
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
                    row.getStatus(),
                    row.getMatchBasis()));
        }

        String collapsedStatus = collapseOcrStatus(version.getOcrStatus());
        if ("completed".equals(collapsedStatus)) {
            // applyOcrFields normally runs as a side effect of triggerOcrAsync
            // receiving ocr-worker's HTTP response synchronously -- but
            // ocr-worker's pipeline isn't cancelled by a client-side
            // timeout (see triggerOcrAsync's own UpstreamServiceException
            // catch), so a slow cold-start run (e.g. downloading PaddleOCR/
            // Docling models for the first time) can finish and write
            // document_ocr_field_results + ocr_status='completed' well
            // after dms-core's RestTemplate already gave up and marked the
            // version 'failed', with nothing left to trigger the mapping.
            // Verified live: a cold-start run that outlived the 60s read
            // timeout left real extracted values sitting in
            // document_ocr_field_results (and a correct confidence badge
            // client-side) while document_metadata_values -- and therefore
            // the actual editable field -- stayed empty forever. Re-running
            // the (idempotent, override-safe) mapping here, from whatever
            // document_ocr_field_results currently holds, closes that gap
            // regardless of whether the original HTTP call ever completed.
            try {
                Map<String, Object> ocrFieldsRaw = new LinkedHashMap<>();
                for (DocumentOcrFieldResult row : rows) {
                    if (row.getValue() == null || "not_found".equals(row.getStatus())) {
                        continue;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("value", row.getValue());
                    if (row.getConfidence() != null) {
                        entry.put("confidence", row.getConfidence());
                    }
                    // Without this, every poll's re-sync silently clears
                    // needs_review back to false (applyOcrFields only flags
                    // low_confidence when it sees "status" in the map) --
                    // document_ocr_field_results already has it, this was
                    // just never copied across before DocumentMetadataValue
                    // #needsReview existed for anything to read it into.
                    entry.put("status", row.getStatus());
                    ocrFieldsRaw.put(row.getFieldKey(), entry);
                }
                if (!ocrFieldsRaw.isEmpty()) {
                    documentMetadataService.applyOcrFields(documentId, ocrFieldsRaw);
                }
            } catch (Exception e) {
                log.warn("Could not map OCR fields onto document {} metadata during status poll: {}", documentId, e.getMessage());
            }
        }

        return new OcrStatusResponse(documentId, collapsedStatus, fields);
    }

    /** Collapses every real {@code document_versions.ocr_status} value down to the three states the polling contract exposes. */
    private static String collapseOcrStatus(String ocrStatus) {
        return switch (ocrStatus) {
            case "completed", "not_required" -> "completed";
            case "failed", "unsupported" -> "failed";
            default -> "processing"; // pending, processing
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

    private void markVersionFailed(UUID versionId, UUID tenantId) {
        documentVersionRepository.findById(versionId).ifPresent(v -> {
            if (v.getTenantId().equals(tenantId)) {
                v.setOcrStatus("failed");
                documentVersionRepository.save(v);
            }
        });
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
}
