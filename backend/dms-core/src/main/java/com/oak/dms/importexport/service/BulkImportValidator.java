package com.oak.dms.importexport.service;

import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.template.ParsedTemplate;
import com.oak.dms.importexport.template.RowValueConverter;
import com.oak.dms.importexport.template.TemplateColumns;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks an uploaded template against the document type's current schema,
 * row by row, before anything is imported. Template-level problems (wrong
 * type, outdated version, unknown columns) throw; row-level problems come
 * back per row - one bad row never stops the others (row-level atomicity).
 * Needs {@link TenantContext}.
 */
@Component
@RequiredArgsConstructor
public class BulkImportValidator {

    public static final int MAX_TITLE_LENGTH = 500;

    private final RowValueConverter rowValueConverter;
    private final MetadataValidationService metadataValidationService;

    /**
     * @param reference the row's file name cell (normalized), null when blank
     * @param errors    "column: reason" lines; empty when the row can be imported
     */
    public record RowResult(ParsedTemplate.Row row, String reference, List<String> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** Throws when the template as a whole can't be used with this schema. */
    public void checkTemplate(ParsedTemplate template, DocumentTypeSchemaResponse schema) {
        if (template.schemaVersion() != schema.schemaVersion()) {
            throw new BulkImportException(HttpStatus.CONFLICT, "TEMPLATE_OUTDATED",
                    "This template was made for version " + template.schemaVersion() + " of \"" + schema.typeName()
                            + "\", but its fields have changed since (now version " + schema.schemaVersion()
                            + "). Download a new template and copy your rows into it.");
        }
        Set<String> known = new HashSet<>(TemplateColumns.headers(TemplateColumns.importableFields(schema)));
        known.add(TemplateColumns.ERRORS);
        List<String> unknown = template.headers().stream()
                .filter(h -> !h.isEmpty() && !known.contains(h))
                .toList();
        if (!unknown.isEmpty()) {
            throw BulkImportException.badRequest("UNKNOWN_COLUMNS", "The template has columns this document type "
                    + "doesn't have: " + String.join(", ", unknown) + ". Download a fresh template and try again.");
        }
        if (template.rows().isEmpty()) {
            throw BulkImportException.badRequest("EMPTY_TEMPLATE", "The template has no rows to import.");
        }
    }

    /** @param files the batch's files, or null to skip file matching (preview) */
    public List<RowResult> validateRows(ParsedTemplate template, DocumentTypeSchemaResponse schema, ImportFiles files) {
        List<SchemaField> fields = TemplateColumns.importableFields(schema);
        Map<String, Integer> firstRowByReference = new HashMap<>();
        List<RowResult> results = new ArrayList<>();
        for (ParsedTemplate.Row row : template.rows()) {
            List<String> errors = new ArrayList<>(validateRow(row, schema, fields));
            String reference = row.cell(TemplateColumns.FILENAME) == null
                    ? null
                    : ImportFiles.normalize(row.cell(TemplateColumns.FILENAME));
            if (reference != null && !reference.isEmpty()) {
                Integer earlier = firstRowByReference.putIfAbsent(reference.toLowerCase(Locale.ROOT), row.rowNumber());
                if (earlier != null) {
                    errors.add(TemplateColumns.FILENAME + ": the same file is already listed on row " + earlier);
                } else if (files != null) {
                    ImportFiles.Resolution resolution = files.resolve(reference);
                    if (resolution instanceof ImportFiles.NotFound) {
                        errors.add(TemplateColumns.FILENAME + ": no uploaded file is called \"" + reference + "\"");
                    } else if (resolution instanceof ImportFiles.Ambiguous ambiguous) {
                        errors.add(TemplateColumns.FILENAME + ": " + ambiguous.matches() + " files in the ZIP are called \""
                                + reference + "\" - give its folder too, e.g. folder/" + reference);
                    }
                }
            }
            results.add(new RowResult(row, reference, errors));
        }
        return results;
    }

    /** Everything about one row except its file: required cells, data types, the field's rules. */
    public List<String> validateRow(ParsedTemplate.Row row, DocumentTypeSchemaResponse schema, List<SchemaField> fields) {
        List<String> errors = new ArrayList<>();
        String reference = row.cell(TemplateColumns.FILENAME);
        if (reference == null || ImportFiles.normalize(reference).isEmpty()) {
            errors.add(TemplateColumns.FILENAME + ": required");
        }
        String title = row.cell(TemplateColumns.TITLE);
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            errors.add(TemplateColumns.TITLE + ": at most " + MAX_TITLE_LENGTH + " characters");
        }
        RowValueConverter.Converted converted = rowValueConverter.convert(fields, row.cells(), TenantContext.getTenantId());
        errors.addAll(converted.errors());

        // Fields that failed conversion are left out, so they don't also get
        // a misleading "required" error from the engine.
        Set<String> unconvertible = new HashSet<>();
        converted.errors().forEach(e -> unconvertible.add(e.substring(0, e.indexOf(':'))));
        for (ValidationError error : metadataValidationService.validate(schema.documentTypeId(), schema.schemaVersion(),
                converted.forValidation())) {
            if ("error".equals(error.severity()) && !unconvertible.contains(error.fieldKey())) {
                errors.add(error.fieldKey() + ": " + error.message());
            }
        }
        return errors;
    }

    /** The row's title: its title cell, else its file name without the extension. */
    public static String titleFor(Map<String, String> cells) {
        String title = cells.get(TemplateColumns.TITLE);
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        String name = ImportFiles.baseName(ImportFiles.normalize(cells.getOrDefault(TemplateColumns.FILENAME, "")));
        int dot = name.lastIndexOf('.');
        String derived = dot > 0 ? name.substring(0, dot) : name;
        derived = derived.isBlank() ? name : derived;
        return derived.length() > MAX_TITLE_LENGTH ? derived.substring(0, MAX_TITLE_LENGTH) : derived;
    }
}
