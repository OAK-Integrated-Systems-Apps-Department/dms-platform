package com.oak.dms.importexport.template;

import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B1/B3: what ImportTemplateWriter writes, ImportTemplateReader reads back - both formats. */
public class ImportTemplateRoundTripTest {

    public static final UUID TYPE_ID = UUID.randomUUID();

    private final ImportTemplateWriter writer = new ImportTemplateWriter();
    private final ImportTemplateReader reader = new ImportTemplateReader();

    public static DocumentTypeSchemaResponse schema(int version) {
        UUID statusId = UUID.randomUUID();
        return new DocumentTypeSchemaResponse(TYPE_ID, "INV", "Invoice", version, List.of(
                field("invoice_no", "text", true, false, "active",
                        List.of(), List.of(rule("max_length", "12", "error"))),
                field("amount", "decimal", false, false, "active", List.of(), List.of()),
                new SchemaField(statusId, "state", "State", null, "dropdown", "active", false, false, 3, null, null,
                        List.of(option(statusId, "open", "Open"), option(statusId, "closed", "Closed")), List.of()),
                field("issued_on", "date", false, false, "active", List.of(), List.of()),
                field("paid", "boolean", false, false, "active", List.of(), List.of()),
                field("internal_ref", "text", false, true, "active", List.of(), List.of()),
                field("legacy_code", "text", false, false, "inactive", List.of(), List.of())));
    }

    static SchemaField field(String key, String type, boolean mandatory, boolean hidden, String status,
                             List<MetadataFieldOptionResponse> options, List<MetadataFieldValidationResponse> rules) {
        return new SchemaField(UUID.randomUUID(), key, key.replace('_', ' '), null, type, status, mandatory, hidden,
                0, null, null, options, rules);
    }

    static MetadataFieldOptionResponse option(UUID fieldId, String value, String label) {
        return new MetadataFieldOptionResponse(UUID.randomUUID(), fieldId, null, value, label, 0, "active");
    }

    static MetadataFieldValidationResponse rule(String type, String value, String severity) {
        return new MetadataFieldValidationResponse(UUID.randomUUID(), UUID.randomUUID(), type, value, null, severity);
    }

    @Test
    void xlsxTemplate_carriesMarkerHeadersAndGuidance_andReadsBack() throws Exception {
        byte[] xlsx = writer.writeTemplate(TemplateFormat.xlsx, schema(3));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheetVisibility(workbook.getSheetIndex(TemplateColumns.MARKER_SHEET)))
                    .isEqualTo(SheetVisibility.VERY_HIDDEN);
            assertThat(workbook.getSheet(TemplateColumns.GUIDANCE_SHEET)).isNotNull();
            // One drop-down per option field (state) and per boolean field (paid).
            assertThat(workbook.getSheet(TemplateColumns.DATA_SHEET).getDataValidations()).hasSize(2);
        }
        ParsedTemplate parsed = reader.read("invoices.xlsx", xlsx, 100);
        assertThat(parsed.documentTypeId()).isEqualTo(TYPE_ID);
        assertThat(parsed.schemaVersion()).isEqualTo(3);
        assertThat(parsed.format()).isEqualTo(TemplateFormat.xlsx);
        // Hidden and inactive fields are left out, exactly as validation skips them.
        assertThat(parsed.headers()).containsExactly(TemplateColumns.FILENAME, TemplateColumns.TITLE,
                "invoice_no", "amount", "state", "issued_on", "paid");
        assertThat(parsed.rows()).isEmpty();
    }

    @Test
    void csvTemplate_carriesMarkerAndGuidanceLines_andReadsBack() {
        byte[] csv = writer.writeTemplate(TemplateFormat.csv, schema(2));
        String text = new String(csv, StandardCharsets.UTF_8);

        assertThat(text).startsWith("﻿" + TemplateColumns.CSV_MARKER);
        assertThat(text).contains("# state: State - optional - One of: open / closed");
        assertThat(text).contains("# invoice_no: invoice no - required - Text - at most 12 characters");

        ParsedTemplate parsed = reader.read("invoices.csv", csv, 100);
        assertThat(parsed.documentTypeId()).isEqualTo(TYPE_ID);
        assertThat(parsed.schemaVersion()).isEqualTo(2);
        assertThat(parsed.headers()).containsExactly(TemplateColumns.FILENAME, TemplateColumns.TITLE,
                "invoice_no", "amount", "state", "issued_on", "paid");
    }

    @Test
    void failedRowsReport_roundTripsItsRows_andTheErrorsColumnIsIgnoredOnUpload() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(TemplateColumns.FILENAME, "scans/a.pdf");
        cells.put("invoice_no", "0042");
        cells.put("amount", "12.50");
        List<ImportTemplateWriter.RowOut> rows = List.of(new ImportTemplateWriter.RowOut(cells, "invoice_no: too long"));

        for (TemplateFormat format : TemplateFormat.values()) {
            byte[] report = writer.writeRows(format, schema(1), rows);
            ParsedTemplate parsed = reader.read("report." + format.name(), report, 100);

            assertThat(parsed.headers()).as(format.name()).contains(TemplateColumns.ERRORS);
            assertThat(parsed.rows()).as(format.name()).hasSize(1);
            // Leading zero survives (xlsx cells are text-formatted); errors dropped.
            assertThat(parsed.rows().get(0).cells()).as(format.name()).isEqualTo(cells);
        }
    }

    @Test
    void xlsx_dateAndNumberCellsTypedByTheUser_readAsIsoAndPlainNumbers() throws Exception {
        byte[] template = writer.writeTemplate(TemplateFormat.xlsx, schema(1));
        byte[] filled;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet data = workbook.getSheet(TemplateColumns.DATA_SHEET);
            Row row = data.createRow(1);
            row.createCell(0).setCellValue("a.pdf");
            row.createCell(3).setCellValue(10000000.0); // amount, as a real number cell
            var dateCell = row.createCell(5);           // issued_on, as a real date cell
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            dateCell.setCellStyle(dateStyle);
            dateCell.setCellValue(LocalDate.of(2026, 9, 30));
            row.createCell(6).setCellValue(true);       // paid, as a boolean cell
            data.createRow(2); // blank row: skipped
            workbook.write(out);
            filled = out.toByteArray();
        }

        ParsedTemplate parsed = reader.read("x.xlsx", filled, 100);

        assertThat(parsed.rows()).hasSize(1);
        ParsedTemplate.Row row = parsed.rows().get(0);
        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.cell("amount")).isEqualTo("10000000");
        assertThat(row.cell("issued_on")).isEqualTo("2026-09-30");
        assertThat(row.cell("paid")).isEqualTo("Yes");
    }

    @Test
    void aSpreadsheetThatIsNotATemplate_isRefused() throws Exception {
        byte[] plain;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue(TemplateColumns.FILENAME);
            workbook.write(out);
            plain = out.toByteArray();
        }
        assertThatThrownBy(() -> reader.read("mine.xlsx", plain, 100))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("isn't a DMS import template");
        assertThatThrownBy(() -> reader.read("mine.csv", "File name,title\na.pdf,A\n".getBytes(StandardCharsets.UTF_8), 100))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("isn't a DMS import template");
        assertThatThrownBy(() -> reader.read("mine.xlsx", "not a zip".getBytes(StandardCharsets.UTF_8), 100))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("could not be read");
        assertThatThrownBy(() -> reader.read("mine.pdf", plain, 100))
                .isInstanceOf(BulkImportException.class).hasMessageContaining(".xlsx or .csv");
    }

    @Test
    void csv_tooManyRowsOrDuplicateColumns_isRefused() {
        String marker = TemplateColumns.CSV_MARKER + " documentTypeId=" + TYPE_ID + " schemaVersion=1\n";
        String many = marker + "File name\na.pdf\nb.pdf\nc.pdf\n";
        assertThatThrownBy(() -> reader.read("t.csv", many.getBytes(StandardCharsets.UTF_8), 2))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("at most 2 rows");

        String duplicate = marker + "File name,amount,amount\na.pdf,1,2\n";
        assertThatThrownBy(() -> reader.read("t.csv", duplicate.getBytes(StandardCharsets.UTF_8), 10))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("more than once");

        String noFilename = marker + "amount\n1\n";
        assertThatThrownBy(() -> reader.read("t.csv", noFilename.getBytes(StandardCharsets.UTF_8), 10))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("column is missing");
    }

    @Test
    void csv_markerStillFound_whenASpreadsheetAppReQuotedTheHashLines() {
        String csv = "\"" + TemplateColumns.CSV_MARKER + " documentTypeId=" + TYPE_ID + " schemaVersion=4\",,\n"
                + "\"# guidance, with a comma\",,\n"
                + "File name,Document title,amount\n"
                + "a.pdf,,\n";
        ParsedTemplate parsed = reader.read("t.csv", csv.getBytes(StandardCharsets.UTF_8), 10);
        assertThat(parsed.schemaVersion()).isEqualTo(4);
        assertThat(parsed.rows()).singleElement().satisfies(r -> {
            assertThat(r.cells()).containsOnlyKeys(TemplateColumns.FILENAME);
            assertThat(r.rowNumber()).isEqualTo(4);
        });
    }
}
