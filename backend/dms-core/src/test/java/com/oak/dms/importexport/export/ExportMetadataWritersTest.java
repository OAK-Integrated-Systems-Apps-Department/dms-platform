package com.oak.dms.importexport.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportMetadataWritersTest {

    private final ExportMetadataTable table = new ExportMetadataTable(
            List.of("File in ZIP", "Title", "Amount", "Due", "Received", "Approved", "Status", "Owner"), 2,
            List.of(List.of(
                    ExportCell.text("invoice.pdf"),
                    ExportCell.text("=cmd|' /C calc'!A0"),
                    new ExportCell.Number(new BigDecimal("1250.50")),
                    new ExportCell.Date(LocalDate.of(2026, 3, 31)),
                    new ExportCell.DateTime(LocalDateTime.of(2026, 3, 1, 9, 30), ZoneOffset.UTC),
                    new ExportCell.Bool(true),
                    new ExportCell.Options(List.of("APPROVED", "PAID"), List.of("Approved", "Paid in full")),
                    new ExportCell.UserRef("7d3c0d5e-6d7b-4a54-9a8f-1a3cbbd0f001", "Asha Perera"))));
    private final List<SkippedDocument> skipped = List.of(
            new SkippedDocument("Old memo", "The document has been deleted"),
            new SkippedDocument("+Scan, draft", "The current version has not passed the malware scan"));
    private final ExportSummary summary = new ExportSummary("Asha Perera",
            OffsetDateTime.parse("2026-03-02T10:00:00Z"), OffsetDateTime.parse("2026-03-02T10:05:00Z"), "xlsx",
            List.of(Map.entry("Selection", "Documents matching the filters below"),
                    Map.entry("Document type", "Invoice")),
            3, 1, 2);

    @Test
    void csv_metadata_isBomPrefixedWithCodesIdsIsoValuesAndEscapedCells() {
        byte[] bytes = ExportMetadataCsvWriter.metadata(table);

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String[] lines = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8).split("\n");
        assertThat(lines[0]).isEqualTo("File in ZIP,Title,Amount,Due,Received,Approved,Status,Owner");
        assertThat(lines[1]).isEqualTo("invoice.pdf,'=cmd|' /C calc'!A0,1250.50,2026-03-31,2026-03-01T09:30:00Z,"
                + "true,APPROVED;PAID,7d3c0d5e-6d7b-4a54-9a8f-1a3cbbd0f001");
    }

    @Test
    void csv_skipped_isItsOwnFileWithTitleAndReason() {
        byte[] bytes = ExportMetadataCsvWriter.skipped(skipped);

        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).isEqualTo(
                "Title,Reason\n"
                        + "Old memo,The document has been deleted\n"
                        + "\"'+Scan, draft\",The current version has not passed the malware scan\n");
    }

    @Test
    void xlsx_documentsSheet_hasStyledFrozenHeaderTypedCellsAndLabels() throws Exception {
        try (XSSFWorkbook workbook = read(ExportMetadataXlsxWriter.write(table, skipped, summary))) {
            Sheet sheet = workbook.getSheet(ExportMetadataXlsxWriter.DOCUMENTS_SHEET);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("File in ZIP");
            assertThat(workbook.getFontAt(header.getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);

            Row row = sheet.getRow(1);
            // Text cells are never formulas - no escaping needed in xlsx.
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("=cmd|' /C calc'!A0");
            assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(1250.50);
            assertDate(row.getCell(3), LocalDateTime.of(2026, 3, 31, 0, 0));
            assertDate(row.getCell(4), LocalDateTime.of(2026, 3, 1, 9, 30));
            assertThat(row.getCell(5).getBooleanCellValue()).isTrue();
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo("Approved; Paid in full");
            assertThat(row.getCell(7).getStringCellValue()).isEqualTo("Asha Perera");
        }
    }

    @Test
    void xlsx_aboutAndSkippedSheets_describeTheExport() throws Exception {
        try (XSSFWorkbook workbook = read(ExportMetadataXlsxWriter.write(table, skipped, summary))) {
            Map<String, String> about = labelValues(workbook.getSheet(ExportMetadataXlsxWriter.ABOUT_SHEET));
            assertThat(about).containsEntry("Requested by", "Asha Perera")
                    .containsEntry("Requested at", "2026-03-02 10:00 UTC")
                    .containsEntry("Metadata format", "xlsx")
                    .containsEntry("Document type", "Invoice")
                    .containsEntry("Documents requested", "3")
                    .containsEntry("Documents exported", "1")
                    .containsEntry("Documents skipped", "2");

            Sheet skippedSheet = workbook.getSheet(ExportMetadataXlsxWriter.SKIPPED_SHEET);
            assertThat(skippedSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Title");
            assertThat(skippedSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("+Scan, draft");
            assertThat(skippedSheet.getRow(2).getCell(1).getStringCellValue())
                    .isEqualTo("The current version has not passed the malware scan");
        }
    }

    @Test
    void xlsx_nothingSkipped_stillHasTheSkippedSheetHeader() throws Exception {
        try (XSSFWorkbook workbook = read(ExportMetadataXlsxWriter.write(table, List.of(), summary))) {
            Sheet skippedSheet = workbook.getSheet(ExportMetadataXlsxWriter.SKIPPED_SHEET);
            assertThat(skippedSheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Reason");
            assertThat(skippedSheet.getLastRowNum()).isZero();
        }
    }

    private static XSSFWorkbook read(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static void assertDate(Cell cell, LocalDateTime expected) {
        assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(DateUtil.isCellDateFormatted(cell)).isTrue();
        assertThat(cell.getLocalDateTimeCellValue()).isEqualTo(expected);
    }

    private static Map<String, String> labelValues(Sheet sheet) {
        Map<String, String> values = new java.util.HashMap<>();
        for (Row row : sheet) {
            values.put(row.getCell(0).getStringCellValue(), row.getCell(1).getStringCellValue());
        }
        return values;
    }
}
