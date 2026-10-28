package com.oak.dms.importexport.template;

import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes import templates (B1) and failed-rows reports (B3) - the same
 * layout, so a corrected report can be uploaded again as a template.
 * <ul>
 *   <li><b>xlsx</b>: a "Documents" sheet (one column per field, every cell
 *   formatted as text so Excel never rewrites a date or a leading zero, and
 *   drop-down lists for option fields), a "Guidance" sheet describing each
 *   column, and a very hidden "_dms" sheet with the version marker.</li>
 *   <li><b>csv</b>: the version marker and one guidance line per column as
 *   {@code #} lines above the header.</li>
 * </ul>
 */
@Component
public class ImportTemplateWriter {

    /** How far down the xlsx drop-down lists reach. */
    private static final int VALIDATION_ROWS = 10_000;

    /** Characters that make a raw csv {@code #} line ambiguous to a CSV parser. */
    private static final Pattern CSV_UNSAFE = Pattern.compile("[\",\r\n]");

    /** One data row to write, for failed-rows reports: header -> cell text, plus why it failed. */
    public record RowOut(Map<String, String> cells, String errors) {
    }

    public byte[] writeTemplate(TemplateFormat format, DocumentTypeSchemaResponse schema) {
        return write(format, schema, List.of(), false);
    }

    public byte[] writeRows(TemplateFormat format, DocumentTypeSchemaResponse schema, List<RowOut> rows) {
        return write(format, schema, rows, true);
    }

    private byte[] write(TemplateFormat format, DocumentTypeSchemaResponse schema, List<RowOut> rows,
                         boolean withErrors) {
        List<SchemaField> fields = TemplateColumns.importableFields(schema);
        List<String> headers = new ArrayList<>(TemplateColumns.headers(fields));
        if (withErrors) {
            headers.add(TemplateColumns.ERRORS);
        }
        try {
            return format == TemplateFormat.xlsx
                    ? writeXlsx(schema, fields, headers, rows)
                    : writeCsv(schema, fields, headers, rows);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the import template", e);
        }
    }

    // ------------------------------------------------------------------ xlsx

    private byte[] writeXlsx(DocumentTypeSchemaResponse schema, List<SchemaField> fields, List<String> headers,
                             List<RowOut> rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet data = workbook.createSheet(TemplateColumns.DATA_SHEET);
            XSSFSheet guidance = workbook.createSheet(TemplateColumns.GUIDANCE_SHEET);
            XSSFSheet marker = workbook.createSheet(TemplateColumns.MARKER_SHEET);
            workbook.setSheetVisibility(workbook.getSheetIndex(marker), SheetVisibility.VERY_HIDDEN);

            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            CellStyle header = headerStyle(workbook, IndexedColors.GREY_25_PERCENT);
            CellStyle requiredHeader = headerStyle(workbook, IndexedColors.LIGHT_YELLOW);

            // Marker: what this template is for. Rows 1-3, columns A-B.
            putRow(marker, 0, "documentTypeId", schema.documentTypeId().toString());
            putRow(marker, 1, "schemaVersion", String.valueOf(schema.schemaVersion()));
            putRow(marker, 2, "typeCode", schema.typeCode());

            XSSFDrawing drawing = data.createDrawingPatriarch();
            Row headerRow = data.createRow(0);
            XSSFDataValidationHelper validations = new XSSFDataValidationHelper(data);
            int optionColumn = 2; // columns C onwards of _dms hold option lists
            for (int col = 0; col < headers.size(); col++) {
                String key = headers.get(col);
                SchemaField field = col >= 2 && col - 2 < fields.size() ? fields.get(col - 2) : null;
                boolean required = TemplateColumns.FILENAME.equals(key) || (field != null && field.mandatory());

                XSSFCell cell = (XSSFCell) headerRow.createCell(col);
                cell.setCellValue(key);
                cell.setCellStyle(required ? requiredHeader : header);
                data.setDefaultColumnStyle(col, text);
                data.setColumnWidth(col, Math.min(60, Math.max(16, key.length() + 4)) * 256);

                ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
                anchor.setCol1(col);
                anchor.setCol2(col + 3);
                anchor.setRow1(0);
                anchor.setRow2(4);
                Comment comment = drawing.createCellComment(anchor);
                comment.setString(new XSSFRichTextString(describe(key, field)));
                cell.setCellComment(comment);

                if (field != null && "dropdown".equals(field.dataType()) && !field.options().isEmpty()) {
                    List<MetadataFieldOptionResponse> options = field.options();
                    for (int i = 0; i < options.size(); i++) {
                        Row row = marker.getRow(i) != null ? marker.getRow(i) : marker.createRow(i);
                        row.createCell(optionColumn).setCellValue(options.get(i).optionValue());
                    }
                    String letter = columnLetter(optionColumn);
                    addList(data, validations, col, validations.createFormulaListConstraint(
                            "'" + TemplateColumns.MARKER_SHEET + "'!$" + letter + "$1:$" + letter + "$" + options.size()));
                    optionColumn++;
                } else if (field != null && "boolean".equals(field.dataType())) {
                    addList(data, validations, col, validations.createExplicitListConstraint(new String[]{"Yes", "No"}));
                }
            }
            data.createFreezePane(0, 1);

            for (int i = 0; i < rows.size(); i++) {
                Row row = data.createRow(i + 1);
                RowOut rowOut = rows.get(i);
                for (int col = 0; col < headers.size(); col++) {
                    String value = TemplateColumns.ERRORS.equals(headers.get(col))
                            ? rowOut.errors()
                            : rowOut.cells().get(headers.get(col));
                    if (value != null) {
                        XSSFCell cell = (XSSFCell) row.createCell(col);
                        cell.setCellStyle(text);
                        cell.setCellValue(value);
                    }
                }
            }

            writeGuidance(guidance, header, schema, fields);
            workbook.setActiveSheet(0);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void writeGuidance(XSSFSheet sheet, CellStyle header, DocumentTypeSchemaResponse schema,
                                      List<SchemaField> fields) {
        String[] titles = {"Column", "Label", "Required", "What to enter", "Rules", "Help"};
        Row intro = sheet.createRow(0);
        intro.createCell(0).setCellValue("Import template for \"" + schema.typeName() + "\" (schema version "
                + schema.schemaVersion() + "). Fill in one row per file on the Documents sheet. Don't rename, "
                + "reorder or delete columns. Yellow columns are required.");
        Row head = sheet.createRow(2);
        for (int i = 0; i < titles.length; i++) {
            head.createCell(i).setCellValue(titles[i]);
            head.getCell(i).setCellStyle(header);
        }
        int r = 3;
        r = guidanceRow(sheet, r, TemplateColumns.FILENAME, "File name", true,
                "The file's name exactly as in your ZIP or upload (a path inside the ZIP also works)", "", "");
        r = guidanceRow(sheet, r, TemplateColumns.TITLE, "Document title", false,
                "The document's title - the file name without its extension if left empty", "Up to 500 characters", "");
        for (SchemaField field : fields) {
            r = guidanceRow(sheet, r, field.fieldKey(), field.label(), field.mandatory(), whatToEnter(field),
                    rules(field.validations()), field.helpText() == null ? "" : field.helpText());
        }
        int[] widths = {24, 28, 10, 60, 36, 40};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    private static int guidanceRow(XSSFSheet sheet, int r, String column, String label, boolean required,
                                   String what, String rules, String help) {
        Row row = sheet.createRow(r);
        row.createCell(0).setCellValue(column);
        row.createCell(1).setCellValue(label);
        row.createCell(2).setCellValue(required ? "Yes" : "No");
        row.createCell(3).setCellValue(what);
        row.createCell(4).setCellValue(rules);
        row.createCell(5).setCellValue(help);
        return r + 1;
    }

    private static void addList(XSSFSheet sheet, XSSFDataValidationHelper helper, int col,
                                DataValidationConstraint constraint) {
        DataValidation validation = helper.createValidation(constraint,
                new CellRangeAddressList(1, VALIDATION_ROWS, col, col));
        validation.setShowErrorBox(true);
        validation.setSuppressDropDownArrow(true);
        sheet.addValidationData(validation);
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook, IndexedColors fill) {
        CellStyle style = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        style.setFont(bold);
        style.setFillForegroundColor(fill.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static void putRow(XSSFSheet sheet, int r, String key, String value) {
        Row row = sheet.createRow(r);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value);
    }

    private static String columnLetter(int index) {
        StringBuilder letters = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            letters.insert(0, (char) ('A' + i % 26));
        }
        return letters.toString();
    }

    // ------------------------------------------------------------------- csv

    private byte[] writeCsv(DocumentTypeSchemaResponse schema, List<SchemaField> fields, List<String> headers,
                            List<RowOut> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // BOM: without it Excel opens UTF-8 CSVs as the local code page.
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printHashLine(writer, printer, TemplateColumns.CSV_MARKER + " documentTypeId=" + schema.documentTypeId()
                    + " schemaVersion=" + schema.schemaVersion());
            printHashLine(writer, printer,
                    "# Keep these # lines. One row per file below the header. Don't rename or reorder columns.");
            printHashLine(writer, printer,
                    "# " + TemplateColumns.FILENAME + ": required - the file's name as in your ZIP or upload");
            printHashLine(writer, printer,
                    "# " + TemplateColumns.TITLE + ": optional - defaults to the file name without its extension");
            for (SchemaField field : fields) {
                String rules = rules(field.validations());
                printHashLine(writer, printer, "# " + field.fieldKey() + ": " + field.label() + " - "
                        + (field.mandatory() ? "required" : "optional") + " - " + whatToEnter(field)
                        + (rules.isEmpty() ? "" : " - " + rules));
            }
            printer.printRecord(headers);
            for (RowOut row : rows) {
                List<String> values = new ArrayList<>(headers.size());
                for (String h : headers) {
                    String value = TemplateColumns.ERRORS.equals(h) ? row.errors() : row.cells().get(h);
                    values.add(value == null ? "" : value);
                }
                printer.printRecord(values);
            }
        }
        return out.toByteArray();
    }

    /**
     * Writes a {@code #} line unquoted, so the file starts with the marker
     * itself. commons-csv's minimal quoting would wrap every value starting
     * with {@code #}. A line holding a comma, quote or line break is still
     * written as a quoted record, since raw it could open a quoted field that
     * swallows the header. The reader accepts both forms.
     */
    private static void printHashLine(Writer writer, CSVPrinter printer, String line) throws IOException {
        if (CSV_UNSAFE.matcher(line).find()) {
            printer.printRecord(line);
        } else {
            writer.write(line);
            writer.write("\r\n");
        }
    }

    // -------------------------------------------------------------- guidance

    private static String describe(String key, SchemaField field) {
        if (field == null) {
            return switch (key) {
                case TemplateColumns.FILENAME -> "Required. The file's name exactly as in your ZIP or upload.";
                case TemplateColumns.TITLE -> "Optional. Defaults to the file name without its extension.";
                default -> "Why this row failed. Ignored when the file is uploaded again.";
            };
        }
        return field.label() + (field.mandatory() ? " (required)" : " (optional)") + "\n" + whatToEnter(field);
    }

    static String whatToEnter(SchemaField field) {
        String dateFormat = field.validations().stream()
                .filter(v -> "date_format".equals(v.ruleType()))
                .map(MetadataFieldValidationResponse::ruleValue)
                .findFirst().orElse("YYYY-MM-DD");
        return switch (field.dataType()) {
            case "long_text" -> "Text (may be long)";
            case "number" -> "A whole number, e.g. 42";
            case "decimal" -> "A number, with . as the decimal point, e.g. 12.50";
            case "date" -> "A date as " + dateFormat;
            case "datetime" -> "A date and time as YYYY-MM-DDTHH:MM (UTC unless you add an offset like +05:30)";
            case "boolean" -> "Yes or No";
            case "dropdown" -> "One of: " + optionList(field);
            case "multi_select" -> "One or more of (separate with |): " + optionList(field);
            case "user_ref" -> "The user's email address";
            default -> "Text";
        };
    }

    private static String optionList(SchemaField field) {
        return field.options().stream().map(MetadataFieldOptionResponse::optionValue)
                .collect(Collectors.joining(" / "));
    }

    static String rules(List<MetadataFieldValidationResponse> validations) {
        return validations.stream()
                .filter(v -> !"date_format".equals(v.ruleType()))
                .map(v -> switch (v.ruleType()) {
                    case "min_length" -> "at least " + v.ruleValue() + " characters";
                    case "max_length" -> "at most " + v.ruleValue() + " characters";
                    case "min_value" -> "at least " + v.ruleValue();
                    case "max_value" -> "at most " + v.ruleValue();
                    case "regex" -> "must match " + v.ruleValue();
                    case "date_range" -> "between " + v.ruleValue().replace("/", " and ");
                    case "allowed_values" -> "one of " + v.ruleValue().replace(",", " /");
                    default -> v.ruleType() + " " + v.ruleValue();
                } + ("warning".equals(v.severity()) ? " (warning only)" : ""))
                .collect(Collectors.joining("; "));
    }
}
