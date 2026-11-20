package com.oak.dms.reporting.datasource;

import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.catalog.FieldDescriptor.ValueKind;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One report row: the document, one typed cell per requested column in column order (String, BigDecimal, OffsetDateTime,
 * LocalDate, Boolean or null), and - for a grouped report - the value of the group-by field. A folder-path cell holds the folder
 * id until {@code RowResolver} swaps in the visible path.
 */
public record ReportRow(UUID documentId, List<Object> cells, Object group) {

    public ReportRow(UUID documentId, List<Object> cells) {
        this(documentId, cells, null);
    }

    /** Reads the current row of a result of {@link ReportQuery#sql()}. */
    public static ReportRow read(ResultSet rs, List<ReportQuery.Column> columns, ReportQuery.Column groupColumn)
            throws SQLException {
        List<Object> cells = new ArrayList<>(columns.size());
        for (ReportQuery.Column column : columns) {
            cells.add(cell(rs, column));
        }
        Object group = groupColumn == null ? null : cell(rs, groupColumn);
        return new ReportRow(rs.getObject("doc_id", UUID.class), cells, group);
    }

    public static ReportRow read(ResultSet rs, List<ReportQuery.Column> columns) throws SQLException {
        return read(rs, columns, null);
    }

    /** One cell, read the way its column's kind says. */
    public static Object cell(ResultSet rs, ReportQuery.Column column) throws SQLException {
        if (column.special() == Special.FOLDER_PATH) {
            return rs.getObject(column.alias(), UUID.class);
        }
        ValueKind kind = column.kind();
        return switch (kind) {
            case TIMESTAMP -> rs.getObject(column.alias(), OffsetDateTime.class);
            case DATE -> rs.getObject(column.alias(), LocalDate.class);
            case NUMBER -> rs.getBigDecimal(column.alias());
            case BOOLEAN -> rs.getObject(column.alias(), Boolean.class);
            default -> rs.getString(column.alias());
        };
    }
}
