package com.oak.dms.importexport.template;

import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Generates and parses the .xlsx bulk import template for one document type
 * at one schema version. Pure POI - no repository or tenant access - so the
 * generate/parse round trip is unit testable on its own.
 *
 * <p>The visible {@code Import} sheet is for people: its headers are field
 * labels and may change wording at any time. Parsing never reads them;
 * instead the very hidden {@code _meta} sheet records the document type,
 * schema version, and the key of every column in order:
 * <pre>
 *   A1 documentTypeId   B1 &lt;uuid&gt;
 *   A2 schemaVersion    B2 &lt;int&gt;
 *   A3.. column keys: filename, title, then each field_key
 *   C1 resubmitOf       D1 &lt;uuid&gt;   (failed-rows templates only)
 * </pre>
 * Dropdown option lists live on a separate very hidden {@code _options}
 * sheet (one column per dropdown field), since an inline list constraint
 * is capped at 255 characters.
 *
 * <p>A failed-rows template (see {@link TemplateContent}) is the same
 * workbook with rows already filled in, a final "Import errors" column
 * keyed {@link #ERRORS_KEY} that {@link #parse} always ignores, and the
 * batch it came from in {@code _meta}. A normal template has none of these
 * and parses exactly as before.
 */
@Component
public class ImportTemplateWorkbook {

    public static final String IMPORT_SHEET = "Import";
    public static final String META_SHEET = "_meta";
    public static final String OPTIONS_SHEET = "_options";

    public static final String FILENAME_KEY = "filename";
    public static final String TITLE_KEY = "title";
    /**
     * Reserved key of the "Import errors" column. Field keys always start
     * with a letter, so it can't collide with one.
     */
    public static final String ERRORS_KEY = "_import_errors";

    private static final String DOCUMENT_TYPE_ID_LABEL = "documentTypeId";
    private static final String SCHEMA_VERSION_LABEL = "schemaVersion";
    private static final String RESUBMIT_OF_LABEL = "resubmitOf";
    private static final int RESUBMIT_OF_LABEL_COLUMN = 2;
    private static final int FIRST_COLUMN_KEY_ROW = 2;
    private static final int COLUMN_WIDTH = 24 * 256;

    private static final String DROPDOWN = "dropdown";
    private static final String MULTI_SELECT = "multi_select";
    private static final String DATE = "date";
    private static final String DATETIME = "datetime";
    private static final Set<String> NUMBER_TYPES = Set.of("number", "decimal");

    /** Excel number formats given to date/datetime data cells, so typed dates become real dates. */
    static final String DATE_FORMAT = "yyyy-mm-dd";
    static final String DATETIME_FORMAT = "yyyy-mm-dd hh:mm";

    static final String TITLE_COMMENT = "Leave blank to use the file name (without its extension).";
    static final String DATE_HINT = "Enter as a date, e.g. 2026-09-30";
    static final String DATETIME_HINT = "Enter as a date and time, e.g. 2026-09-30 14:30";
    static final String ERRORS_HEADER = "Import errors";
    static final String ERRORS_COMMENT = "Why this row failed last time. Ignored when you upload the file - "
            + "you can leave it or delete it.";

    public record ParsedRow(int rowNumber, String filename, String title, Map<String, String> fields) {
    }

    /**
     * @param columnKeys the data columns in order - never {@link #ERRORS_KEY}
     * @param resubmitOf the batch a failed-rows template came from; null for
     *                   a normal template
     */
    public record ParsedTemplate(UUID documentTypeId, int schemaVersion, List<String> columnKeys, List<ParsedRow> rows,
                                 UUID resubmitOf) {
    }

    /**
     * A row written into the template. Field values are keyed by field_key;
     * keys with no column in the template are dropped.
     *
     * @param errors the "Import errors" cell - only written when the
     *               template has that column
     */
    public record PrefilledRow(String filename, String title, Map<String, String> fields, String errors) {
    }

    /**
     * What goes into a template beyond its columns.
     *
     * @param resubmitOf   recorded in {@code _meta}; null for none
     * @param rows         written from the first data row down, in order
     * @param errorsColumn add the final "Import errors" column
     */
    public record TemplateContent(UUID resubmitOf, List<PrefilledRow> rows, boolean errorsColumn) {
        public static final TemplateContent NONE = new TemplateContent(null, List.of(), false);
    }

    /** A normal, empty template. */
    public byte[] generate(DocumentTypeSchemaResponse schema, int maxRows) {
        return generate(schema, maxRows, TemplateContent.NONE);
    }

    /**
     * @param maxRows how many data rows the dropdown validations and date
     *                formats cover - the batch row limit, so every usable
     *                row gets them
     */
    public byte[] generate(DocumentTypeSchemaResponse schema, int maxRows, TemplateContent content) {
        List<SchemaField> fields = schema.fields().stream()
                .filter(f -> !f.hidden())
                .sorted((a, b) -> Integer.compare(a.displayOrder(), b.displayOrder()))
                .toList();

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet importSheet = workbook.createSheet(IMPORT_SHEET);
            Sheet metaSheet = workbook.createSheet(META_SHEET);
            Sheet optionsSheet = null;

            CreationHelper factory = workbook.getCreationHelper();
            Drawing<?> drawing = importSheet.createDrawingPatriarch();
            DataValidationHelper validationHelper = importSheet.getDataValidationHelper();
            CellStyle headerStyle = headerStyle(workbook);

            Row header = importSheet.createRow(0);
            List<String> columnKeys = new ArrayList<>();

            writeHeader(header, 0, "Filename", headerStyle);
            addComment(drawing, factory, header.getCell(0),
                    "Name of the file to upload for this row, including its extension.");
            columnKeys.add(FILENAME_KEY);
            writeHeader(header, 1, "Title", headerStyle);
            addComment(drawing, factory, header.getCell(1), TITLE_COMMENT);
            columnKeys.add(TITLE_KEY);

            CellStyle dateStyle = dataFormatStyle(workbook, DATE_FORMAT);
            CellStyle dateTimeStyle = dataFormatStyle(workbook, DATETIME_FORMAT);
            Map<Integer, CellStyle> dateColumns = new LinkedHashMap<>();
            Set<Integer> numberColumns = new HashSet<>();

            int optionsColumn = 0;
            for (int i = 0; i < fields.size(); i++) {
                SchemaField field = fields.get(i);
                int column = i + 2;
                writeHeader(header, column, field.mandatory() ? field.label() + " *" : field.label(), headerStyle);
                columnKeys.add(field.fieldKey());

                String help = field.helpText() != null ? field.helpText().trim() : "";
                if (MULTI_SELECT.equals(field.dataType())) {
                    help = (help.isEmpty() ? "" : help + "\n") + "Separate values with ;";
                } else if (DATE.equals(field.dataType())) {
                    help = (help.isEmpty() ? "" : help + "\n") + DATE_HINT;
                    dateColumns.put(column, dateStyle);
                } else if (DATETIME.equals(field.dataType())) {
                    help = (help.isEmpty() ? "" : help + "\n") + DATETIME_HINT;
                    dateColumns.put(column, dateTimeStyle);
                } else if (NUMBER_TYPES.contains(field.dataType())) {
                    numberColumns.add(column);
                }
                if (!help.isEmpty()) {
                    addComment(drawing, factory, header.getCell(column), help);
                }

                List<MetadataFieldOptionResponse> options = field.options() != null ? field.options() : List.of();
                if (DROPDOWN.equals(field.dataType()) && !options.isEmpty()) {
                    if (optionsSheet == null) {
                        optionsSheet = workbook.createSheet(OPTIONS_SHEET);
                    }
                    for (int o = 0; o < options.size(); o++) {
                        Row optionRow = optionsSheet.getRow(o) != null ? optionsSheet.getRow(o) : optionsSheet.createRow(o);
                        optionRow.createCell(optionsColumn).setCellValue(options.get(o).optionValue());
                    }
                    String columnLetter = CellReference.convertNumToColString(optionsColumn);
                    DataValidationConstraint constraint = validationHelper.createFormulaListConstraint(
                            "'" + OPTIONS_SHEET + "'!$" + columnLetter + "$1:$" + columnLetter + "$" + options.size());
                    DataValidation validation = validationHelper.createValidation(constraint,
                            new CellRangeAddressList(1, maxRows, column, column));
                    validation.setShowErrorBox(true);
                    // XSSF inverts this flag: true means "show the in-cell arrow".
                    validation.setSuppressDropDownArrow(true);
                    importSheet.addValidationData(validation);
                    optionsColumn++;
                }
            }

            CellStyle wrapStyle = null;
            if (content.errorsColumn()) {
                int column = columnKeys.size();
                writeHeader(header, column, ERRORS_HEADER, headerStyle);
                addComment(drawing, factory, header.getCell(column), ERRORS_COMMENT);
                columnKeys.add(ERRORS_KEY);
                wrapStyle = workbook.createCellStyle();
                wrapStyle.setWrapText(true);
            }

            // Pre-formatted empty cells: Excel then stores what's typed into
            // them as a date rather than text. They stay blank, so parse
            // still skips any row nobody filled in.
            if (!dateColumns.isEmpty()) {
                for (int r = 1; r <= maxRows; r++) {
                    Row row = importSheet.createRow(r);
                    dateColumns.forEach((column, style) -> row.createCell(column).setCellStyle(style));
                }
            }

            for (int i = 0; i < content.rows().size(); i++) {
                writeRow(importSheet, i + 1, content.rows().get(i), columnKeys, dateColumns, numberColumns,
                        wrapStyle);
            }

            for (int c = 0; c < columnKeys.size(); c++) {
                importSheet.setColumnWidth(c, COLUMN_WIDTH);
            }
            importSheet.createFreezePane(0, 1);

            writeMeta(metaSheet, schema, columnKeys, content.resubmitOf());
            workbook.setSheetVisibility(workbook.getSheetIndex(metaSheet), SheetVisibility.VERY_HIDDEN);
            if (optionsSheet != null) {
                workbook.setSheetVisibility(workbook.getSheetIndex(optionsSheet), SheetVisibility.VERY_HIDDEN);
            }
            workbook.setActiveSheet(0);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate import template", e);
        }
    }

    /**
     * Reads the template's {@code _meta} sheet and every non-empty data row
     * of its {@code Import} sheet. Raw values only - no field validation.
     *
     * @param maxRows the batch row limit; parsing stops, and the upload is
     *                rejected, as soon as one more non-empty row is found
     */
    public ParsedTemplate parse(InputStream content, int maxRows) {
        try (XSSFWorkbook workbook = open(content)) {
            Sheet metaSheet = workbook.getSheet(META_SHEET);
            Sheet importSheet = workbook.getSheet(IMPORT_SHEET);
            if (metaSheet == null || importSheet == null) {
                throw invalid();
            }

            UUID documentTypeId;
            int schemaVersion;
            try {
                documentTypeId = UUID.fromString(cellText(metaSheet, 0, 1));
                schemaVersion = Integer.parseInt(cellText(metaSheet, 1, 1));
            } catch (RuntimeException e) {
                throw invalid();
            }
            UUID resubmitOf = readResubmitOf(metaSheet);

            // The errors column is for people only: its key is dropped and its
            // cells are never read, so it can't fill in or keep alive a row.
            List<String> allKeys = readColumnKeys(metaSheet);
            List<String> columnKeys = new ArrayList<>(allKeys.size());
            List<Integer> columnIndexes = new ArrayList<>(allKeys.size());
            for (int c = 0; c < allKeys.size(); c++) {
                if (!ERRORS_KEY.equals(allKeys.get(c))) {
                    columnKeys.add(allKeys.get(c));
                    columnIndexes.add(c);
                }
            }
            if (columnKeys.size() < 2 || !FILENAME_KEY.equals(columnKeys.get(0))
                    || !TITLE_KEY.equals(columnKeys.get(1))) {
                throw invalid();
            }

            List<ParsedRow> rows = new ArrayList<>();
            for (int r = 1; r <= importSheet.getLastRowNum(); r++) {
                Row row = importSheet.getRow(r);
                if (row == null) {
                    continue;
                }
                List<String> values = new ArrayList<>(columnKeys.size());
                boolean empty = true;
                for (int column : columnIndexes) {
                    String value = cellValue(row.getCell(column));
                    values.add(value);
                    empty &= value == null;
                }
                if (empty) {
                    continue;
                }
                if (rows.size() == maxRows) {
                    throw new ImportBatchSizeException("IMPORT_ROW_COUNT_INVALID",
                            "The template has more than " + maxRows + " rows; split it into smaller imports.");
                }

                Map<String, String> fieldValues = new LinkedHashMap<>();
                for (int c = 2; c < columnKeys.size(); c++) {
                    if (values.get(c) != null) {
                        fieldValues.put(columnKeys.get(c), values.get(c));
                    }
                }
                rows.add(new ParsedRow(r + 1, values.get(0) != null ? values.get(0) : "", values.get(1), fieldValues));
            }
            return new ParsedTemplate(documentTypeId, schemaVersion, List.copyOf(columnKeys), List.copyOf(rows),
                    resubmitOf);
        } catch (IOException e) {
            throw invalid();
        }
    }

    private static XSSFWorkbook open(InputStream content) {
        try {
            return new XSSFWorkbook(content);
        } catch (IOException | RuntimeException e) {
            // Not a zip, not OOXML, a legacy .xls, or corrupt - all mean the
            // same thing to the user.
            throw invalid();
        }
    }

    public static InvalidImportTemplateException invalid() {
        return new InvalidImportTemplateException("IMPORT_TEMPLATE_INVALID",
                "This file isn't a valid import template. Download a fresh template and try again.");
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        return style;
    }

    private static CellStyle dataFormatStyle(XSSFWorkbook workbook, String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(format));
        return style;
    }

    private static void writeHeader(Row header, int column, String text, CellStyle style) {
        Cell cell = header.createCell(column);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private static void addComment(Drawing<?> drawing, CreationHelper factory, Cell cell, String text) {
        ClientAnchor anchor = factory.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(cell.getColumnIndex() + 3);
        anchor.setRow1(0);
        anchor.setRow2(5);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(factory.createRichTextString(text));
        cell.setCellComment(comment);
    }

    /**
     * Writes one pre-filled data row. Date and date-time values go in as
     * real dates, so they show and edit like typed ones; a value that isn't
     * ISO is kept as text, so the user still sees what was wrong. Number and
     * decimal values go in as numbers the same way, but only when
     * {@link #cellValue} reads the cell back as exactly the same string -
     * anything else ("twelve lakhs", "12.50", too many digits) stays text.
     */
    private static void writeRow(Sheet importSheet, int rowIndex, PrefilledRow prefilled, List<String> columnKeys,
                                 Map<Integer, CellStyle> dateColumns, Set<Integer> numberColumns,
                                 CellStyle wrapStyle) {
        Row row = importSheet.getRow(rowIndex) != null ? importSheet.getRow(rowIndex) : importSheet.createRow(rowIndex);
        writeText(row, 0, prefilled.filename());
        writeText(row, 1, prefilled.title());
        Map<String, String> fields = prefilled.fields() != null ? prefilled.fields() : Map.of();
        for (int c = 2; c < columnKeys.size(); c++) {
            String key = columnKeys.get(c);
            if (ERRORS_KEY.equals(key)) {
                Cell cell = writeText(row, c, prefilled.errors());
                if (cell != null) {
                    cell.setCellStyle(wrapStyle);
                }
                continue;
            }
            String value = fields.get(key);
            CellStyle dateStyle = dateColumns.get(c);
            boolean written = value != null && (dateStyle != null
                    ? writeDate(row, c, value, dateStyle)
                    : numberColumns.contains(c) && writeNumber(row, c, value));
            if (!written) {
                writeText(row, c, value);
            }
        }
    }

    private static Cell writeText(Row row, int column, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Cell cell = row.getCell(column) != null ? row.getCell(column) : row.createCell(column);
        cell.setCellValue(value);
        return cell;
    }

    private static boolean writeDate(Row row, int column, String value, CellStyle style) {
        Cell cell = row.getCell(column) != null ? row.getCell(column) : row.createCell(column);
        try {
            cell.setCellValue(LocalDate.parse(value.trim()));
        } catch (DateTimeParseException notDate) {
            try {
                cell.setCellValue(LocalDateTime.parse(value.trim()));
            } catch (DateTimeParseException notDateTime) {
                return false;
            }
        }
        cell.setCellStyle(style);
        return true;
    }

    private static boolean writeNumber(Row row, int column, String value) {
        String trimmed = value.trim();
        double number;
        try {
            // BigDecimal first: Double.parseDouble also takes "NaN", "0x1p3", "12d".
            new BigDecimal(trimmed);
            number = Double.parseDouble(trimmed);
        } catch (NumberFormatException notNumber) {
            return false;
        }
        if (!numericText(number).equals(trimmed)) {
            return false;
        }
        Cell cell = row.getCell(column) != null ? row.getCell(column) : row.createCell(column);
        cell.setCellValue(number);
        return true;
    }

    private static void writeMeta(Sheet metaSheet, DocumentTypeSchemaResponse schema, List<String> columnKeys,
                                  UUID resubmitOf) {
        Row typeRow = metaSheet.createRow(0);
        typeRow.createCell(0).setCellValue(DOCUMENT_TYPE_ID_LABEL);
        typeRow.createCell(1).setCellValue(schema.documentTypeId().toString());
        if (resubmitOf != null) {
            typeRow.createCell(RESUBMIT_OF_LABEL_COLUMN).setCellValue(RESUBMIT_OF_LABEL);
            typeRow.createCell(RESUBMIT_OF_LABEL_COLUMN + 1).setCellValue(resubmitOf.toString());
        }
        Row versionRow = metaSheet.createRow(1);
        versionRow.createCell(0).setCellValue(SCHEMA_VERSION_LABEL);
        versionRow.createCell(1).setCellValue(String.valueOf(schema.schemaVersion()));
        for (int i = 0; i < columnKeys.size(); i++) {
            metaSheet.createRow(FIRST_COLUMN_KEY_ROW + i).createCell(0).setCellValue(columnKeys.get(i));
        }
    }

    private static List<String> readColumnKeys(Sheet metaSheet) {
        List<String> keys = new ArrayList<>();
        for (int r = FIRST_COLUMN_KEY_ROW; r <= metaSheet.getLastRowNum(); r++) {
            Row row = metaSheet.getRow(r);
            String key = row != null ? cellValue(row.getCell(0)) : null;
            if (key == null) {
                break;
            }
            keys.add(key);
        }
        return keys;
    }

    /** The batch a failed-rows template came from, or null if {@code _meta} doesn't name one. */
    private static UUID readResubmitOf(Sheet metaSheet) {
        Row row = metaSheet.getRow(0);
        if (row == null || !RESUBMIT_OF_LABEL.equals(cellValue(row.getCell(RESUBMIT_OF_LABEL_COLUMN)))) {
            return null;
        }
        try {
            return UUID.fromString(cellText(metaSheet, 0, RESUBMIT_OF_LABEL_COLUMN + 1));
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static String cellText(Sheet sheet, int row, int column) {
        Row r = sheet.getRow(row);
        String value = r != null ? cellValue(r.getCell(column)) : null;
        if (value == null) {
            throw new IllegalArgumentException("Missing _meta value at row " + (row + 1));
        }
        return value;
    }

    /**
     * A cell's raw value as a string, or null if blank. Dates become ISO
     * {@code yyyy-MM-dd}, or an ISO date-time ({@code yyyy-MM-ddTHH:mm:ss})
     * when the cell holds a time other than midnight; numbers are written out in full, never in
     * exponent notation, with no trailing zeros.
     */
    static String cellValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? dateCellValue(cell.getLocalDateTimeCellValue())
                    : numericText(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static String numericText(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String dateCellValue(LocalDateTime value) {
        return value.toLocalTime().equals(LocalTime.MIDNIGHT)
                ? value.toLocalDate().toString()
                : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
