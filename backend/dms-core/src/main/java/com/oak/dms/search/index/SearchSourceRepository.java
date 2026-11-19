package com.oak.dms.search.index;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain-SQL reads of the source-of-truth tables the indexer needs. JDBC rather than the JPA entities: the
 * indexer is a background job with no caller, runs outside any web request, and needs a handful of columns
 * from several tables, never the entity graphs (and must not accidentally trigger access checks).
 */
@Repository
public class SearchSourceRepository {

    private final JdbcTemplate jdbc;

    public SearchSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DocRow(UUID documentId, UUID tenantId, String title, UUID documentTypeId, String documentTypeName,
                         UUID ownerId, UUID departmentId, UUID folderId, String lifecycleState, boolean legalHold,
                         boolean deleted, boolean purged, boolean template, OffsetDateTime createdAt,
                         OffsetDateTime updatedAt, UUID currentVersionId, UUID latestVersionId) {
        public UUID versionToIndex() {
            return currentVersionId != null ? currentVersionId : latestVersionId;
        }
    }

    public record VersionRow(UUID versionId, UUID documentId, UUID tenantId, int versionNumber, UUID storageLocationId,
                             String storageObjectKey, String fileName, String mimeType, long sizeBytes,
                             String scanStatus, String renditionStatus, String ocrStatus, String renditionObjectKey,
                             boolean purged) {
    }

    public record OcrRow(String status, String ref) {
    }

    public record MetadataRow(UUID documentTypeId, String fieldValuesJson) {
    }

    public record FieldDef(UUID fieldId, String key, String dataType, boolean searchable, String status) {
    }

    private static final String DOC_SQL = """
            select d.document_id, d.tenant_id, d.title, d.document_type_id, dt.type_name, d.owner_user_id,
                   d.owner_department_id, d.folder_id, d.lifecycle_state, d.legal_hold, d.is_deleted,
                   (d.purged_at is not null) as purged, d.is_template, d.created_at, d.updated_at,
                   d.current_version_id, d.latest_version_id
              from documents d
              left join document_types dt on dt.tenant_id = d.tenant_id and dt.document_type_id = d.document_type_id
             where d.tenant_id = ? and d.document_id = ?
            """;

    public Optional<DocRow> document(UUID tenantId, UUID documentId) {
        return jdbc.query(DOC_SQL, (rs, n) -> new DocRow(
                rs.getObject("document_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("title"),
                rs.getObject("document_type_id", UUID.class), rs.getString("type_name"),
                rs.getObject("owner_user_id", UUID.class), rs.getObject("owner_department_id", UUID.class),
                rs.getObject("folder_id", UUID.class), rs.getString("lifecycle_state"), rs.getBoolean("legal_hold"),
                rs.getBoolean("is_deleted"), rs.getBoolean("purged"), rs.getBoolean("is_template"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("current_version_id", UUID.class), rs.getObject("latest_version_id", UUID.class)),
                tenantId, documentId).stream().findFirst();
    }

    public Optional<VersionRow> version(UUID versionId) {
        return jdbc.query("""
                select version_id, document_id, tenant_id, version_number, storage_location_id, storage_object_key,
                       original_filename, mime_type, size_bytes, scan_status, rendition_status, ocr_status,
                       rendition_object_key, (purged_at is not null) as purged
                  from document_versions where version_id = ?
                """, (rs, n) -> new VersionRow(
                rs.getObject("version_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getInt("version_number"),
                rs.getObject("storage_location_id", UUID.class), rs.getString("storage_object_key"),
                rs.getString("original_filename"), rs.getString("mime_type"), rs.getLong("size_bytes"),
                rs.getString("scan_status"), rs.getString("rendition_status"), rs.getString("ocr_status"),
                rs.getString("rendition_object_key"), rs.getBoolean("purged")), versionId).stream().findFirst();
    }

    public Optional<OcrRow> ocr(UUID versionId) {
        return jdbc.query("select status, extracted_text_ref from ocr_outputs where version_id = ?",
                (rs, n) -> new OcrRow(rs.getString(1), rs.getString(2)), versionId).stream().findFirst();
    }

    /** Key of the completed searchable-PDF rendition (scanned files), if the OCR worker produced one. */
    public Optional<String> searchablePdfKey(UUID versionId) {
        return jdbc.query("""
                select storage_object_key from renditions
                 where version_id = ? and rendition_type = 'searchable_pdf' and status = 'completed'
                """, (rs, n) -> rs.getString(1), versionId).stream().findFirst();
    }

    public Optional<MetadataRow> metadata(UUID tenantId, UUID documentId) {
        return jdbc.query("select document_type_id, field_values::text from document_metadata where tenant_id = ? and document_id = ?",
                (rs, n) -> new MetadataRow(rs.getObject(1, UUID.class), rs.getString(2)), tenantId, documentId)
                .stream().findFirst();
    }

    /** The folder itself and every ancestor, nearest first. Depth-capped so a bad hierarchy cannot loop. */
    public List<UUID> folderAndAncestors(UUID tenantId, UUID folderId) {
        if (folderId == null) {
            return List.of();
        }
        return jdbc.query("""
                with recursive up(folder_id, parent_folder_id, depth) as (
                    select folder_id, parent_folder_id, 1 from folders where tenant_id = ? and folder_id = ?
                    union all
                    select f.folder_id, f.parent_folder_id, up.depth + 1
                      from folders f join up on f.folder_id = up.parent_folder_id
                     where f.tenant_id = ? and up.depth < 64)
                select folder_id from up order by depth
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, folderId, tenantId);
    }

    /** Searchable, non-inactive metadata field definitions of a tenant, keyed by field id. */
    public Map<UUID, FieldDef> fieldDefs(UUID tenantId) {
        Map<UUID, FieldDef> out = new HashMap<>();
        jdbc.query("""
                select field_id, field_key, data_type, is_searchable, status
                  from metadata_fields where tenant_id = ? and not is_deleted
                """, rs -> {
            UUID id = rs.getObject(1, UUID.class);
            out.put(id, new FieldDef(id, rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5)));
        }, tenantId);
        return out;
    }

    /** Option labels by field id then option value, for option-backed fields. */
    public Map<UUID, Map<String, String>> optionLabels(UUID tenantId) {
        Map<UUID, Map<String, String>> out = new HashMap<>();
        jdbc.query("select field_id, option_value, option_label from metadata_field_options where tenant_id = ? and not is_deleted",
                rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
                }, tenantId);
        return out;
    }

    public boolean tenantActive(UUID tenantId) {
        Boolean deleted = jdbc.query("select is_deleted from tenants where tenant_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, tenantId);
        return deleted != null && !deleted;
    }

    /** One page of the documents anywhere under a folder (the folder itself and all descendants), keyset-paged by id. */
    public List<UUID> documentsInSubtree(UUID tenantId, UUID folderId, UUID afterId, int limit) {
        return jdbc.query("""
                with recursive down(folder_id) as (
                    select folder_id from folders where tenant_id = ? and folder_id = ?
                    union all
                    select f.folder_id from folders f join down on f.parent_folder_id = down.folder_id where f.tenant_id = ?)
                select d.document_id from documents d
                 where d.tenant_id = ? and d.purged_at is null and d.folder_id in (select folder_id from down)
                   and (?::uuid is null or d.document_id > ?::uuid)
                 order by d.document_id limit ?
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, folderId, tenantId, tenantId, afterId, afterId, limit);
    }

    // ---- eligibility (what belongs in the index): live documents only, not templates, not purged ---------------------

    private static final String ELIGIBLE = "tenant_id = ? and not is_deleted and purged_at is null and not is_template";

    public long eligibleCount(UUID tenantId) {
        Long n = jdbc.queryForObject("select count(*) from documents where " + ELIGIBLE, Long.class, tenantId);
        return n == null ? 0 : n;
    }

    /** One keyset page of the ids that belong in the index. */
    public List<UUID> eligibleIds(UUID tenantId, UUID afterId, int limit) {
        return jdbc.query("select document_id from documents where " + ELIGIBLE
                + " and (?::uuid is null or document_id > ?::uuid) order by document_id limit ?",
                (rs, n) -> rs.getObject(1, UUID.class), tenantId, afterId, afterId, limit);
    }

    /** Of the given ids, those that belong in the index. */
    public java.util.Set<UUID> eligibleAmong(UUID tenantId, java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(jdbc.query("select document_id from documents where " + ELIGIBLE
                + " and document_id = any(?::uuid[])", (rs, n) -> rs.getObject(1, UUID.class), tenantId,
                ids.toArray(new UUID[0])));
    }
}
