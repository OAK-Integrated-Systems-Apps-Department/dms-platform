package com.oak.dms.importexport.template;

import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.importexport.exception.BulkImportException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads an uploaded import template back into rows - the inverse of
 * {@link ImportTemplateWriter}. Only the file's shape is checked here (it is
 * a DMS template, it has a version marker, a header and not too many rows);
 * whether the rows are valid is {@code BulkImportValidator}'s job.
 */
@Component
public class ImportTemplateReader {

    public ParsedTemplate read(String filename, byte[] content, int maxRows) {
        TemplateFormat format = TemplateFormat.fromFilename(filename);
        if (format == null) {
            throw BulkImportException.badRequest("INVALID_TEMPLATE", "Upload the template as an .xlsx or .csv file.");
        }
        return format == TemplateFormat.xlsx ? readXlsx(content, maxRows) : readCsv(content, maxRows);
    }

    // ------------------------------------------------------------------ xlsx

    private ParsedTemplate readXlsx(byte[] content, int maxRows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet marker = workbook.getSheet(TemplateColumns.MARKER_SHEET);
            if (marker == null) {
                throw notATemplate();
            }
            Map<String, String> markerValues = new LinkedHashMap<>();
            for (Row row : marker) {
                String key = text(row.getCell(0));
                String value = text(row.getCell(1));
                if (key != null && value != null) {
                    markerValues.put(key, value);
                }
            }
            UUID documentTypeId = parseId(markerValues.get("documentTypeId"));
            int schemaVersion = parseVersion(markerValues.get("schemaVersion"));

            Sheet data = workbook.getSheet(TemplateColumns.DATA_SHEET);
            if (data == null) {
                data = workbook.getSheetAt(0);
            }
            Row headerRow = data.getRow(0);
            if (headerRow == null) {
                throw missingHeader();
            }
            List<String> headers = new ArrayList<>();
            for (int col = 0; col < headerRow.getLastCellNum(); col++) {
                String header = text(headerRow.getCell(col));
                headers.add(header == null ? "" : header);
            }
            checkHeaders(headers);

            List<ParsedTemplate.Row> rows = new ArrayList<>();
            for (int r = 1; r <= data.getLastRowNum(); r++) {
                Row row = data.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> cells = new LinkedHashMap<>();
                for (int col = 0; col < headers.size(); col++) {
                    String value = text(row.getCell(col));
                    if (value != null && !headers.get(col).isEmpty()) {
                        cells.put(headers.get(col), value);
                    }
                }
                addRow(rows, r + 1, cells, maxRows);
            }
            return new ParsedTemplate(documentTypeId, schemaVersion, TemplateFormat.xlsx, headers, rows);
        } catch (BulkImportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Not a readable workbook: truncated, not OOXML, or refused by
            // POI's zip-bomb guard (ZipSecureFile).
            throw BulkImportException.badRequest("INVALID_TEMPLATE",
                    "The template could not be read as an Excel workbook. Download a fresh template and try again.");
        }
    }

    /** A cell as text: dates as ISO, numbers without an Excel-style exponent, blanks as null. */
    static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue() ? "Yes" : "No";
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
                    yield dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)
                            ? dateTime.toLocalDate().toString()
                            : dateTime.toString();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            default -> null;
        };
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    // ------------------------------------------------------------------- csv

    private ParsedTemplate readCsv(byte[] content, int maxRows) {
        int offset = content.length >= 3 && content[0] == (byte) 0xEF && content[1] == (byte) 0xBB
                && content[2] == (byte) 0xBF ? 3 : 0;
        try (Reader reader = new InputStreamReader(
                new ByteArrayInputStream(content, offset, content.length - offset), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT)) {
            UUID documentTypeId = null;
            Integer schemaVersion = null;
            List<String> headers = null;
            List<ParsedTemplate.Row> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                String first = record.size() > 0 ? record.get(0).trim() : "";
                // # lines: the marker and guidance. Checked on the parsed
                // first cell, so a spreadsheet app that re-quotes the line
                // when saving doesn't break it.
                if (first.startsWith("#")) {
                    if (first.startsWith(TemplateColumns.CSV_MARKER)) {
                        for (String part : first.substring(TemplateColumns.CSV_MARKER.length()).trim().split("\\s+")) {
                            if (part.startsWith("documentTypeId=")) {
                                documentTypeId = parseId(part.substring("documentTypeId=".length()));
                            } else if (part.startsWith("schemaVersion=")) {
                                schemaVersion = parseVersion(part.substring("schemaVersion=".length()));
                            }
                        }
                    }
                    continue;
                }
                if (headers == null) {
                    if (isBlank(record)) {
                        continue;
                    }
                    headers = new ArrayList<>();
                    for (String h : record) {
                        headers.add(h.trim());
                    }
                    checkHeaders(headers);
                    continue;
                }
                Map<String, String> cells = new LinkedHashMap<>();
                for (int col = 0; col < headers.size() && col < record.size(); col++) {
                    String value = record.get(col).trim();
                    if (!value.isEmpty() && !headers.get(col).isEmpty()) {
                        cells.put(headers.get(col), value);
                    }
                }
                addRow(rows, (int) record.getRecordNumber(), cells, maxRows);
            }
            if (documentTypeId == null || schemaVersion == null) {
                throw notATemplate();
            }
            if (headers == null) {
                throw missingHeader();
            }
            return new ParsedTemplate(documentTypeId, schemaVersion, TemplateFormat.csv, headers, rows);
        } catch (BulkImportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw BulkImportException.badRequest("INVALID_TEMPLATE",
                    "The template could not be read as a CSV file. Download a fresh template and try again.");
        }
    }

    private static boolean isBlank(CSVRecord record) {
        for (String value : record) {
            if (!value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- shared

    private static void addRow(List<ParsedTemplate.Row> rows, int rowNumber, Map<String, String> cells, int maxRows) {
        cells.remove(TemplateColumns.ERRORS);
        if (cells.isEmpty()) {
            return; // blank rows are skipped, not errors
        }
        if (rows.size() >= maxRows) {
            throw BulkImportException.badRequest("TOO_MANY_ROWS",
                    "A template can hold at most " + maxRows + " rows. Split it into several imports.");
        }
        rows.add(new ParsedTemplate.Row(rowNumber, cells));
    }

    private static void checkHeaders(List<String> headers) {
        Set<String> seen = new HashSet<>();
        for (String header : headers) {
            if (!header.isEmpty() && !seen.add(header)) {
                throw BulkImportException.badRequest("INVALID_TEMPLATE", "The column \"" + header
                        + "\" appears more than once. Download a fresh template and try again.");
            }
        }
        if (!seen.contains(TemplateColumns.FILENAME)) {
            throw BulkImportException.badRequest("INVALID_TEMPLATE", "The \"" + TemplateColumns.FILENAME
                    + "\" column is missing. Download a fresh template and try again.");
        }
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException e) {
            throw notATemplate();
        }
    }

    private static int parseVersion(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw notATemplate();
        }
    }

    private static BulkImportException notATemplate() {
        return BulkImportException.badRequest("NOT_A_TEMPLATE",
                "This file isn't a DMS import template (its version marker is missing). "
                        + "Download the template for your document type and fill that in.");
    }

    private static BulkImportException missingHeader() {
        return BulkImportException.badRequest("INVALID_TEMPLATE",
                "The template has no header row. Download a fresh template and try again.");
    }
}
