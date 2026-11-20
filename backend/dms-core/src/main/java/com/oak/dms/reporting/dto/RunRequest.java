package com.oak.dms.reporting.dto;

import java.util.List;
import java.util.UUID;

/**
 * Asks for a report to be generated. Exactly one of {@code templateId} and {@code definition}; a template someone shared with the
 * caller needs only the run permission, an ad hoc definition also needs build.
 *
 * @param format          csv | xlsx | pdf - must be one the organisation has enabled
 * @param filterOverrides only with a template: filters that replace the template's filter on the same field. A locked official
 *                        template accepts them only for the fields its administrator marked editable.
 */
public record RunRequest(UUID templateId, ReportDefinition definition, String format,
                         List<ReportDefinition.Filter> filterOverrides) {

    public RunRequest(UUID templateId, ReportDefinition definition, String format) {
        this(templateId, definition, format, null);
    }
}
