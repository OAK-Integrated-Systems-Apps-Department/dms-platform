package com.oak.dms.document;

import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link DocumentServiceImpl#validateRequestedFields} -- the
 * gate behind both {@code POST /documents} (requestedFields) and
 * {@code POST /documents/{versionId}/ocr} (re-run fields). No Spring
 * context: this method has no instance state.
 */
class DocumentServiceRequestedFieldsValidationTest {

    private static DocumentTypeSchemaResponse.SchemaField field(String key, boolean mandatory, boolean hidden) {
        return new DocumentTypeSchemaResponse.SchemaField(
                UUID.randomUUID(), key, key, null, "text", "active",
                mandatory, hidden, false, 0, null, null, List.of(), List.of());
    }

    private static DocumentTypeSchemaResponse schemaOf(DocumentTypeSchemaResponse.SchemaField... fields) {
        return new DocumentTypeSchemaResponse(UUID.randomUUID(), "GENERAL", "General", 1, List.of(fields));
    }

    @Test
    void rejectsAFieldKeyNotOnTheSchema() {
        DocumentTypeSchemaResponse schema = schemaOf(field("classification_level", true, false));

        assertThatThrownBy(() -> DocumentServiceImpl.validateRequestedFields(
                schema, List.of("classification_level", "not_a_real_field"), true))
                .isInstanceOf(MetadataValidationFailedException.class)
                .hasMessageContaining("not_a_real_field");
    }

    @Test
    void rejectsAHiddenFieldEvenThoughItExistsOnTheSchema() {
        DocumentTypeSchemaResponse schema = schemaOf(
                field("classification_level", true, false),
                field("legacy_field", false, true));

        assertThatThrownBy(() -> DocumentServiceImpl.validateRequestedFields(
                schema, List.of("classification_level", "legacy_field"), true))
                .isInstanceOf(MetadataValidationFailedException.class)
                .hasMessageContaining("legacy_field");
    }

    @Test
    void uploadRejectsWhenAMandatoryFieldIsNotRequested() {
        DocumentTypeSchemaResponse schema = schemaOf(
                field("classification_level", true, false),
                field("author", false, false));

        assertThatThrownBy(() -> DocumentServiceImpl.validateRequestedFields(schema, List.of("author"), true))
                .isInstanceOf(MetadataValidationFailedException.class)
                .hasMessageContaining("classification_level");
    }

    @Test
    void uploadAcceptsWhenEveryMandatoryFieldIsRequested() {
        DocumentTypeSchemaResponse schema = schemaOf(
                field("classification_level", true, false),
                field("author", false, false));

        assertThatCode(() -> DocumentServiceImpl.validateRequestedFields(
                schema, List.of("classification_level", "author"), true))
                .doesNotThrowAnyException();
    }

    @Test
    void rerunDoesNotRequireMandatoryFieldsToBeReRequested() {
        DocumentTypeSchemaResponse schema = schemaOf(
                field("classification_level", true, false),
                field("author", false, false));

        // A re-run of just the optional "author" field, with no mention of
        // the mandatory classification_level, must be allowed -- unlike
        // upload's enforceMandatory=true path above.
        assertThatCode(() -> DocumentServiceImpl.validateRequestedFields(schema, List.of("author"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void emptyRequestedFieldsIsFineWhenNotEnforcingMandatory() {
        DocumentTypeSchemaResponse schema = schemaOf(field("classification_level", true, false));

        assertThatCode(() -> DocumentServiceImpl.validateRequestedFields(schema, List.of(), false))
                .doesNotThrowAnyException();
    }

    @Test
    void sanityCheckFixtureShape() {
        DocumentTypeSchemaResponse schema = schemaOf(field("author", false, false));
        assertThat(schema.fields()).hasSize(1);
    }
}
