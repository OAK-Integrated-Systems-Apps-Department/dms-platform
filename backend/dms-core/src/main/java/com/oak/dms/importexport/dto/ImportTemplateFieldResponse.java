package com.oak.dms.importexport.dto;

import java.util.List;

/**
 * A field a saved template can have a column (or a default) for - the
 * template editor's field list, in form order.
 *
 * @param options the allowed values of a dropdown / multi_select field; empty otherwise
 */
public record ImportTemplateFieldResponse(
        String fieldKey,
        String label,
        String dataType,
        boolean mandatory,
        String helpText,
        List<String> options
) {
}
