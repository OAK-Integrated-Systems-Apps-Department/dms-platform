package com.oak.dms.importexport.service;

import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.template.ParsedTemplate;
import com.oak.dms.importexport.template.RowValueConverter;
import com.oak.dms.importexport.template.TemplateColumns;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.oak.dms.importexport.template.ImportTemplateRoundTripTest.TYPE_ID;
import static com.oak.dms.importexport.template.ImportTemplateRoundTripTest.schema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B2 row validation: one bad row never blocks the others; template-level problems do. */
class BulkImportValidatorTest {

    private final MetadataValidationService metadataValidation = mock(MetadataValidationService.class);
    private final BulkImportValidator validator =
            new BulkImportValidator(new RowValueConverter(mock(UserRepository.class)), metadataValidation);

    @TempDir
    Path dir;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(UUID.randomUUID(), UUID.randomUUID());
        when(metadataValidation.validate(eq(TYPE_ID), any(), anyMap())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void eachRowGetsItsOwnErrors_andValidRowsPass() throws Exception {
        Files.createDirectories(dir.resolve(ImportFiles.LOOSE_DIR));
        Files.writeString(dir.resolve(ImportFiles.LOOSE_DIR).resolve("a.pdf"), "A");
        Files.writeString(dir.resolve(ImportFiles.LOOSE_DIR).resolve("c.pdf"), "C");
        // The engine's own rules: invoice_no too long on the row that has "TOO-LONG-NUMBER".
        when(metadataValidation.validate(eq(TYPE_ID), eq(1), argThat(m -> "TOO-LONG-NUMBER".equals(m.get("invoice_no")))))
                .thenReturn(List.of(new ValidationError("invoice_no", "MAX_LENGTH", "Too long.", "error"),
                        new ValidationError("invoice_no", "REGEX", "Looks odd.", "warning")));
        DocumentTypeSchemaResponse schema = schema(1);
        ParsedTemplate template = template(1,
                row(2, Map.of(TemplateColumns.FILENAME, "a.pdf", "invoice_no", "INV-1", "amount", "10")),
                row(3, Map.of(TemplateColumns.FILENAME, "b.pdf", "invoice_no", "INV-2")),
                row(4, Map.of(TemplateColumns.FILENAME, "A.PDF", "invoice_no", "INV-3")),
                row(5, Map.of(TemplateColumns.FILENAME, "c.pdf", "invoice_no", "TOO-LONG-NUMBER", "amount", "ten")),
                row(6, Map.of("invoice_no", "INV-6")));

        List<BulkImportValidator.RowResult> results;
        try (ImportFiles files = ImportFiles.open(List.of(dir), 100)) {
            results = validator.validateRows(template, schema, files);
        }

        assertThat(results).extracting(r -> r.row().rowNumber()).containsExactly(2, 3, 4, 5, 6);
        assertThat(results.get(0).errors()).isEmpty();
        assertThat(results.get(1).errors()).containsExactly("File name: no uploaded file is called \"b.pdf\"");
        assertThat(results.get(2).errors()).containsExactly("File name: the same file is already listed on row 2");
        // Conversion error and the engine's blocking rule both reported; the warning isn't.
        assertThat(results.get(3).errors()).containsExactly(
                "amount: \"ten\" is not a number (use . as the decimal point)", "invoice_no: Too long.");
        assertThat(results.get(4).errors()).containsExactly("File name: required");
    }

    @Test
    void anOutdatedTemplate_isRefusedAsAWhole() {
        ParsedTemplate old = template(1, row(2, Map.of(TemplateColumns.FILENAME, "a.pdf")));

        assertThatThrownBy(() -> validator.checkTemplate(old, schema(2)))
                .isInstanceOf(BulkImportException.class)
                .hasMessageContaining("made for version 1")
                .hasMessageContaining("now version 2");
    }

    @Test
    void unknownColumns_orNoRows_areRefusedAsAWhole() {
        ParsedTemplate foreign = new ParsedTemplate(TYPE_ID, 1, TemplateFormat.csv,
                List.of(TemplateColumns.FILENAME, "po_number"), List.of(row(2, Map.of(TemplateColumns.FILENAME, "a"))));
        assertThatThrownBy(() -> validator.checkTemplate(foreign, schema(1)))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("po_number");

        ParsedTemplate empty = template(1);
        assertThatThrownBy(() -> validator.checkTemplate(empty, schema(1)))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("no rows");

        // The report's errors column is fine to upload again.
        ParsedTemplate report = new ParsedTemplate(TYPE_ID, 1, TemplateFormat.csv,
                List.of(TemplateColumns.FILENAME, TemplateColumns.ERRORS), List.of(row(2, Map.of(TemplateColumns.FILENAME, "a"))));
        validator.checkTemplate(report, schema(1));
    }

    @Test
    void title_defaultsToTheFileNameWithoutExtension() {
        assertThat(BulkImportValidator.titleFor(Map.of(TemplateColumns.FILENAME, "2026/jan/invoice-001.pdf")))
                .isEqualTo("invoice-001");
        assertThat(BulkImportValidator.titleFor(Map.of(TemplateColumns.FILENAME, "a.pdf", TemplateColumns.TITLE, " Mine ")))
                .isEqualTo("Mine");
        assertThat(BulkImportValidator.titleFor(Map.of(TemplateColumns.FILENAME, ".env"))).isEqualTo(".env");
    }

    private static ParsedTemplate template(int version, ParsedTemplate.Row... rows) {
        return new ParsedTemplate(TYPE_ID, version, TemplateFormat.xlsx,
                TemplateColumns.headers(TemplateColumns.importableFields(schema(version))), List.of(rows));
    }

    private static ParsedTemplate.Row row(int number, Map<String, String> cells) {
        return new ParsedTemplate.Row(number, cells);
    }
}
