package com.oak.dms.importexport.template;

import com.oak.dms.importexport.enums.TemplateFormat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An uploaded import template, read but not yet validated.
 *
 * @param headers the columns as found (reserved columns plus field keys)
 * @param rows    non-blank data rows, in file order
 */
public record ParsedTemplate(
        UUID documentTypeId,
        int schemaVersion,
        TemplateFormat format,
        List<String> headers,
        List<Row> rows
) {

    /**
     * @param rowNumber the row as the user sees it - the header is row 1
     * @param cells     column header -> trimmed cell text; blank cells are absent
     */
    public record Row(int rowNumber, Map<String, String> cells) {

        public String cell(String header) {
            return cells.get(header);
        }
    }
}
