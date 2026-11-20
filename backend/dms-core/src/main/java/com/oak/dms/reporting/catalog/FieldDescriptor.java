package com.oak.dms.reporting.catalog;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One field a report can show, filter, sort or group by. The SQL expressions here are code constants for built-in fields;
 * a custom metadata field has none (the query builder derives its expression from {@link #metadataDataType} and binds the
 * field id as a parameter). A request never supplies SQL - it only names a {@link #key()} that must exist in the catalogue.
 *
 * @param selectExpr the value shown in a cell and used to sort (a name for a reference field); null for metadata fields
 * @param filterExpr what filters compare against (the id for a reference field); null for metadata fields
 * @param joins      named joins the expressions need - the builder owns the SQL of each, see {@code DocumentReportQueryBuilder}
 */
public record FieldDescriptor(
        String key,
        String label,
        FieldType type,
        ValueKind valueKind,
        boolean multi,
        String selectExpr,
        String filterExpr,
        Set<String> joins,
        boolean selectable,
        boolean filterable,
        boolean sortable,
        boolean groupable,
        boolean defaultSelected,
        Integer displayOrder,
        boolean showCode,
        UUID metadataFieldId,
        String metadataDataType,
        Special special) {

    /** What a filter value is converted to before it is bound. */
    public enum ValueKind { UUID, TEXT, NUMBER, TIMESTAMP, DATE, BOOLEAN }

    /** Fields that need more than an expression. */
    public enum Special { NONE, STAGE, FOLDER_PATH }

    public static final String META_PREFIX = "meta.";

    public boolean isMetadata() {
        return metadataFieldId != null;
    }

    public List<Operator> operators() {
        return type.operators();
    }

    /** The same field with the tenant's admin overrides applied. */
    public FieldDescriptor withSettings(String labelOverride, boolean selectable, boolean filterable, boolean sortable,
                                        boolean groupable, boolean defaultSelected, Integer displayOrder, boolean showCode) {
        return new FieldDescriptor(key, labelOverride != null && !labelOverride.isBlank() ? labelOverride : label, type,
                valueKind, multi, selectExpr, filterExpr, joins,
                this.selectable && selectable, this.filterable && filterable, this.sortable && sortable,
                this.groupable && groupable, defaultSelected, displayOrder, showCode, metadataFieldId, metadataDataType,
                special);
    }
}
