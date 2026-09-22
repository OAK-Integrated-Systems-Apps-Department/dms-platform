package com.oak.dms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.DocumentTypeFieldService;
import com.oak.dms.metadata.MetadataValidationService;
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

/** See {@link DocumentMetadataService}. */
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

    private final DocumentRepository documentRepository;
    private final DocumentMetadataValueRepository metadataValueRepository;
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

        Map<UUID, List<DocumentMetadataValue>> rowsByField = metadataValueRepository
                .findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId).stream()
                .filter(row -> fieldsById.containsKey(row.getFieldId()))
                .collect(Collectors.groupingBy(DocumentMetadataValue::getFieldId));

        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : rowsByField.entrySet()) {
            DocumentTypeSchemaResponse.SchemaField field = fieldsById.get(entry.getKey());
            List<String> values = entry.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(DocumentMetadataValue::getValueOrdinal))
                    .map(row -> displayValue(row, field.dataType()))
                    .toList();
            result.put(field.fieldKey(), "multi_select".equals(field.dataType()) ? values : values.get(0));
        }
        return result;
    }

    @Override
    @Transactional
    public void saveValues(UUID documentId, Map<String, Object> valuesByFieldKey, boolean enforceMandatory) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = requireDocument(documentId, tenantId);
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(document.getDocumentTypeId());
        writeValues(document, tenantId, schema, valuesByFieldKey, "manual", null, enforceMandatory);
    }

    // REQUIRES_NEW, not the default REQUIRED: DocumentServiceImpl#runOcr (itself
    // @Transactional) calls this and deliberately catches any failure so a bad
    // OCR-mapping never breaks an otherwise-successful OCR response. But
    // catching a RuntimeException from a call into a *participating*
    // @Transactional method doesn't undo Spring's markRollbackOnly on the
    // shared transaction -- the caller's catch would still hit
    // UnexpectedRollbackException at commit. A separate physical transaction
    // means a failure/rollback in here is fully contained.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyOcrFields(UUID documentId, Map<String, Object> ocrFieldsRaw) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = requireDocument(documentId, tenantId);
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(document.getDocumentTypeId());

        // "manual" is the only source a re-run must never clobber -- an
        // earlier OCR-sourced value (or no value at all) is fair game for a
        // fresh OCR result to replace, which is what makes re-running OCR
        // for a field actually update anything after the first pass. Only
        // one row's source needs checking per field: every row for one
        // field_id shares the same source (writeValues always replaces the
        // full set together), so the first one found is representative.
        Map<UUID, String> sourceByFieldId = metadataValueRepository
                .findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId).stream()
                .collect(Collectors.toMap(DocumentMetadataValue::getFieldId, DocumentMetadataValue::getSource, (a, b) -> a));

        // ocr-worker now only ever returns fields it was actually asked to
        // extract (selectable-OCR-field-extraction) -- field.ocrExtractable()
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
            if (field == null || "manual".equals(sourceByFieldId.get(field.fieldId()))) {
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> ocrField && ocrField.get("value") instanceof String value && !value.isBlank()) {
                // ocr-worker JSON-encodes a multi_select field's value to a
                // plain string (see extraction.py's
                // requested_fields_to_output_dict) so it still fits this
                // sparse map's `{value, confidence, bbox}` shape -- decode it
                // back into a real List<String> here so writeValues'
                // toStringList sees a Collection (one metadata row per
                // selected value) instead of one row containing the raw
                // "[\"a\",\"b\"]" text.
                Object decodedValue = value;
                if ("multi_select".equals(field.dataType())) {
                    try {
                        decodedValue = objectMapper.readValue(value, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
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
            writeValues(document, tenantId, schema, toApply, "ocr", confidenceByFieldKey, false);
        }
    }

    private void writeValues(Document document, UUID tenantId, DocumentTypeSchemaResponse schema,
                              Map<String, Object> valuesByFieldKey, String source,
                              Map<String, BigDecimal> confidenceByFieldKey, boolean enforceMandatory) {
        if (enforceMandatory) {
            List<ValidationError> errors = metadataValidationService.validate(document.getDocumentTypeId(), null, valuesByFieldKey);
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
        // throw away the other fields OCR got right, so rows are built
        // (and validated) BEFORE the old value is deleted, and a parse
        // failure here is skipped rather than propagated -- unlike a manual
        // save, where the same failure should still reject the whole call
        // so the user gets clear feedback.
        boolean bestEffort = "ocr".equals(source);

        for (var entry : valuesByFieldKey.entrySet()) {
            DocumentTypeSchemaResponse.SchemaField field = fieldsByKey.get(entry.getKey());
            if (field == null) {
                continue; // not part of this document type's current schema -- ignore, don't reject
            }
            List<String> rawValues = toStringList(entry.getValue());
            if (rawValues.isEmpty()) {
                // A blank value explicitly clears the field -- always applied,
                // even for an OCR batch, since it's not something that could
                // have failed to parse.
                metadataValueRepository.deleteByDocumentIdAndFieldIdAndTenantId(document.getDocumentId(), field.fieldId(), tenantId);
                continue;
            }
            BigDecimal confidence = confidenceByFieldKey != null ? confidenceByFieldKey.get(field.fieldKey()) : null;

            List<DocumentMetadataValue> rows = new ArrayList<>();
            try {
                for (int ordinal = 0; ordinal < rawValues.size(); ordinal++) {
                    rows.add(buildRow(tenantId, document.getDocumentId(), field, rawValues.get(ordinal), ordinal, source, confidence));
                }
            } catch (MetadataValidationFailedException e) {
                if (bestEffort) {
                    log.warn("Skipping OCR value for field '{}' on document {}: {}", field.fieldKey(), document.getDocumentId(), e.getMessage());
                    continue;
                }
                throw e;
            }

            metadataValueRepository.deleteByDocumentIdAndFieldIdAndTenantId(document.getDocumentId(), field.fieldId(), tenantId);
            rows.forEach(metadataValueRepository::save);
        }
    }

    private DocumentMetadataValue buildRow(UUID tenantId, UUID documentId, DocumentTypeSchemaResponse.SchemaField field,
                                            String rawValue, int ordinal, String source, BigDecimal confidence) {
        DocumentMetadataValue.DocumentMetadataValueBuilder row = DocumentMetadataValue.builder()
                .tenantId(tenantId)
                .documentId(documentId)
                .fieldId(field.fieldId())
                .valueOrdinal(ordinal)
                .source(source)
                .confidenceScore(confidence);

        switch (field.dataType()) {
            case "number", "decimal" -> row.valueNumber(parseNumber(field, rawValue));
            case "date", "datetime" -> row.valueDate(parseDate(field, rawValue));
            case "boolean" -> row.valueBoolean(Boolean.parseBoolean(rawValue));
            // user_ref/lookup: stored as text -- no picker UI yet to resolve a
            // real value_ref_id (see DocumentMetadataValue's own javadoc).
            default -> row.valueText(rawValue);
        }
        return row.build();
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
     * {@code row.getValueDate()}'s raw {@code OffsetDateTime#toString()} (e.g.
     * {@code "2026-05-01T00:00Z"}) doesn't match what an {@code <input type="date">}
     * (wants {@code "yyyy-MM-dd"}) or {@code type="datetime-local"} (wants
     * {@code "yyyy-MM-ddTHH:mm"}) will parse, so it renders as blank on the
     * frontend even though the value round-tripped correctly through the DB.
     */
    private static String displayValue(DocumentMetadataValue row, String dataType) {
        if (row.getValueText() != null) return row.getValueText();
        if (row.getValueNumber() != null) return row.getValueNumber().toPlainString();
        if (row.getValueDate() != null) {
            return "datetime".equals(dataType) ? row.getValueDate().toLocalDateTime().toString() : row.getValueDate().toLocalDate().toString();
        }
        if (row.getValueBoolean() != null) return row.getValueBoolean().toString();
        if (row.getValueRefId() != null) return row.getValueRefId().toString();
        return "";
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
