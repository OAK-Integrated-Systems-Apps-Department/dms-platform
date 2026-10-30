package com.oak.dms.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadata.FieldValue;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link DocumentMetadataServiceImpl#applyOcrFields}'s override
 * rule: a field whose current value has source "manual" (a real user
 * override) must never be clobbered by a fresh OCR result, while a field
 * currently sourced "ocr" (or unset) is fair game for a re-run to update --
 * this is what makes "re-run OCR for one field" actually change anything
 * after the first pass, without ever touching a value the user typed in
 * themselves.
 *
 * <p>Storage is {@code document_metadata}'s {@code field_values} JSON map
 * (V36 dropped the one-row-per-value table these assertions used to read), so
 * every test here captures the single saved {@link DocumentMetadata} and
 * inspects the map rather than counting repository calls. That is a better
 * test of the same behaviour: it asserts what ends up stored, not how many
 * writes it took to get there.
 */
@ExtendWith(MockitoExtension.class)
class DocumentMetadataServiceApplyOcrFieldsTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentMetadataRepository documentMetadataRepository;
    @Mock
    private DocumentTypeFieldService documentTypeFieldService;
    @Mock
    private MetadataValidationService metadataValidationService;
    @Mock
    private com.oak.dms.signature.service.DocumentSignatureLockService signatureLockService;

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

    private DocumentMetadataServiceImpl service() {
        return new DocumentMetadataServiceImpl(documentRepository, documentMetadataRepository,
                documentTypeFieldService, metadataValidationService, objectMapper, signatureLockService);
    }

    /** The document, schema and (optionally) the metadata row already stored. */
    private void given(DocumentTypeSchemaResponse schema, Map<String, FieldValue> stored) {
        Document document = Document.builder()
                .documentId(documentId).tenantId(tenantId).documentTypeId(documentTypeId)
                .title("doc").latestVersionId(UUID.randomUUID()).ownerUserId(UUID.randomUUID())
                .build();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(schema);
        // The service reads the stored values under a row lock (lockedStoredValues ->
        // findByIdForUpdate) so a manual save racing an OCR result cannot be overwritten.
        when(documentMetadataRepository.findByIdForUpdate(new DocumentMetadataId(tenantId, documentId)))
                .thenReturn(stored == null ? Optional.empty() : Optional.of(DocumentMetadata.builder()
                        .id(new DocumentMetadataId(tenantId, documentId))
                        .documentTypeId(documentTypeId)
                        .schemaVersion(1)
                        .fieldValues(new HashMap<>(stored))
                        .build()));
    }

    /** The one row the service saved, or a failure if it saved none. */
    private Map<String, FieldValue> saved() {
        ArgumentCaptor<DocumentMetadata> captor = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentMetadataRepository).save(captor.capture());
        return captor.getValue().getFieldValues();
    }

    @Test
    void manualOverrideIsNeverOverwrittenByAFreshOcrResult() {
        given(new DocumentTypeSchemaResponse(documentTypeId, "GENERAL", "General", 1,
                        List.of(schemaField(authorFieldId, "author"),
                                schemaField(referenceFieldId, "reference_number"))),
                Map.of(
                        authorFieldId.toString(), new FieldValue("The Real Author", "manual", null),
                        referenceFieldId.toString(), new FieldValue("OLD-REF-000", "ocr", null)));

        service().applyOcrFields(documentId, Map.of(
                "author", Map.of("value", "OCR-Detected Author", "confidence", 0.9),
                "reference_number", Map.of("value", "NEW-REF-123", "confidence", 0.95)));

        Map<String, FieldValue> values = saved();
        // "author" has a manual override -- left exactly as it was, source included.
        assertThat(values.get(authorFieldId.toString()))
                .isEqualTo(new FieldValue("The Real Author", "manual", null));
        // "reference_number" was only ever OCR-sourced -- a fresh OCR result replaces it.
        assertThat(values.get(referenceFieldId.toString()).v()).isEqualTo("NEW-REF-123");
        assertThat(values.get(referenceFieldId.toString()).src()).isEqualTo("ocr");
        assertThat(values.get(referenceFieldId.toString()).conf())
                .isEqualByComparingTo(BigDecimal.valueOf(0.95));
    }

    @Test
    void anUnsetFieldIsFilledInNormally() {
        given(new DocumentTypeSchemaResponse(documentTypeId, "GENERAL", "General", 1,
                List.of(schemaField(referenceFieldId, "reference_number"))), null);

        service().applyOcrFields(documentId, Map.of(
                "reference_number", Map.of("value", "REF-1", "confidence", 0.8)));

        assertThat(saved().get(referenceFieldId.toString()).v()).isEqualTo("REF-1");
    }

    @Test
    void nothingIsWrittenWhenOcrReturnedNothingUsable() {
        given(new DocumentTypeSchemaResponse(documentTypeId, "GENERAL", "General", 1,
                List.of(schemaField(referenceFieldId, "reference_number"))), null);

        // A field this type does not have, and a blank value for one it does.
        service().applyOcrFields(documentId, Map.of(
                "not_a_field_here", Map.of("value", "x", "confidence", 0.9),
                "reference_number", Map.of("value", "   ", "confidence", 0.9)));

        verify(documentMetadataRepository, never()).save(org.mockito.ArgumentMatchers.any());
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
        given(new DocumentTypeSchemaResponse(documentTypeId, "INVOICE", "Invoice", 1,
                List.of(schemaField(amountFieldId, "amount", "decimal"))), null);

        service().applyOcrFields(documentId, Map.of(
                "amount", Map.of("value", "2,479,475.00", "confidence", 0.8)));

        // Stored as a real number, not text: a decimal kept as a string
        // sorts and compares as a string, and "9" > "10" surfaces months
        // later as a wrong search result.
        assertThat(saved().get(amountFieldId.toString()).v())
                .isInstanceOf(BigDecimal.class)
                .satisfies(v -> assertThat((BigDecimal) v).isEqualByComparingTo(new BigDecimal("2479475.00")));
    }

    /**
     * Regression test: ocr-worker JSON-encodes a multi_select field's value
     * to a string (e.g. {@code "[\"policy\",\"hr\"]"}) so it fits this
     * sparse map's {@code {value, confidence, bbox}} shape (see
     * extraction.py's {@code requested_fields_to_output_dict}). Without
     * decoding that string back into a real {@code List<String>}, it would
     * be stored as one value containing the literal bracketed text instead
     * of a list of tags.
     */
    @Test
    void multiSelectOcrValueIsDecodedIntoAList() {
        UUID tagsFieldId = UUID.randomUUID();
        given(new DocumentTypeSchemaResponse(documentTypeId, "GENERAL", "General", 1,
                List.of(schemaField(tagsFieldId, "tags", "multi_select"))), null);

        service().applyOcrFields(documentId, Map.of(
                "tags", Map.of("value", "[\"policy\",\"hr\"]", "confidence", 0.7)));

        assertThat(saved().get(tagsFieldId.toString()).v())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("policy", "hr");
    }
}
