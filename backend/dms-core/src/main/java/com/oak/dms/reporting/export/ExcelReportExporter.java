package com.oak.dms.reporting.export;

import com.oak.dms.reporting.datasource.ReportQuery.Column;
import com.oak.dms.reporting.datasource.ReportRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Excel (.xlsx) through {@link SXSSFWorkbook}: only a small window of rows is ever in memory, the rest is flushed to a temporary
 * file that {@code dispose()} deletes. The data sheet is a clean table - frozen header row, filters on, widths set, dates, numbers
 * and booleans as native cells formatted with the organisation's patterns - and a second sheet, "Info", carries the title,
 * filters, generated-at, row count and notices. Text cells are sanitised.
 */
@Component
public class ExcelReportExporter implements ReportExporter {

    private static final int WINDOW = 200;
    private static final int MAX_WIDTH_CHARS = 60;
    private static final int WIDTH_SAMPLE_ROWS = 2000;

    @Override
    public String format() {
        return "xlsx";
    }

    @Override
    public String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String extension() {
        return "xlsx";
    }

    @Override
    public void write(ReportFileSpec spec, ReportFileSpec.RowCursor rows, Path target) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW);
        try {
            DataFormat formats = workbook.createDataFormat();
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle head = workbook.createCellStyle();
            head.setFont(bold);
            CellStyle groupStyle = workbook.createCellStyle();
            groupStyle.setFont(bold);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(formats.getFormat(spec.formatter().datePattern()));
            CellStyle dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(formats.getFormat(spec.formatter().dateTimePattern()));
            CellStyle integerStyle = workbook.createCellStyle();
            integerStyle.setDataFormat(formats.getFormat("#,##0"));
            CellStyle decimalStyle = workbook.createCellStyle();
            decimalStyle.setDataFormat(formats.getFormat(spec.formatter().decimalPattern()));

            SXSSFSheet sheet = workbook.createSheet("Report");
            int columns = spec.columns().size();
            int[] widths = new int[columns];
            Row header = sheet.createRow(0);
            for (int c = 0; c < columns; c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(CellSanitizer.sanitize(spec.columns().get(c).label()));
                cell.setCellStyle(head);
                widths[c] = spec.columns().get(c).label().length();
            }
            sheet.createFreezePane(0, 1);

            int rowIndex = 1;
            long written = 0;
            String currentGroup = null;
            ReportRow row;
            while ((row = rows.next()) != null) {
                if (spec.groupColumn() != null) {
                    String group = spec.groupOf(row);
                    if (!group.equals(currentGroup)) {
                        Cell cell = sheet.createRow(rowIndex++).createCell(0);
                        cell.setCellValue(CellSanitizer.sanitize(spec.groupHeader(group)));
                        cell.setCellStyle(groupStyle);
                        currentGroup = group;
                    }
                }
                Row out = sheet.createRow(rowIndex++);
                for (int c = 0; c < columns; c++) {
                    Column column = spec.columns().get(c);
                    Object value = row.cells().get(c);
                    Cell cell = out.createCell(c);
                    int length = put(spec, column, value, cell, dateStyle, dateTimeStyle, integerStyle, decimalStyle);
                    if (written < WIDTH_SAMPLE_ROWS) {
                        widths[c] = Math.max(widths[c], length);
                    }
                }
                written++;
            }
            if (spec.groupColumn() != null) {
                Cell cell = sheet.createRow(rowIndex++).createCell(0);
                cell.setCellValue(CellSanitizer.sanitize(spec.totalLine()));
                cell.setCellStyle(groupStyle);
            }
            // Widths from what was written - java.awt font metrics (autoSizeColumn) are not available on a headless server.
            for (int c = 0; c < columns; c++) {
                sheet.setColumnWidth(c, (Math.min(widths[c], MAX_WIDTH_CHARS) + 2) * 256);
            }
            if (columns > 0 && rowIndex > 1) {
                sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, columns - 1));
            }

            SXSSFSheet info = workbook.createSheet("Info");
            int r = 0;
            for (ReportFileSpec.Info item : spec.info()) {
                Row infoRow = info.createRow(r);
                if (item.label() == null) {
                    Cell only = infoRow.createCell(0);
                    only.setCellValue(CellSanitizer.sanitize(item.value()));
                    if (r == 0) {
                        only.setCellStyle(head); // the title
                    }
                } else {
                    infoRow.createCell(0).setCellValue(item.label());
                    infoRow.createCell(1).setCellValue(CellSanitizer.sanitize(item.value()));
                }
                r++;
            }
            info.setColumnWidth(0, 24 * 256);
            info.setColumnWidth(1, 70 * 256);

            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
        } finally {
            workbook.dispose(); // deletes the temporary files SXSSF spilled to
            workbook.close();
        }
    }

    /** Writes one cell as its native type; returns the length of what a reader will see, for the column width. */
    private static int put(ReportFileSpec spec, Column column, Object value, Cell cell, CellStyle dateStyle,
                           CellStyle dateTimeStyle, CellStyle integerStyle, CellStyle decimalStyle) {
        if (value == null) {
            return 0;
        }
        if (value instanceof OffsetDateTime t) {
            cell.setCellValue(t.atZoneSameInstant(spec.formatter().zone()).toLocalDateTime());
            cell.setCellStyle(dateTimeStyle);
            return spec.formatter().dateTimePattern().length();
        }
        if (value instanceof LocalDate d) {
            cell.setCellValue(d);
            cell.setCellStyle(dateStyle);
            return spec.formatter().datePattern().length();
        }
        if (value instanceof BigDecimal n) {
            cell.setCellValue(n.doubleValue());
            cell.setCellStyle(column.key().startsWith("meta.") ? decimalStyle : integerStyle);
            return n.toPlainString().length() + 2;
        }
        if (value instanceof Boolean b) {
            cell.setCellValue(b);
            return 5;
        }
        String text = CellSanitizer.sanitize(spec.formatter().display(column, value));
        cell.setCellValue(text);
        return text.length();
    }
}
