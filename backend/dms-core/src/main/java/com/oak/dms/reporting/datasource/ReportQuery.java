package com.oak.dms.reporting.datasource;

import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.catalog.FieldType;

import java.util.List;
import java.util.Map;

/**
 * A parameterised query ready to execute, plus what the caller needs to present its result. Built only by
 * {@link DocumentReportQueryBuilder}; preview and generation run the same {@link #sql()}, so what a user previews is what
 * they get.
 *
 * @param sql             rows: {@code doc_id}, then one {@code cN} alias per column in the order requested
 * @param countSql        how many rows {@link #sql()} returns - visible rows only, through the same predicate
 * @param groupCountSql   {@code g}, {@code n} per group over the same rows; null when the report is not grouped
 * @param groupColumn     the group-by field, selected as {@code grp} on every row (it need not be one of the columns); null if ungrouped
 * @param params          named parameters shared by all three statements
 * @param resolvedFilters human-readable filters with relative dates resolved to the absolute range, for the report header
 * @param limitedToAccess whether the caller's access narrows the result (shown as a notice - never a count of hidden rows)
 */
public record ReportQuery(
        String sql,
        String countSql,
        String groupCountSql,
        Map<String, Object> params,
        List<Column> columns,
        String groupByKey,
        Column groupColumn,
        List<String> resolvedFilters,
        boolean limitedToAccess,
        boolean includeInactive) {

    /**
     * @param kind  how the cell is read from the result set (a timestamp, a date, a number...)
     * @param multi a multi-select custom field: the cell holds a JSON list
     * @param alias the column alias in {@link #sql()}, e.g. {@code c0}
     */
    public record Column(String key, String label, FieldType type, FieldDescriptor.ValueKind kind, boolean multi,
                         FieldDescriptor.Special special, String alias) {

        public Column withAlias(String other) {
            return new Column(key, label, type, kind, multi, special, other);
        }

        /** A custom user-reference field stores an id that is shown as a name. */
        public boolean isMetadataReference() {
            return type == FieldType.REFERENCE && key.startsWith(FieldDescriptor.META_PREFIX);
        }
    }
}
