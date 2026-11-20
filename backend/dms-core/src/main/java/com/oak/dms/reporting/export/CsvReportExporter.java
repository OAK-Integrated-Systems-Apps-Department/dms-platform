package com.oak.dms.reporting.export;

import com.oak.dms.reporting.datasource.ReportQuery.Column;
import com.oak.dms.reporting.datasource.ReportRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV (UTF-8; delimiter and byte-order mark from the organisation's settings). Streams: one row in, one record out.
 * <p>
 * Layout: the header block (title, generated-at, filters, row count, notices) one value per line, a blank line, the column
 * headings, then the rows - with a group line above each group and a total line at the end for a grouped report.
 * Text cells go through {@link CellSanitizer}; numbers are written plainly and unformatted so they stay machine-readable.
 */
@Component
public class CsvReportExporter implements ReportExporter {

    private static final String BOM = "﻿";

    @Override
    public String format() {
        return "csv";
    }

    @Override
    public String contentType() {
        return "text/csv; charset=UTF-8";
    }

    @Override
    public String extension() {
        return "csv";
    }

    @Override
    public void write(ReportFileSpec spec, ReportFileSpec.RowCursor rows, Path target) throws IOException {
        String delimiter = spec.settings().csvDelimiter() == null || spec.settings().csvDelimiter().isEmpty()
                ? "," : spec.settings().csvDelimiter();
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setRecordSeparator("\r\n").get();
        try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8);
             CSVPrinter csv = new CSVPrinter(out, format)) {
            if (spec.settings().csvIncludeBom()) {
                out.write(BOM);
            }
            for (String line : spec.headerLines()) {
                csv.printRecord(CellSanitizer.sanitize(line));
            }
            csv.println();
            List<String> headings = new ArrayList<>();
            for (Column column : spec.columns()) {
                headings.add(CellSanitizer.sanitize(column.label()));
            }
            csv.printRecord(headings);

            String currentGroup = null;
            ReportRow row;
            while ((row = rows.next()) != null) {
                if (spec.groupColumn() != null) {
                    String group = spec.groupOf(row);
                    if (!group.equals(currentGroup)) {
                        csv.printRecord(CellSanitizer.sanitize(spec.groupHeader(group)));
                        currentGroup = group;
                    }
                }
                List<String> cells = new ArrayList<>(spec.columns().size());
                for (int i = 0; i < spec.columns().size(); i++) {
                    cells.add(cell(spec, spec.columns().get(i), row.cells().get(i)));
                }
                csv.printRecord(cells);
            }
            if (spec.groupColumn() != null) {
                csv.printRecord(CellSanitizer.sanitize(spec.totalLine()));
            }
        }
    }

    private static String cell(ReportFileSpec spec, Column column, Object value) {
        if (value instanceof BigDecimal n) {
            return n.toPlainString();
        }
        String text = spec.formatter().display(column, value);
        return value instanceof String ? CellSanitizer.sanitize(text) : text;
    }
}
