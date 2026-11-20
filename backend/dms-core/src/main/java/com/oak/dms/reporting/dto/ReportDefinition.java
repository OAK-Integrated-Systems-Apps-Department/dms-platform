package com.oak.dms.reporting.dto;

import java.util.List;

/**
 * What a user builds: which documents (filters, ANDed), which columns in what order, up to 3 sort keys and at most one
 * group-by. Stored as a template and snapshotted on every run. Everything here is a name looked up in the field catalogue -
 * nothing is SQL.
 *
 * @param source          only {@code documents} in v1; null means documents
 * @param includeInactive null = the tenant's {@code includeInactiveByDefault}
 * @param timeZone        IANA zone relative dates are resolved in; null = the tenant's default
 */
public record ReportDefinition(
        String source,
        List<Filter> filters,
        List<String> columns,
        List<Sort> sort,
        String groupBy,
        Boolean includeInactive,
        String timeZone) {

    /**
     * @param field    a catalogue key, e.g. {@code status} or {@code meta.contractValue}
     * @param operator an {@link com.oak.dms.reporting.catalog.Operator#key()}
     * @param values   several values inside one filter are ORed; dates are ISO {@code yyyy-MM-dd}; a relative date is e.g.
     *                 {@code last_7_days}
     */
    public record Filter(String field, String operator, List<String> values) {
    }

    /** @param direction {@code asc} (default) or {@code desc} */
    public record Sort(String field, String direction) {
    }

    public List<Filter> filtersOrEmpty() {
        return filters == null ? List.of() : filters;
    }

    public List<String> columnsOrEmpty() {
        return columns == null ? List.of() : columns;
    }

    public List<Sort> sortOrEmpty() {
        return sort == null ? List.of() : sort;
    }
}
