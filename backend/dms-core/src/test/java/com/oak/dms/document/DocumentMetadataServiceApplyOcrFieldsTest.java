package com.oak.dms.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.metadata.DocumentTypeFieldService;
import com.oak.dms.metadata.MetadataValidationService;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link DocumentMetadataServiceImpl#applyOcrFields}'s override
 * rule: a field whose current value has source "manual" (a real user
 * override) must never be clobbered by a fresh OCR result, while a field
 * currently sourced "ocr" (or unset) is fair game for a re-run to update --
 * this is what makes "re-run OCR for one field" (requirement 5) actually
 * change anything after the first pass, without ever touching a value the
 * user typed in themselves.
 */
@ExtendWith(MockitoExtension.class)
class DocumentMetadataServiceApplyOcrFieldsTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentMetadataValueRepository metadataValueRepository;
    @Mock
    private DocumentTypeFieldService documentTypeFieldService;
    @Mock
    private MetadataValidationService metadataValidationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();
    private final UUID authorFieldId = UUID.randomUUID();
    private final UUID referenceFieldId = UUID.randomUUID();

    @BeforeEach
    void bindTenantContext() {
        TenantContext.set(tenantId, UUID.randomUUID());
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private DocumentTypeSchemaResponse.SchemaField schemaField(UUID fieldId, String key) {
        return schemaField(fieldId, key, "text");
    }

    private DocumentTypeSchemaResponse.SchemaField schemaField(UUID fieldId, String key, String dataType) {
        return new DocumentTypeSchemaResponse.SchemaField(
                fieldId, key, key, null, dataType, "active",
                false, false, true, 0, null, null, List.of(), List.of());
    }

    @Test
    void manualOverrideIsNeverOverwrittenByAFreshOcrResult() {
        Document document = Document.builder()
                .documentId(documentId).tenantId(tenantId).documentTypeId(documentTypeId)
                .title("doc").latestVersionId(UUID.randomUUID()).ownerUserId(UUID.randomUUID())
                .build();
        when(documentRepository.findById(documentId)).thenReturn(java.util.Optional.of(document));

        DocumentTypeSchemaResponse schema = new DocumentTypeSchemaResponse(
                documentTypeId, "GENERAL", "General", 1,
                List.of(schemaField(authorFieldId, "author"), schemaField(referenceFieldId, "reference_number")));
        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(schema);

        DocumentMetadataValue manualAuthorValue = DocumentMetadataValue.builder()
                .tenantId(tenantId).documentId(documentId).fieldId(authorFieldId)
                .valueText("The Real Author").source("manual").build();
        DocumentMetadataValue ocrReferenceValue = DocumentMetadataValue.builder()
                .tenantId(tenantId).documentId(documentId).fieldId(referenceFieldId)
                .valueText("OLD-REF-000").source("ocr").build();
        when(metadataValueRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(List.of(manualAuthorValue, ocrReferenceValue));

        DocumentMetadataServiceImpl service = new DocumentMetadataServiceImpl(
                documentRepository, metadataValueRepository, documentTypeFieldService, metadataValidationService, objectMapper);

        Map<String, Object> ocrFields = Map.of(
                "author", Map.of("value", "OCR-Detected Author", "confidence", 0.9),
                "reference_number", Map.of("value", "NEW-REF-123", "confidence", 0.95));

        service.applyOcrFields(documentId, ocrFields);

        // "author" has a manual override -- must be left completely alone.
        verify(metadataValueRepository, never())
                .deleteByDocumentIdAndFieldIdAndTenantId(eq(documentId), eq(authorFieldId), eq(tenantId));

        // "reference_number" was only ever OCR-sourced -- a fresh OCR
        // result must replace it.
        verify(metadataValueRepository, times(1))
                .deleteByDocumentIdAndFieldIdAndTenantId(eq(documentId), eq(referenceFieldId), eq(tenantId));
        verify(metadataValueRepository, times(1)).save(argThatFieldIdIs(referenceFieldId));
        verify(metadataValueRepository, never()).save(argThatFieldIdIs(authorFieldId));
    }

    @Test
    void anUnsetFieldIsFilledInNormally() {
        Document document = Document.builder()
                .documentId(documentId).tenantId(tenantId).documentTypeId(documentTypeId)
                .title("doc").latestVersionId(UUID.randomUUID()).ownerUserId(UUID.randomUUID())
                .build();
        when(documentRepository.findById(documentId)).thenReturn(java.util.Optional.of(document));

        DocumentTypeSchemaResponse schema = new DocumentTypeSchemaResponse(
                documentTypeId, "GENERAL", "General", 1, List.of(schemaField(referenceFieldId, "reference_number")));
        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(schema);
        when(metadataValueRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(List.of());

        DocumentMetadataServiceImpl service = new DocumentMetadataServiceImpl(
                documentRepository, metadataValueRepository, documentTypeFieldService, metadataValidationService, objectMapper);

        service.applyOcrFields(documentId, Map.of("reference_number", Map.of("value", "REF-1", "confidence", 0.8)));

        verify(metadataValueRepository, times(1)).save(argThatFieldIdIs(referenceFieldId));
    }

    /**
     * Regression test: a real invoice's OCR'd "Grand Total" came back as
     * "2,479,475.00" (the thousands-separator formatting actually printed
     * on the document) -- BigDecimal rejects a comma outright, so this
     * best-effort OCR mapping was silently DROPPING an otherwise-correct,
     * mandatory "amount" value every time, leaving it permanently
     * unfillable except by the user retyping it. The comma must be
     * stripped before parsing, not just for OCR values but for manual
     * entry too (a user typing "1,234.56" shouldn't be rejected either).
     */
    @Test
    void decimalFieldWithThousandsSeparatorCommaIsAcceptedNotSkipped() {
        UUID amountFieldId = UUID.randomUUID();
        Document document = Document.builder()
                .documentId(documentId).tenantId(tenantId).documentTypeId(documentTypeId)
                .title("doc").latestVersionId(UUID.randomUUID()).ownerUserId(UUID.randomUUID())
                .build();
        when(documentRepository.findById(documentId)).thenReturn(java.util.Optional.of(document));

        DocumentTypeSchemaResponse schema = new DocumentTypeSchemaResponse(
                documentTypeId, "INVOICE", "Invoice", 1, List.of(schemaField(amountFieldId, "amount", "decimal")));
        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(schema);
        when(metadataValueRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(List.of());

        DocumentMetadataServiceImpl service = new DocumentMetadataServiceImpl(
                documentRepository, metadataValueRepository, documentTypeFieldService, metadataValidationService, objectMapper);

        service.applyOcrFields(documentId, Map.of("amount", Map.of("value", "2,479,475.00", "confidence", 0.8)));

        verify(metadataValueRepository, times(1)).save(org.mockito.ArgumentMatchers.argThat(
                row -> amountFieldId.equals(row.getFieldId())
                        && row.getValueNumber() != null
                        && row.getValueNumber().compareTo(new java.math.BigDecimal("2479475.00")) == 0));
    }

    /**
     * Regression test: ocr-worker JSON-encodes a multi_select field's value
     * to a string (e.g. {@code "[\"policy\",\"hr\"]"}) so it fits this
     * sparse map's {@code {value, confidence, bbox}} shape (see
     * extraction.py's {@code requested_fields_to_output_dict}). Without
     * decoding that string back into a real {@code List<String>} before
     * handing it to {@code writeValues}, it would be stored as ONE row
     * containing the literal bracketed text instead of one row per tag.
     */
    @Test
    void multiSelectOcrValueIsDecodedIntoOneRowPerElement() {
        UUID tagsFieldId = UUID.randomUUID();
        Document document = Document.builder()
                .documentId(documentId).tenantId(tenantId).documentTypeId(documentTypeId)
                .title("doc").latestVersionId(UUID.randomUUID()).ownerUserId(UUID.randomUUID())
                .build();
        when(documentRepository.findById(documentId)).thenReturn(java.util.Optional.of(document));

        DocumentTypeSchemaResponse schema = new DocumentTypeSchemaResponse(
                documentTypeId, "GENERAL", "General", 1,
                List.of(schemaField(tagsFieldId, "tags", "multi_select")));
        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(schema);
        when(metadataValueRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(List.of());

        DocumentMetadataServiceImpl service = new DocumentMetadataServiceImpl(
                documentRepository, metadataValueRepository, documentTypeFieldService, metadataValidationService, objectMapper);

        service.applyOcrFields(documentId, Map.of(
                "tags", Map.of("value", "[\"policy\",\"hr\"]", "confidence", 0.7)));

        // One row per decoded element (ordinals 0 and 1), not one row
        // holding the raw "[\"policy\",\"hr\"]" text.
        verify(metadataValueRepository, times(2)).save(argThatFieldIdIs(tagsFieldId));
        verify(metadataValueRepository).save(org.mockito.ArgumentMatchers.argThat(
                row -> tagsFieldId.equals(row.getFieldId()) && row.getValueOrdinal() == 0 && "policy".equals(row.getValueText())));
        verify(metadataValueRepository).save(org.mockito.ArgumentMatchers.argThat(
                row -> tagsFieldId.equals(row.getFieldId()) && row.getValueOrdinal() == 1 && "hr".equals(row.getValueText())));
    }

    /**
     * The confidence gate: a "low_confidence" OCR result is still written
     * (not suppressed), but flagged via {@code needsReview} so the frontend
     * can distinguish it from a confident extraction instead of presenting
     * both identically -- see DocumentMetadataValue#needsReview and
     * extraction.py's requested_fields_to_output_dict (which is what
     * actually puts "status" in this map in production).
     */
    @Test
    void lowConfidenceOcrResultIsWrittenButFlaggedNeedsReview() {
        UUID descriptionFieldId = UUID.randomUUID();
        UUID referenceFieldIdForThisTest = UUID.randomUUID();
        Document document = Document.builder()
                .documentId(documentId).tenantId(tenantId).documentTypeId(documentTypeId)
                .title("doc").latestVersionId(UUID.randomUUID()).ownerUserId(UUID.randomUUID())
                .build();
        when(documentRepository.findById(documentId)).thenReturn(java.util.Optional.of(document));

        DocumentTypeSchemaResponse schema = new DocumentTypeSchemaResponse(
                documentTypeId, "GENERAL", "General", 1, List.of(
                        schemaField(descriptionFieldId, "description"),
                        schemaField(referenceFieldIdForThisTest, "reference_number")));
        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(schema);
        when(metadataValueRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(List.of());

        DocumentMetadataServiceImpl service = new DocumentMetadataServiceImpl(
                documentRepository, metadataValueRepository, documentTypeFieldService, metadataValidationService, objectMapper);

        service.applyOcrFields(documentId, Map.of(
                "description", Map.of("value", "auto-summarized guess", "confidence", 0.5, "status", "low_confidence"),
                "reference_number", Map.of("value", "REF-1", "confidence", 0.95, "status", "extracted")));

        verify(metadataValueRepository).save(org.mockito.ArgumentMatchers.argThat(
                row -> descriptionFieldId.equals(row.getFieldId()) && row.isNeedsReview()));
        verify(metadataValueRepository).save(org.mockito.ArgumentMatchers.argThat(
                row -> referenceFieldIdForThisTest.equals(row.getFieldId()) && !row.isNeedsReview()));
    }

    private static DocumentMetadataValue argThatFieldIdIs(UUID fieldId) {
        return org.mockito.ArgumentMatchers.argThat(row -> row != null && fieldId.equals(row.getFieldId()));
    }
}
