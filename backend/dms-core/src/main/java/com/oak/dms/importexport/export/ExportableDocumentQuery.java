package com.oak.dms.importexport.export;

import com.oak.dms.importexport.dto.BulkExportFilters;
import com.oak.dms.importexport.dto.BulkExportSelection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Reads documents for bulk export: the picker's list, a selection's totals,
 * the IDs an export freezes, and the rows the worker exports. Plain SQL
 * through {@link NamedParameterJdbcTemplate} rather than the document
 * module's repositories: nothing there lists a tenant's documents, and the
 * list needs the current version, type name and uploader in one query. It
 * joins the surrounding transaction's connection like any JPA query.
 *
 * <p>Every query except {@link #loadForExport} is filtered by
 * {@link BulkExportAccessPolicy#documentPredicateSql()} - the same rule
 * the worker applies document by document - so a document the caller may
 * not export is never listed, counted or frozen into a job.
 */
@Repository
@RequiredArgsConstructor
public class ExportableDocumentQuery {

    /** Largest page the picker list returns, whatever is asked for. */
    public static final int MAX_PAGE_SIZE = 50;

    static final String COLUMNS = """
            select d.document_id, d.tenant_id, d.title, d.document_type_id, dt.type_name,
                   d.created_by, u.display_name, d.created_at, d.is_deleted as document_deleted,
                   v.version_id, v.version_number, v.original_filename, v.size_bytes, v.scan_status,
                   v.is_deleted as version_deleted
            """;
    /** The current version is current_version_id, else latest_version_id - as single-document download picks it. */
    static final String FROM = """
            from documents d
            left join document_versions v
                   on v.version_id = coalesce(d.current_version_id, d.latest_version_id)
                  and v.document_id = d.document_id
            left join document_types dt on dt.document_type_id = d.document_type_id
            left join users u on u.user_id = d.created_by
            """;
    static final String NEWEST_FIRST = " order by d.created_at desc, d.document_id desc";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final BulkExportAccessPolicy accessPolicy;

    /** A selection's size: how many documents, and their current versions' combined bytes. */
    public record Totals(long documentCount, long totalBytes) {
    }

    /** The exportable documents matching {@code filters}, newest first, at most {@value #MAX_PAGE_SIZE} a page. */
    public Page<ExportableDocumentRow> page(UUID tenantId, UUID userId, BulkExportFilters filters, Pageable pageable) {
        PageRequest request = PageRequest.of(pageable.getPageNumber(),
                Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE));
        Where where = where(tenantId, userId, new BulkExportSelection(null, filters));
        Long total = jdbcTemplate.queryForObject("select count(*) " + FROM + where.sql(), where.params(), Long.class);
        if (total == null || total == 0) {
            return new PageImpl<>(List.of(), request, 0);
        }
        MapSqlParameterSource params = where.params()
                .addValue("limit", request.getPageSize())
                .addValue("offset", request.getOffset());
        List<ExportableDocumentRow> rows = jdbcTemplate.query(
                COLUMNS + FROM + where.sql() + NEWEST_FIRST + " limit :limit offset :offset", params, ROW_MAPPER);
        return new PageImpl<>(rows, request, total);
    }

    public Totals totals(UUID tenantId, UUID userId, BulkExportSelection selection) {
        if (isEmptyIdList(selection)) {
            return new Totals(0, 0);
        }
        Where where = where(tenantId, userId, selection);
        return jdbcTemplate.queryForObject("select count(*) as document_count,"
                        + " coalesce(sum(v.size_bytes), 0) as total_bytes " + FROM + where.sql(), where.params(),
                (rs, rowNum) -> new Totals(rs.getLong("document_count"), rs.getLong("total_bytes")));
    }

    /** The exportable documents of {@code selection}, newest first, at most {@code limit} - what a job freezes. */
    public List<UUID> resolveIds(UUID tenantId, UUID userId, BulkExportSelection selection, int limit) {
        if (isEmptyIdList(selection)) {
            return List.of();
        }
        Where where = where(tenantId, userId, selection);
        return jdbcTemplate.queryForList("select d.document_id " + FROM + where.sql() + NEWEST_FIRST + " limit :limit",
                where.params().addValue("limit", limit), UUID.class);
    }

    /**
     * The rows of {@code documentIds} as they are now, in no particular
     * order - deliberately <b>not</b> filtered by the access rule, so the
     * worker can say why a frozen document is left out (see
     * {@link BulkExportAccessPolicy#skipReason}). A document that no
     * longer exists at all has no row.
     */
    public List<ExportableDocumentRow> loadForExport(Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(COLUMNS + FROM + " where d.document_id in (:documentIds)",
                new MapSqlParameterSource("documentIds", documentIds), ROW_MAPPER);
    }

    record Where(String sql, MapSqlParameterSource params) {
    }

    Where where(UUID tenantId, UUID userId, BulkExportSelection selection) {
        StringBuilder sql = new StringBuilder(" where ").append(accessPolicy.documentPredicateSql());
        MapSqlParameterSource params = new MapSqlParameterSource(accessPolicy.bindings(tenantId, userId));
        if (selection.documentIds() != null) {
            sql.append(" and d.document_id in (:documentIds)");
            params.addValue("documentIds", selection.documentIds());
            return new Where(sql.toString(), params);
        }
        BulkExportFilters filters = selection.filters() != null ? selection.filters() : BulkExportFilters.none();
        if (filters.documentTypeId() != null) {
            sql.append(" and d.document_type_id = :documentTypeId");
            params.addValue("documentTypeId", filters.documentTypeId());
        }
        if (filters.createdFrom() != null) {
            sql.append(" and d.created_at >= :createdFrom");
            params.addValue("createdFrom", filters.createdFrom());
        }
        if (filters.createdTo() != null) {
            sql.append(" and d.created_at < :createdTo");
            params.addValue("createdTo", filters.createdTo());
        }
        if (filters.uploadedBy() != null) {
            sql.append(" and d.created_by = :uploadedBy");
            params.addValue("uploadedBy", filters.uploadedBy());
        }
        if (filters.titleContains() != null && !filters.titleContains().isBlank()) {
            sql.append(" and lower(d.title) like :titlePattern escape '\\'");
            params.addValue("titlePattern", "%" + escapeLike(filters.titleContains().trim().toLowerCase(Locale.ROOT))
                    + "%");
        }
        if (filters.importBatchId() != null) {
            sql.append(" and exists (select 1 from import_items ii"
                    + " join import_batches ib on ib.import_batch_id = ii.import_batch_id"
                    + " where ii.document_id = d.document_id"
                    + " and ib.import_batch_id = :importBatchId and ib.tenant_id = :tenantId)");
            params.addValue("importBatchId", filters.importBatchId());
        }
        return new Where(sql.toString(), params);
    }

    /** {@code text} with LIKE's wildcards and escape character taken literally. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isEmptyIdList(BulkExportSelection selection) {
        return selection.documentIds() != null && selection.documentIds().isEmpty();
    }

    static final RowMapper<ExportableDocumentRow> ROW_MAPPER = (rs, rowNum) -> new ExportableDocumentRow(
            rs.getObject("document_id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("title"),
            rs.getObject("document_type_id", UUID.class),
            rs.getString("type_name"),
            rs.getObject("created_by", UUID.class),
            rs.getString("display_name"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getBoolean("document_deleted"),
            rs.getObject("version_id", UUID.class),
            rs.getObject("version_number", Integer.class),
            rs.getString("original_filename"),
            rs.getObject("size_bytes", Long.class),
            rs.getString("scan_status"),
            rs.getBoolean("version_deleted"));
}
