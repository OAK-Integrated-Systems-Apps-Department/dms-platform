package com.oak.dms.importexport.template;

import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedTemplate;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.PrefilledRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CSV templates, and failed-rows reports in both formats, read back exactly like the .xlsx template. */
class ImportTemplateCsvTest {

    private final ImportTemplateCsv csv = new ImportTemplateCsv();
    private final ImportTemplateWorkbook workbook = new ImportTemplateWorkbook();

    @Test
    void generate_writesMarkerGuidanceAndKeyHeader_andParsesBack() {
        byte[] template = csv.generate(ImportTemplateWorkbookTest.schema(3), List.of());
        String text = new String(template, StandardCharsets.UTF_8);

        assertThat(text).startsWith("﻿" + ImportTemplateCsv.MARKER);
        assertThat(text).contains("# category: Category - optional - one of: finance / hr");
        assertThat(text).contains("# invoice_date: Invoice Date - required - " + ImportTemplateWorkbook.DATE_HINT);
        assertThat(text).doesNotContain("internal_note"); // hidden field
        assertThat(text).contains("filename,title,invoice_date,amount,category,tags,reference");

        ParsedTemplate parsed = csv.parse(new ByteArrayInputStream(template), 10);
        assertThat(parsed.schemaVersion()).isEqualTo(3);
        assertThat(parsed.columnKeys()).containsExactly("filename", "title", "invoice_date", "amount", "category",
                "tags", "reference");
        assertThat(parsed.rows()).isEmpty();
    }

    @Test
    void parse_rowsLikeTheWorkbook_skippingBlankRowsAndTrimming() {
        String text = ImportTemplateCsv.MARKER + " documentTypeId=" + ImportTemplateWorkbookTest.schema(1).documentTypeId()
                + " schemaVersion=1\n"
                + "filename,title,amount,tags\n"
                + " scan-1.pdf ,Invoice 1,12.50,a;b\n"
                + ",,,\n"
                + "scan-2.pdf,,,\n";

        ParsedTemplate parsed = csv.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), 10);

        assertThat(parsed.rows()).containsExactly(
                new ParsedRow(3, "scan-1.pdf", "Invoice 1", Map.of("amount", "12.50", "tags", "a;b")),
                new ParsedRow(5, "scan-2.pdf", null, Map.of()));
    }

    @Test
    void parse_markerReQuotedByASpreadsheetApp_stillRecognised() {
        String text = "\"" + ImportTemplateCsv.MARKER + " documentTypeId=" + ImportTemplateWorkbookTest.schema(1).documentTypeId()
                + " schemaVersion=7\",,\n\"# guidance, with a comma\",,\nfilename,title,amount\na.pdf,,1\n";

        ParsedTemplate parsed = csv.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), 10);

        assertThat(parsed.schemaVersion()).isEqualTo(7);
        assertThat(parsed.rows()).singleElement().extracting(ParsedRow::filename).isEqualTo("a.pdf");
    }

    @Test
    void parse_notATemplate_tooManyRows_orBadHeader_areRefused() {
        assertThatThrownBy(() -> csv.parse(stream("filename,title\na.pdf,A\n"), 10))
                .isInstanceOf(InvalidImportTemplateException.class);
        String marker = ImportTemplateCsv.MARKER + " documentTypeId=" + ImportTemplateWorkbookTest.schema(1).documentTypeId()
                + " schemaVersion=1\n";
        assertThatThrownBy(() -> csv.parse(stream(marker + "filename,title\na,\nb,\nc,\n"), 2))
                .isInstanceOf(ImportBatchSizeException.class).hasMessageContaining("more than 2 rows");
        assertThatThrownBy(() -> csv.parse(stream(marker + "title,filename\nA,a.pdf\n"), 10))
                .isInstanceOf(InvalidImportTemplateException.class);
        assertThatThrownBy(() -> csv.parse(stream(marker + "filename,title,amount,amount\na,,1,2\n"), 10))
                .isInstanceOf(InvalidImportTemplateException.class);
    }

    @Test
    void failedRowsReport_bothFormats_roundTripTheRows_andIgnoreTheProblemsColumn() {
        List<PrefilledRow> rows = List.of(new PrefilledRow("scan-9.pdf", "Invoice 9",
                Map.of("amount", "abc", "invoice_date", "2026-09-30", "reference", "0042"), "Amount: must be a number"));

        ParsedTemplate fromCsv = csv.parse(new ByteArrayInputStream(csv.generate(ImportTemplateWorkbookTest.schema(2), rows)), 10);
        ParsedTemplate fromXlsx = workbook.parse(new ByteArrayInputStream(
                workbook.generate(ImportTemplateWorkbookTest.schema(2), 10, rows)), 10);

        for (ParsedTemplate parsed : List.of(fromCsv, fromXlsx)) {
            assertThat(parsed.schemaVersion()).isEqualTo(2);
            assertThat(parsed.columnKeys()).doesNotContain(ImportTemplateCsv.PROBLEMS_HEADER);
            assertThat(parsed.rows()).singleElement().satisfies(row -> {
                assertThat(row.filename()).isEqualTo("scan-9.pdf");
                assertThat(row.title()).isEqualTo("Invoice 9");
                // Text cells keep leading zeros; the problems text is never read back.
                assertThat(row.fields()).isEqualTo(Map.of("amount", "abc", "invoice_date", "2026-09-30",
                        "reference", "0042"));
            });
        }
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
