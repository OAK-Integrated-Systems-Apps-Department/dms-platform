package com.oak.dms.importexport.template;

import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedTemplate;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportTemplateWorkbookTest {

    private static final UUID DOCUMENT_TYPE_ID = UUID.randomUUID();

    private final ImportTemplateWorkbook templateWorkbook = new ImportTemplateWorkbook();

    @Test
    void generate_buildsHeadersCommentsDropdownAndVeryHiddenMeta() throws IOException {
        byte[] template = templateWorkbook.generate(schema(3), 500);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            Sheet importSheet = workbook.getSheet(ImportTemplateWorkbook.IMPORT_SHEET);
            Row header = importSheet.getRow(0);
            List<String> headers = List.of(0, 1, 2, 3, 4, 5, 6).stream()
                    .map(c -> header.getCell(c).getStringCellValue()).toList();
            assertThat(headers).containsExactly(
                    "Filename", "Title", "Invoice Date *", "Amount", "Category", "Tags", "Reference");
            assertThat(header.getCell(7)).isNull();

            assertThat(header.getCell(2).getCellComment().getString().getString()).isEqualTo("When it was issued");
            assertThat(header.getCell(5).getCellComment().getString().getString())
                    .contains("Separate values with ;");

            List<? extends DataValidation> validations = importSheet.getDataValidations();
            assertThat(validations).hasSize(1);
            assertThat(validations.get(0).getValidationConstraint().getFormula1()).contains("_options");
            assertThat(validations.get(0).getRegions().getCellRangeAddress(0).getFirstColumn()).isEqualTo(4);

            int metaIndex = workbook.getSheetIndex(ImportTemplateWorkbook.META_SHEET);
            assertThat(workbook.getSheetVisibility(metaIndex)).isEqualTo(SheetVisibility.VERY_HIDDEN);
            int optionsIndex = workbook.getSheetIndex(ImportTemplateWorkbook.OPTIONS_SHEET);
            assertThat(workbook.getSheetVisibility(optionsIndex)).isEqualTo(SheetVisibility.VERY_HIDDEN);
            assertThat(workbook.getSheet(ImportTemplateWorkbook.OPTIONS_SHEET).getRow(1).getCell(0)
                    .getStringCellValue()).isEqualTo("hr");
        }
    }

    @Test
    void parse_filledTemplate_roundTripsMetaAndConvertsCellsAndSkipsEmptyRows() throws IOException {
        byte[] filled = fill(templateWorkbook.generate(schema(3), 500), workbook -> {
            Sheet sheet = workbook.getSheet(ImportTemplateWorkbook.IMPORT_SHEET);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("  Scan-001.PDF ");
            first.createCell(1).setCellValue("Invoice 1");
            first.createCell(2).setCellValue(LocalDate.of(2026, 9, 30));
            first.getCell(2).setCellStyle(dateStyle);
            first.createCell(3).setCellValue(12345678901d);
            first.createCell(4).setCellValue("finance");
            first.createCell(5).setCellValue("urgent;q3");
            first.createCell(6).setCellValue("  REF-9 ");

            Row empty = sheet.createRow(2);
            empty.createCell(0).setCellValue("   ");
            empty.createCell(3);

            Row second = sheet.createRow(3);
            second.createCell(0).setCellValue("doc2.pdf");
            second.createCell(3).setCellValue(1.50d);
        });

        ParsedTemplate parsed = templateWorkbook.parse(new ByteArrayInputStream(filled), 500);

        assertThat(parsed.documentTypeId()).isEqualTo(DOCUMENT_TYPE_ID);
        assertThat(parsed.schemaVersion()).isEqualTo(3);
        assertThat(parsed.columnKeys()).containsExactly(
                "filename", "title", "invoice_date", "amount", "category", "tags", "reference");

        assertThat(parsed.rows()).extracting(ParsedRow::rowNumber).containsExactly(2, 4);

        ParsedRow first = parsed.rows().get(0);
        assertThat(first.filename()).isEqualTo("Scan-001.PDF");
        assertThat(first.title()).isEqualTo("Invoice 1");
        assertThat(first.fields()).containsExactly(
                Map.entry("invoice_date", "2026-09-30"),
                Map.entry("amount", "12345678901"),
                Map.entry("category", "finance"),
                Map.entry("tags", "urgent;q3"),
                Map.entry("reference", "REF-9"));

        ParsedRow second = parsed.rows().get(1);
        assertThat(second.filename()).isEqualTo("doc2.pdf");
        assertThat(second.title()).isNull();
        assertThat(second.fields()).containsExactly(Map.entry("amount", "1.5"));
    }

    @Test
    void parse_moreNonEmptyRowsThanLimit_rejectsWithRowCountError() {
        byte[] filled = fill(templateWorkbook.generate(schema(1), 2), workbook -> {
            Sheet sheet = workbook.getSheet(ImportTemplateWorkbook.IMPORT_SHEET);
            for (int r = 1; r <= 3; r++) {
                sheet.createRow(r).createCell(0).setCellValue("file" + r + ".pdf");
            }
        });

        assertThatThrownBy(() -> templateWorkbook.parse(new ByteArrayInputStream(filled), 2))
                .isInstanceOf(ImportBatchSizeException.class)
                .extracting("code").isEqualTo("IMPORT_ROW_COUNT_INVALID");
    }

    @Test
    void parse_workbookWithoutMetaSheet_rejectsAsInvalidTemplate() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet(ImportTemplateWorkbook.IMPORT_SHEET).createRow(0).createCell(0).setCellValue("Filename");
            workbook.write(out);
            bytes = out.toByteArray();
        }

        assertThatThrownBy(() -> templateWorkbook.parse(new ByteArrayInputStream(bytes), 500))
                .isInstanceOf(InvalidImportTemplateException.class)
                .extracting("code").isEqualTo("IMPORT_TEMPLATE_INVALID");
    }

    @Test
    void parse_notAWorkbook_rejectsAsInvalidTemplate() {
        assertThatThrownBy(() -> templateWorkbook.parse(new ByteArrayInputStream("not,a,workbook".getBytes()), 500))
                .isInstanceOf(InvalidImportTemplateException.class);
    }

    static DocumentTypeSchemaResponse schema(int version) {
        return schema(DOCUMENT_TYPE_ID, version);
    }

    static DocumentTypeSchemaResponse schema(UUID documentTypeId, int version) {
        UUID categoryId = UUID.randomUUID();
        return new DocumentTypeSchemaResponse(documentTypeId, "INV", "Invoice", version, List.of(
                field("reference", "Reference", null, "text", false, false, 60, List.of()),
                field("invoice_date", "Invoice Date", "When it was issued", "date", true, false, 10, List.of()),
                field("internal_note", "Internal Note", null, "text", false, true, 5, List.of()),
                field("amount", "Amount", null, "decimal", false, false, 20, List.of()),
                field("category", "Category", "Pick one", "dropdown", false, false, 30, List.of(
                        new MetadataFieldOptionResponse(UUID.randomUUID(), categoryId, null, "finance", "Finance", 1, "active"),
                        new MetadataFieldOptionResponse(UUID.randomUUID(), categoryId, null, "hr", "HR", 2, "active"))),
                field("tags", "Tags", null, "multi_select", false, false, 40, List.of())));
    }

    private static SchemaField field(String key, String label, String helpText, String dataType, boolean mandatory,
                                     boolean hidden, int displayOrder, List<MetadataFieldOptionResponse> options) {
        return new SchemaField(UUID.randomUUID(), key, label, helpText, dataType, "active", mandatory, hidden,
                displayOrder, null, null, options, List.of());
    }

    private static byte[] fill(byte[] template, Consumer<XSSFWorkbook> filler) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            filler.accept(workbook);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
