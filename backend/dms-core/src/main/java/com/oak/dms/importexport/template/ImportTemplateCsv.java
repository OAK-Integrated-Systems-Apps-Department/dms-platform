package com.oak.dms.importexport.template;

import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedTemplate;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.PrefilledRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.TemplateLayout;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The CSV form of the bulk import template - same columns and same
 * {@link ParsedTemplate} as {@link ImportTemplateWorkbook}, for people who
 * work in a text editor or another spreadsheet tool. Layout:
 * <pre>
 *   # dms-import-template documentTypeId=&lt;uuid&gt; schemaVersion=&lt;n&gt;
 *   # one guidance line per column
 *   filename,title,&lt;field_key&gt;,...      &lt;- header: column keys, not labels
 *   rows...
 * </pre>
 * {@code #} lines are recognised by the first parsed cell, so a spreadsheet
 * app that re-quotes them on save doesn't break the marker. A header cell
 * starting with {@code #} (the failed-rows report's problems column) is
 * ignored on upload.
 */
@Component
public class ImportTemplateCsv {

    static final String MARKER = "# dms-import-template";
    /** Header of the failed-rows report's extra column; never read back. */
    public static final String PROBLEMS_HEADER = "#problems";
    /** Comment carrying the column keys when a saved template renames the header row. */
    static final String COLUMNS_PREFIX = "columns: ";

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    /** Writes {@code #} guidance lines as plain comments; parsing doesn't use a comment marker (see class doc). */
    private static final CSVFormat WRITE_FORMAT = CSVFormat.DEFAULT.builder().setCommentMarker('#').get();

    public byte[] generate(DocumentTypeSchemaResponse schema, List<PrefilledRow> rows) {
        return generate(schema, ImportTemplateWorkbook.standard(schema), rows);
    }

    /**
     * {@code layout}'s columns. A saved template's custom headers become the
     * header row, so the real column keys go in a {@code # columns:} line
     * above it (parsing prefers that line to the header row).
     */
    public byte[] generate(DocumentTypeSchemaResponse schema, TemplateLayout layout, List<PrefilledRow> rows) {
        List<SchemaField> fields = layout.fields();
        boolean customHeaders = layout.importTemplateId() != null;
        List<String> keys = new ArrayList<>();
        keys.add(ImportTemplateWorkbook.FILENAME_KEY);
        keys.add(ImportTemplateWorkbook.TITLE_KEY);
        fields.forEach(f -> keys.add(f.fieldKey()));
        boolean withProblems = !rows.isEmpty();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // BOM: without it Excel opens a UTF-8 CSV in the local code page.
        out.writeBytes(UTF8_BOM);
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, WRITE_FORMAT)) {
            printer.printComment(MARKER.substring(2) + " documentTypeId=" + schema.documentTypeId()
                    + " schemaVersion=" + schema.schemaVersion()
                    + (layout.importTemplateId() != null ? " importTemplateId=" + layout.importTemplateId() : ""));
            printer.printComment("Keep the # lines and the header row as they are. One row per file.");
            printer.printComment("filename: required - the file's name including its extension");
            printer.printComment("title: optional - " + ImportTemplateWorkbook.TITLE_COMMENT);
            for (SchemaField field : fields) {
                printer.printComment(field.fieldKey() + ": " + layout.header(field)
                        + (field.mandatory() ? " - required" : " - optional") + hint(field));
            }
            if (customHeaders) {
                printer.printComment(COLUMNS_PREFIX + String.join("|", keys));
            }
            List<String> header = new ArrayList<>(keys);
            if (customHeaders) {
                header.clear();
                header.add(ImportTemplateWorkbook.FILENAME_KEY);
                header.add(ImportTemplateWorkbook.TITLE_KEY);
                fields.forEach(f -> header.add(layout.header(f)));
            }
            if (withProblems) {
                header.add(PROBLEMS_HEADER);
            }
            printer.printRecord(header);
            for (PrefilledRow row : rows) {
                List<String> values = new ArrayList<>();
                values.add(nullToEmpty(row.filename()));
                values.add(nullToEmpty(row.title()));
                for (int c = 2; c < keys.size(); c++) {
                    values.add(nullToEmpty(row.fields().get(keys.get(c))));
                }
                values.add(nullToEmpty(row.problems()));
                printer.printRecord(values);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate CSV import template", e);
        }
        return out.toByteArray();
    }

    /** @see ImportTemplateWorkbook#parse - same contract, CSV input. */
    public ParsedTemplate parse(InputStream content, int maxRows) {
        try (Reader reader = new InputStreamReader(skipBom(content), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT)) {
            UUID documentTypeId = null;
            Integer schemaVersion = null;
            UUID importTemplateId = null;
            List<String> declaredKeys = null;
            List<String> columnKeys = null;
            List<ParsedRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                String first = record.size() > 0 ? record.get(0).trim() : "";
                if (first.startsWith("#")) {
                    if (first.startsWith(MARKER)) {
                        for (String part : first.substring(MARKER.length()).trim().split("\\s+")) {
                            if (part.startsWith("documentTypeId=")) {
                                documentTypeId = UUID.fromString(part.substring("documentTypeId=".length()));
                            } else if (part.startsWith("schemaVersion=")) {
                                schemaVersion = Integer.parseInt(part.substring("schemaVersion=".length()));
                            } else if (part.startsWith("importTemplateId=")) {
                                importTemplateId = UUID.fromString(part.substring("importTemplateId=".length()));
                            }
                        }
                    }
                    if (first.startsWith("# " + COLUMNS_PREFIX)) {
                        declaredKeys = List.of(first.substring(("# " + COLUMNS_PREFIX).length()).trim().split("\\|"));
                    }
                    continue;
                }
                if (columnKeys == null) {
                    if (blank(record)) {
                        continue;
                    }
                    columnKeys = new ArrayList<>();
                    for (String key : record) {
                        columnKeys.add(key.trim());
                    }
                    if (declaredKeys != null) {
                        // Custom headers: the keys line says what each column is.
                        List<String> headers = columnKeys;
                        columnKeys = new ArrayList<>(declaredKeys);
                        for (int c = declaredKeys.size(); c < headers.size(); c++) {
                            columnKeys.add(headers.get(c)); // e.g. the #problems column
                        }
                    }
                    checkHeader(columnKeys);
                    continue;
                }
                Map<String, String> fieldValues = new LinkedHashMap<>();
                String filename = null;
                String title = null;
                for (int c = 0; c < columnKeys.size() && c < record.size(); c++) {
                    String key = columnKeys.get(c);
                    String value = record.get(c).trim();
                    if (value.isEmpty() || key.isEmpty() || key.startsWith("#")) {
                        continue;
                    }
                    if (c == 0) {
                        filename = value;
                    } else if (c == 1) {
                        title = value;
                    } else {
                        fieldValues.put(key, value);
                    }
                }
                if (filename == null && title == null && fieldValues.isEmpty()) {
                    continue;
                }
                if (rows.size() == maxRows) {
                    throw new ImportBatchSizeException("IMPORT_ROW_COUNT_INVALID",
                            "The template has more than " + maxRows + " rows; split it into smaller imports.");
                }
                rows.add(new ParsedRow((int) record.getRecordNumber(), filename != null ? filename : "", title,
                        fieldValues));
            }
            if (documentTypeId == null || schemaVersion == null || columnKeys == null) {
                throw ImportTemplateWorkbook.invalid();
            }
            List<String> keys = columnKeys.stream().filter(k -> !k.isEmpty() && !k.startsWith("#")).toList();
            return new ParsedTemplate(documentTypeId, schemaVersion, keys, List.copyOf(rows), importTemplateId);
        } catch (ImportBatchSizeException | InvalidImportTemplateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Unreadable CSV or bad marker values - the same thing to the user.
            throw ImportTemplateWorkbook.invalid();
        }
    }

    private static void checkHeader(List<String> keys) {
        if (keys.size() < 2 || !ImportTemplateWorkbook.FILENAME_KEY.equals(keys.get(0))
                || !ImportTemplateWorkbook.TITLE_KEY.equals(keys.get(1))) {
            throw ImportTemplateWorkbook.invalid();
        }
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            if (!key.isEmpty() && !seen.add(key)) {
                throw ImportTemplateWorkbook.invalid();
            }
        }
    }

    private static String hint(SchemaField field) {
        List<MetadataFieldOptionResponse> options = field.options() != null ? field.options() : List.of();
        return switch (field.dataType()) {
            case "date" -> " - " + ImportTemplateWorkbook.DATE_HINT;
            case "datetime" -> " - " + ImportTemplateWorkbook.DATETIME_HINT;
            case "boolean" -> " - true or false";
            case "number", "decimal" -> " - a number with . as the decimal point";
            case "user_ref" -> " - the user's email address";
            case "dropdown" -> " - one of: " + options.stream().map(MetadataFieldOptionResponse::optionValue)
                    .collect(Collectors.joining(" / "));
            case "multi_select" -> " - one or more of (separate with ;): "
                    + options.stream().map(MetadataFieldOptionResponse::optionValue).collect(Collectors.joining(" / "));
            default -> "";
        };
    }

    private static boolean blank(CSVRecord record) {
        for (String value : record) {
            if (!value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static InputStream skipBom(InputStream in) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(in, UTF8_BOM.length);
        byte[] head = pushback.readNBytes(UTF8_BOM.length);
        boolean bom = head.length == UTF8_BOM.length && head[0] == UTF8_BOM[0] && head[1] == UTF8_BOM[1]
                && head[2] == UTF8_BOM[2];
        if (!bom && head.length > 0) {
            pushback.unread(head);
        }
        return pushback;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
