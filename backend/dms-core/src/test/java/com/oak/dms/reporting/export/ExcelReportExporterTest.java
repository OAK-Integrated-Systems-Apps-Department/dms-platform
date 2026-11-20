package com.oak.dms.reporting.export;

import com.oak.dms.reporting.datasource.ReportRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.oak.dms.reporting.export.ExporterFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class ExcelReportExporterTest {

    @TempDir
    Path dir;

    private final ExcelReportExporter exporter = new ExcelReportExporter();

    private XSSFWorkbook write(ReportFileSpec spec, List<ReportRow> rows) throws Exception {
        Path file = dir.resolve("r.xlsx");
        exporter.write(spec, cursor(rows), file);
        try (InputStream in = Files.newInputStream(file)) {
            return new XSSFWorkbook(in);
        }
    }

    @Test
    void theDataSheetHasAFrozenFilteredHeaderRow_andNativeTypedCells() throws Exception {
        ReportFileSpec spec = spec(defaults(), 2, false, null, Map.of());
        try (XSSFWorkbook book = write(spec, List.of(row("Alpha", 12, "2024-03-09T20:00:00Z", true),
                row("Beta", 1234567, "2024-03-01T00:00:00Z", false)))) {
            XSSFSheet sheet = book.getSheet("Report");

            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Title");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:D3");

            Row alpha = sheet.getRow(1);
            assertThat(alpha.getCell(0).getStringCellValue()).isEqualTo("Alpha");
            assertThat(alpha.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(alpha.getCell(1).getNumericCellValue()).isEqualTo(12.0);
            assertThat(DateUtil.isCellDateFormatted(alpha.getCell(2))).as("a real date, not text").isTrue();
            assertThat(alpha.getCell(2).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2024, 3, 10, 1, 30)); // in Colombo
            assertThat(alpha.getCell(3).getCellType()).isEqualTo(CellType.BOOLEAN);
            assertThat(alpha.getCell(3).getBooleanCellValue()).isTrue();
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(1234567.0);
            assertThat(sheet.getColumnWidth(0)).isGreaterThan(0);
        }
    }

    @Test
    void textThatCouldBeAFormulaIsQuoted() throws Exception {
        ReportFileSpec spec = spec(defaults(), 2, false, null, Map.of());
        try (XSSFWorkbook book = write(spec, List.of(row("=1+1", 1, "2024-03-09T20:00:00Z", false),
                row("@SUM(A1)", -3, "2024-03-09T20:00:00Z", false)))) {
            XSSFSheet sheet = book.getSheet("Report");

            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=1+1");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("'@SUM(A1)");
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).as("a negative number stays a number").isEqualTo(-3.0);
            assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
        }
    }

    @Test
    void theInfoSheetCarriesTitleFiltersGeneratedAtAndRowCount() throws Exception {
        ReportFileSpec spec = spec(defaults(), 1, true, null, Map.of());
        try (XSSFWorkbook book = write(spec, List.of(row("A", 1, "2024-03-09T20:00:00Z", false)))) {
            XSSFSheet info = book.getSheet("Info");
            List<String> text = new ArrayList<>();
            for (Row r : info) {
                for (Cell c : r) {
                    text.add(c.getStringCellValue());
                }
            }

            assertThat(text).contains("Contract register", "Generated at", "2024-03-10 12:00 (Asia/Colombo)", "Generated for",
                    "Alice Perera", "Filter", "Title: contains \"contract\"", "Rows", "1", "Truncated at 1 rows");
        }
    }

    @Test
    void groupedReportsGetAHeaderRowPerGroupAndATotal() throws Exception {
        ReportFileSpec spec = spec(defaults(), 2, false, STATUS, Map.of("Draft", 1L, "Published", 1L));
        try (XSSFWorkbook book = write(spec, List.of(grouped("A", 1, "2024-03-09T20:00:00Z", false, "Draft"),
                grouped("B", 1, "2024-03-09T20:00:00Z", false, "Published")))) {
            XSSFSheet sheet = book.getSheet("Report");
            List<String> first = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                first.add(sheet.getRow(i).getCell(0).getStringCellValue());
            }

            assertThat(first).containsExactly("Document status: Draft (1 document)", "A",
                    "Document status: Published (1 document)", "B", "Total: 2 documents");
        }
    }

    @Test
    void nonLatinTextIsStoredAsIs() throws Exception {
        ReportFileSpec spec = spec(defaults(), 2, false, null, Map.of());
        try (XSSFWorkbook book = write(spec, List.of(row("සිංහල", 1, "2024-03-09T20:00:00Z", false),
                row("合同 عقد", 1, "2024-03-09T20:00:00Z", false)))) {
            XSSFSheet sheet = book.getSheet("Report");

            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("සිංහල");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("合同 عقد");
        }
    }

    @Test
    void aLargeReportIsWrittenFromACursorThatCreatesEachRowOnDemand() throws Exception {
        // The exporter never asks for more than one row at a time. (The bounded-heap proof at 100k+ rows is ReportRunIT's.)
        int total = 30_000;
        int[] next = {0};
        ReportFileSpec.RowCursor generated = () -> next[0] < total
                ? row("Document " + next[0]++, 1, "2024-03-09T20:00:00Z", false) : null;
        Path file = dir.resolve("big.xlsx");

        exporter.write(spec(defaults(), total, false, null, Map.of()), generated, file);

        try (InputStream in = Files.newInputStream(file); XSSFWorkbook book = new XSSFWorkbook(in)) {
            assertThat(book.getSheet("Report").getLastRowNum()).isEqualTo(total);
        }
    }
}
