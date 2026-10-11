package com.oak.dms.importexport.export;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Writes bulk export's xlsx metadata - for people: a "Documents" sheet
 * (styled, frozen header row; real number and date cells; option labels
 * and user names), an "About this export" sheet (who asked, when, how the
 * documents were chosen, the counts) and a "Skipped" sheet (title and
 * reason for every requested document left out). Rows stream through
 * {@link SXSSFWorkbook}, so memory stays flat however many there are.
 * Cells written as text are never evaluated as formulas, so unlike the CSV
 * there is nothing to escape.
 */
public final class ExportMetadataXlsxWriter {

    public static final String METADATA_FILENAME = "metadata.xlsx";
    static final String DOCUMENTS_SHEET = "Documents";
    static final String ABOUT_SHEET = "About this export";
    static final String SKIPPED_SHEET = "Skipped";
    private static final int MIN_WIDTH_CHARS = 12;
    private static final int MAX_WIDTH_CHARS = 60;
    private static final DateTimeFormatter ABOUT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private ExportMetadataXlsxWriter() {
    }

    public static byte[] write(ExportMetadataTable table, List<SkippedDocument> skipped, ExportSummary summary) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        try {
            Styles styles = Styles.of(workbook);
            writeDocuments(workbook, styles, table);
            writeAbout(workbook, styles, summary);
            writeSkipped(workbook, styles, skipped);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the export's metadata workbook", e);
        } finally {
            try {
                workbook.close();
            } catch (IOException ignored) {
                // Only temp-file cleanup; the bytes are already written.
            }
        }
    }

    private record Styles(CellStyle header, CellStyle date, CellStyle dateTime, CellStyle label) {

        static Styles of(Workbook workbook) {
            return new Styles(headerStyle(workbook), format(workbook, "yyyy-mm-dd"),
                    format(workbook, "yyyy-mm-dd hh:mm:ss"), boldStyle(workbook));
        }

        private static CellStyle headerStyle(Workbook workbook) {
            CellStyle style = boldStyle(workbook);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            return style;
        }

        private static CellStyle boldStyle(Workbook workbook) {
            Font font = workbook.createFont();
            font.setBold(true);
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            return style;
        }

        private static CellStyle format(Workbook workbook, String pattern) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(pattern));
            return style;
        }
    }

    private static void writeDocuments(Workbook workbook, Styles styles, ExportMetadataTable table) {
        Sheet sheet = workbook.createSheet(DOCUMENTS_SHEET);
        headerRow(sheet, styles, table.headers());
        sheet.createFreezePane(0, 1);
        int rowIndex = 1;
        for (List<ExportCell> cells : table.rows()) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < cells.size(); i++) {
                write(row.createCell(i), cells.get(i), styles);
            }
        }
    }

    private static void write(Cell cell, ExportCell value, Styles styles) {
        if (value instanceof ExportCell.Text text) {
            cell.setCellValue(text.value());
        } else if (value instanceof ExportCell.Number number) {
            cell.setCellValue(number.value().doubleValue());
        } else if (value instanceof ExportCell.Date date) {
            cell.setCellValue(date.value());
            cell.setCellStyle(styles.date());
        } else if (value instanceof ExportCell.DateTime dateTime) {
            cell.setCellValue(dateTime.value());
            cell.setCellStyle(styles.dateTime());
        } else if (value instanceof ExportCell.Bool bool) {
            cell.setCellValue(bool.value());
        } else if (value instanceof ExportCell.Options options) {
            cell.setCellValue(options.labelText());
        } else if (value instanceof ExportCell.UserRef user) {
            cell.setCellValue(user.name() != null ? user.name() : user.id());
        } else {
            cell.setBlank();
        }
    }

    private static void writeAbout(Workbook workbook, Styles styles, ExportSummary summary) {
        Sheet sheet = workbook.createSheet(ABOUT_SHEET);
        AboutLines lines = new AboutLines(sheet, styles);
        lines.add("Requested by", summary.requestedBy());
        lines.add("Requested at", timestamp(summary.requestedAt()));
        lines.add("Generated at", timestamp(summary.generatedAt()));
        lines.add("Metadata format", summary.format());
        for (Map.Entry<String, String> selection : summary.selection()) {
            lines.add(selection.getKey(), selection.getValue());
        }
        lines.add("Documents requested", String.valueOf(summary.requestedCount()));
        lines.add("Documents exported", String.valueOf(summary.exportedCount()));
        lines.add("Documents skipped", String.valueOf(summary.skippedCount()));
        lines.add("Note", "Uploaded-at times are in UTC. Skipped documents, and why, are listed on the '"
                + SKIPPED_SHEET + "' sheet.");
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, MAX_WIDTH_CHARS * 256);
    }

    /** Label/value rows of the About sheet. */
    private static final class AboutLines {
        private final Sheet sheet;
        private final Styles styles;
        private int next;

        AboutLines(Sheet sheet, Styles styles) {
            this.sheet = sheet;
            this.styles = styles;
        }

        void add(String label, String value) {
            Row row = sheet.createRow(next++);
            Cell labelCell = row.createCell(0);
            labelCell.setCellValue(label);
            labelCell.setCellStyle(styles.label());
            row.createCell(1).setCellValue(value != null ? value : "");
        }
    }

    private static void writeSkipped(Workbook workbook, Styles styles, List<SkippedDocument> skipped) {
        Sheet sheet = workbook.createSheet(SKIPPED_SHEET);
        headerRow(sheet, styles, ExportMetadataCsvWriter.SKIPPED_HEADERS);
        sheet.createFreezePane(0, 1);
        int rowIndex = 1;
        for (SkippedDocument document : skipped) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(document.title());
            row.createCell(1).setCellValue(document.reason());
        }
        sheet.setColumnWidth(0, 40 * 256);
        sheet.setColumnWidth(1, MAX_WIDTH_CHARS * 256);
    }

    private static void headerRow(Sheet sheet, Styles styles, List<String> headers) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(styles.header());
            int width = Math.max(MIN_WIDTH_CHARS, Math.min(MAX_WIDTH_CHARS, headers.get(i).length() + 4));
            sheet.setColumnWidth(i, width * 256);
        }
    }

    private static String timestamp(OffsetDateTime instant) {
        return instant == null ? "" : ABOUT_TIMESTAMP.format(instant.withOffsetSameInstant(ZoneOffset.UTC));
    }
}
