package com.oak.dms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadata.FieldValue;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * See {@link DocumentMetadataService}.
 *
 * <h2>Storage model</h2>
 * Values live in {@code document_metadata} - one row per document, with a
 * JSONB map of {@code fieldId -> }{@link FieldValue}{@code (v, src, conf)}.
 *
 * <p>This class used to write {@code document_metadata_values}, one row per
 * (document, field, ordinal). That table was dropped by V36, which replaced
 * it with the map above, and this service was ported onto it as part of the
 * watermarking/OCR merge. The port is not a compromise: {@code FieldValue}
 * already carries {@code src} and {@code conf}, which are exactly the two
 * things the OCR path needs beyond the value itself, and a multi-select is a
 * {@code List} in one place rather than N rows that have to be re-sorted by
 * ordinal on every read.
 *
 * <p>Everything else is kept deliberately: the alias map, "manual always
 * wins", the best-effort parse that skips one bad OCR value rather than
 * losing the batch, the comma-tolerant number parse, and the date formats the
 * frontend's inputs can actually read back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentMetadataServiceImpl implements DocumentMetadataService {

    /**
     * ocr-worker's own generic field names (invoice_number/invoice_date/
     * total_amount/id_number -- see backend/workers/ocr-worker/src/
     * ocr_worker/extraction.py) aren't tied to any tenant's metadata field
     * catalogue, so a couple of the clearest, most common mismatches get a
     * fixed alias. Deliberately small and explicit -- same "best-effort,
     * not a general NLP extractor" ethos extraction.py documents about
     * itself, not an attempt at fuzzy/semantic matching.
     */
    private static final Map<String, String> OCR_FIELD_ALIASES = Map.of(
            "total_amount", "amount",
            "id_number", "purchase_order_number");

    static final String SOURCE_MANUAL = "manual";
    static final String SOURCE_OCR = "ocr";

    private final DocumentRepository documentRepository;
    private final DocumentMetadataRepository documentMetadataRepository;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final MetadataValidationService metadataValidationService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getValues(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = requireDocument(documentId, tenantId);
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(document.getDocumentTypeId());

        Map<UUID, DocumentTypeSchemaResponse.SchemaField> fieldsById = schema.fields().stream()
                .collect(Collectors.toMap(DocumentTypeSchemaResponse.SchemaField::fieldId, f -> f));

        Map<String, FieldValue> stored = storedValues(tenantId, documentId);
        Map<String, Object> result = new LinkedHashMap<>();
        // Schema order, not map order: the form renders these in the order
        // the fields are configured, and a JSONB map has no order worth
        // relying on.
        for (DocumentTypeSchemaResponse.SchemaField field : schema.fields()) {
            FieldValue value = stored.get(field.fieldId().toString());
            if (value == null || value.v() == null) {
                continue;
            }
            Object display = displayValue(value.v(), field.dataType());
            if (display != null) {
                result.put(field.fieldKey(), display);
            }
        }
        // A value whose field is no longer on the type's schema is left out,
        // same as before - fieldsById is what decides, and the loop above
        // only walks fields that are in it.
        return result;
    }

    @Override
    @Transactional
    public void saveValues(UUID documentId, Map<String, Object> valuesByFieldKey, boolean enforceMandatory) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = requireDocument(documentId, tenantId);
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(document.getDocumentTypeId());
        writeValues(document, tenantId, schema, valuesByFieldKey, SOURCE_MANUAL, null, enforceMandatory);
    }

    // REQUIRES_NEW, not the default REQUIRED: callers (the OCR events consumer
    // and the reconciler) deliberately catch any failure so a bad OCR-mapping
    // never breaks an otherwise-successful OCR run. But catching a
    // RuntimeException from a call into a *participating* @Transactional
    // method doesn't undo Spring's markRollbackOnly on the shared transaction
    // -- the caller's catch would still hit UnexpectedRollbackException at
    // commit. A separate physical transaction means a failure/rollback in here
    // is fully contained.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyOcrFields(UUID documentId, Map<String, Object> ocrFieldsRaw) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = requireDocument(documentId, tenantId);
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(document.getDocumentTypeId());

        // "manual" is the only source a re-run must never clobber -- an
        // earlier OCR-sourced value (or no value at all) is fair game for a
        // fresh OCR result to replace, which is what makes re-running OCR
        // for a field actually update anything after the first pass.
        Map<String, FieldValue> stored = storedValues(tenantId, documentId);

        // ocr-worker now only ever returns fields it was actually asked to
        // extract (selectable OCR field extraction) -- field.ocrExtractable()
        // is no longer the right gate here; iterate directly over whatever
        // keys ocr-worker's response actually contains.
        Map<String, DocumentTypeSchemaResponse.SchemaField> fieldsByKey = schema.fields().stream()
                .collect(Collectors.toMap(DocumentTypeSchemaResponse.SchemaField::fieldKey, f -> f));

        Map<String, Object> toApply = new LinkedHashMap<>();
        Map<String, BigDecimal> confidenceByFieldKey = new LinkedHashMap<>();
        for (var entry : ocrFieldsRaw.entrySet()) {
            String fieldKey = entry.getKey();
            DocumentTypeSchemaResponse.SchemaField field = fieldsByKey.get(fieldKey);
            if (field == null) {
                field = fieldsByKey.get(OCR_FIELD_ALIASES.get(fieldKey));
            }
            if (field == null) {
                continue;
            }
            FieldValue existing = stored.get(field.fieldId().toString());
            if (existing != null && SOURCE_MANUAL.equals(existing.src())) {
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> ocrField && ocrField.get("value") instanceof String value && !value.isBlank()) {
                // ocr-worker JSON-encodes a multi_select field's value to a
                // plain string (see extraction.py's
                // requested_fields_to_output_dict) so it still fits this
                // sparse map's `{value, confidence, bbox}` shape -- decode it
                // back into a real List<String> here so writeValues sees a
                // Collection and stores a list, instead of one value
                // containing the raw "[\"a\",\"b\"]" text.
                Object decodedValue = value;
                if ("multi_select".equals(field.dataType())) {
                    try {
                        decodedValue = objectMapper.readValue(value,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    } catch (JsonProcessingException e) {
                        log.warn("Could not decode multi_select OCR value for field '{}' on document {}: {}",
                                field.fieldKey(), document.getDocumentId(), e.getMessage());
                        continue;
                    }
                }
                toApply.put(field.fieldKey(), decodedValue);
                if (ocrField.get("confidence") instanceof Number confidence) {
                    confidenceByFieldKey.put(field.fieldKey(), BigDecimal.valueOf(confidence.doubleValue()));
                }
            }
        }
        if (!toApply.isEmpty()) {
            writeValues(document, tenantId, schema, toApply, SOURCE_OCR, confidenceByFieldKey, false);
        }
    }

    private void writeValues(Document document, UUID tenantId, DocumentTypeSchemaResponse schema,
                             Map<String, Object> valuesByFieldKey, String source,
                             Map<String, BigDecimal> confidenceByFieldKey, boolean enforceMandatory) {
        if (enforceMandatory) {
            List<ValidationError> errors = metadataValidationService
                    .validate(document.getDocumentTypeId(), null, valuesByFieldKey);
            List<ValidationError> blocking = errors.stream().filter(e -> "error".equals(e.severity())).toList();
            if (!blocking.isEmpty()) {
                throw new MetadataValidationFailedException(blocking);
            }
        }

        Map<String, DocumentTypeSchemaResponse.SchemaField> fieldsByKey = schema.fields().stream()
                .collect(Collectors.toMap(DocumentTypeSchemaResponse.SchemaField::fieldKey, f -> f));

        // OCR-sourced values are a best-effort batch: ocr-worker's regexes
        // (see extraction.py's _DATE_RE) can hand back a date/number format
        // this schema's field type can't parse. One bad field must not
        // throw away the other fields OCR got right, so each value is parsed
        // on its own and a parse failure is skipped rather than propagated --
        // unlike a manual save, where the same failure should still reject
        // the whole call so the user gets clear feedback.
        boolean bestEffort = SOURCE_OCR.equals(source);

        Map<String, FieldValue> merged = new LinkedHashMap<>(storedValues(tenantId, document.getDocumentId()));
        boolean changed = false;

        for (var entry : valuesByFieldKey.entrySet()) {
            DocumentTypeSchemaResponse.SchemaField field = fieldsByKey.get(entry.getKey());
            if (field == null) {
                continue; // not part of this document type's current schema -- ignore, don't reject
            }
            String key = field.fieldId().toString();
            List<String> rawValues = toStringList(entry.getValue());
            if (rawValues.isEmpty()) {
                // A blank value explicitly clears the field -- always applied,
                // even for an OCR batch, since it's not something that could
                // have failed to parse.
                changed |= merged.remove(key) != null;
                continue;
            }
            Object typed;
            try {
                typed = "multi_select".equals(field.dataType())
                        ? rawValues.stream().map(raw -> typedValue(field, raw)).toList()
                        : typedValue(field, rawValues.get(0));
            } catch (MetadataValidationFailedException e) {
                if (bestEffort) {
                    log.warn("Skipping OCR value for field '{}' on document {}: {}",
                            field.fieldKey(), document.getDocumentId(), e.getMessage());
                    continue;
                }
                throw e;
            }
            BigDecimal confidence = confidenceByFieldKey != null ? confidenceByFieldKey.get(field.fieldKey()) : null;
            merged.put(key, new FieldValue(typed, source, confidence));
            changed = true;
        }

        if (changed) {
            persist(document, tenantId, schema, merged);
        }
    }

    /**
     * Writes the whole map back, creating the row if this document has no
     * metadata yet.
     *
     * <p>{@code documentTypeId} and {@code schemaVersion} are refreshed on
     * every write, matching what {@code ImportRecordCreator#writeMetadata}
     * does: the row records which schema the values were saved against, and a
     * stale version there is worse than no version - it would make a later
     * reader resolve the field ids against the wrong field set.
     */
    private void persist(Document document, UUID tenantId, DocumentTypeSchemaResponse schema,
                         Map<String, FieldValue> values) {
        DocumentMetadataId id = new DocumentMetadataId(tenantId, document.getDocumentId());
        UUID userId = TenantContext.getUserId();
        DocumentMetadata metadata = documentMetadataRepository.findById(id)
                .map(existing -> {
                    existing.setFieldValues(new java.util.HashMap<>(values));
                    existing.setDocumentTypeId(document.getDocumentTypeId());
                    existing.setSchemaVersion(schema.schemaVersion());
                    existing.setUpdatedBy(userId);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    return existing;
                })
                .orElseGet(() -> DocumentMetadata.builder()
                        .id(id)
                        .documentTypeId(document.getDocumentTypeId())
                        .schemaVersion(schema.schemaVersion())
                        .fieldValues(new java.util.HashMap<>(values))
                        .createdBy(userId)
                        .build());
        documentMetadataRepository.save(metadata);
    }

    private Map<String, FieldValue> storedValues(UUID tenantId, UUID documentId) {
        return documentMetadataRepository.findById(new DocumentMetadataId(tenantId, documentId))
                .map(DocumentMetadata::getFieldValues)
                .orElseGet(Map::of);
    }

    /**
     * One raw string as the type the JSON column should hold: a
     * {@link BigDecimal} for numbers, an ISO-8601 string for dates, a
     * {@link Boolean} for booleans, the string itself otherwise.
     *
     * <p>Not the raw string for everything, even though JSONB would accept
     * it: a decimal stored as text sorts and compares as text, and
     * {@code "9"} &gt; {@code "10"} is the kind of bug that surfaces as a
     * wrong search result months later.
     */
    private Object typedValue(DocumentTypeSchemaResponse.SchemaField field, String rawValue) {
        return switch (field.dataType()) {
            case "number", "decimal" -> parseNumber(field, rawValue);
            // ISO-8601 text rather than a temporal object: the JSON mapper has
            // no type information to read it back with, so the string IS the
            // storage format. displayValue trims it to what the form's input
            // can parse.
            case "date", "datetime" -> parseDate(field, rawValue).toString();
            case "boolean" -> Boolean.parseBoolean(rawValue);
            // user_ref/lookup: stored as text -- no picker UI yet to resolve
            // a real reference id.
            default -> rawValue;
        };
    }

    private BigDecimal parseNumber(DocumentTypeSchemaResponse.SchemaField field, String rawValue) {
        // OCR-sourced decimal values carry whatever thousands-separator
        // formatting is actually printed on the document (e.g. a real
        // invoice's Grand Total came back as "2,479,475.00") -- verified
        // live: BigDecimal rejects the comma outright, so this best-effort
        // mapping was silently dropping an otherwise-correct, mandatory
        // "amount" value every time. Stripping only the comma (never a
        // currency symbol or anything else) is also a reasonable allowance
        // for manual entry -- a user typing "1,234.56" shouldn't be
        // rejected either -- without risking turning a genuinely malformed
        // value into a wrong-but-parseable one.
        String normalized = rawValue.replace(",", "");
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new MetadataValidationFailedException(List.of(new ValidationError(
                    field.fieldKey(), "INVALID_NUMBER", "Field '" + field.label() + "' must be a number.", "error")));
        }
    }

    private OffsetDateTime parseDate(DocumentTypeSchemaResponse.SchemaField field, String rawValue) {
        try {
            return OffsetDateTime.parse(rawValue);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(rawValue).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw new MetadataValidationFailedException(List.of(new ValidationError(
                        field.fieldKey(), "INVALID_DATE", "Field '" + field.label() + "' must be a valid date.", "error")));
            }
        }
    }

    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        String s = String.valueOf(value);
        return s.isBlank() ? List.of() : List.of(s);
    }

    /**
     * The stored value as the frontend's input will parse it back.
     *
     * <p>A stored date is full ISO-8601 (e.g. {@code "2026-05-01T00:00Z"}),
     * which an {@code <input type="date">} (wants {@code yyyy-MM-dd}) and
     * {@code type="datetime-local"} (wants {@code yyyy-MM-ddTHH:mm}) both
     * render as blank. Trimming it here is what makes a value that
     * round-tripped correctly through the database also round-trip through
     * the form.
     *
     * <p>A multi_select stays a {@code List<String>}; everything else becomes
     * a single string, which is the shape {@link #getValues} promises.
     */
    private static Object displayValue(Object stored, String dataType) {
        if (stored instanceof Collection<?> collection) {
            return collection.stream().map(v -> String.valueOf(displayScalar(v, dataType))).toList();
        }
        return displayScalar(stored, dataType);
    }

    private static Object displayScalar(Object stored, String dataType) {
        if (stored == null) {
            return null;
        }
        if (stored instanceof BigDecimal number) {
            return number.toPlainString();
        }
        if ("date".equals(dataType) || "datetime".equals(dataType)) {
            try {
                OffsetDateTime parsed = OffsetDateTime.parse(String.valueOf(stored));
                return "datetime".equals(dataType)
                        ? parsed.toLocalDateTime().toString()
                        : parsed.toLocalDate().toString();
            } catch (DateTimeParseException e) {
                // Stored by something that didn't go through typedValue, or
                // by an older write. Shown as-is rather than hidden: a value
                // the form cannot parse is still better than a blank field
                // that looks like no value was ever saved.
                return String.valueOf(stored);
            }
        }
        return String.valueOf(stored);
    }

    private Document requireDocument(UUID documentId, UUID tenantId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("documents", documentId));
        if (!document.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("documents", documentId);
        }
        return document;
    }
}
