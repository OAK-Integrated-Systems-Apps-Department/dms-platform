package com.oak.dms.importexport.template;

import com.oak.dms.importexport.service.impl.BulkImportServiceImplDefaultsAccess;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedTemplate;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.TemplateLayout;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A saved template's file: its own columns and headers, and its id carried through to the upload. */
class SavedTemplateLayoutTest {

    private final ImportTemplateWorkbook workbook = new ImportTemplateWorkbook();
    private final ImportTemplateCsv csv = new ImportTemplateCsv();

    @Test
    void xlsx_customColumnsAndHeaders_parseBackByKey_withTheTemplateId() throws Exception {
        DocumentTypeSchemaResponse schema = ImportTemplateWorkbookTest.schema(4);
        UUID templateId = UUID.randomUUID();
        TemplateLayout layout = layout(schema, templateId, List.of("amount", "reference"), Map.of("amount", "Total (LKR)"));

        byte[] template = workbook.generate(schema, layout, 10, List.of());
        byte[] filled;
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.getSheet(ImportTemplateWorkbook.IMPORT_SHEET);
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Total (LKR)");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("Reference");
            assertThat(sheet.getRow(0).getCell(4)).isNull();
            Row row = sheet.getRow(1) != null ? sheet.getRow(1) : sheet.createRow(1);
            row.createCell(0).setCellValue("a.pdf");
            row.createCell(2).setCellValue("12.5");
            book.write(out);
            filled = out.toByteArray();
        }

        ParsedTemplate parsed = workbook.parse(new ByteArrayInputStream(filled), 10);

        assertThat(parsed.importTemplateId()).isEqualTo(templateId);
        assertThat(parsed.columnKeys()).containsExactly("filename", "title", "amount", "reference");
        assertThat(parsed.rows()).containsExactly(new ParsedRow(2, "a.pdf", null, Map.of("amount", "12.5")));
    }

    @Test
    void csv_customHeaders_areReadThroughTheColumnsLine() {
        DocumentTypeSchemaResponse schema = ImportTemplateWorkbookTest.schema(4);
        UUID templateId = UUID.randomUUID();
        TemplateLayout layout = layout(schema, templateId, List.of("reference", "amount"), Map.of("reference", "Ref, external"));

        String text = new String(csv.generate(schema, layout, List.of()), StandardCharsets.UTF_8);
        assertThat(text).contains("# columns: filename|title|reference|amount");
        assertThat(text).contains("filename,title,\"Ref, external\",Amount");

        ParsedTemplate parsed = csv.parse(new ByteArrayInputStream(
                (text + "b.pdf,B,X-1,9\r\n").getBytes(StandardCharsets.UTF_8)), 10);
        assertThat(parsed.importTemplateId()).isEqualTo(templateId);
        assertThat(parsed.rows()).singleElement()
                .satisfies(r -> assertThat(r.fields()).isEqualTo(Map.of("reference", "X-1", "amount", "9")));
    }

    @Test
    void standardTemplates_carryNoTemplateId() {
        DocumentTypeSchemaResponse schema = ImportTemplateWorkbookTest.schema(1);
        assertThat(workbook.parse(new ByteArrayInputStream(workbook.generate(schema, 5)), 5).importTemplateId()).isNull();
        assertThat(csv.parse(new ByteArrayInputStream(csv.generate(schema, List.of())), 5).importTemplateId()).isNull();
    }

    @Test
    void defaults_fillOnlyCellsTheUserLeftOutOrBlank() {
        ParsedRow row = new ParsedRow(2, "a.pdf", null, Map.of("amount", "5", "currency", " "));

        ParsedRow merged = BulkImportServiceImplDefaultsAccess.withDefaults(row,
                Map.of("amount", "1", "currency", "LKR", "category", "finance"));

        assertThat(merged.fields()).isEqualTo(Map.of("amount", "5", "currency", "LKR", "category", "finance"));
    }

    private static TemplateLayout layout(DocumentTypeSchemaResponse schema, UUID id, List<String> keys,
                                         Map<String, String> headers) {
        List<SchemaField> fields = keys.stream()
                .map(k -> schema.fields().stream().filter(f -> f.fieldKey().equals(k)).findFirst().orElseThrow())
                .toList();
        return new TemplateLayout(id, fields, headers);
    }
}
