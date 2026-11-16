package com.oak.dms.search.query;

import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.search.index.SearchSourceRepository;
import com.oak.dms.search.index.SearchSourceRepository.FieldDef;
import com.oak.dms.search.query.SearchResponse.Notice;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Checks the filters a request carries against what exists now. A filter that points at something that no longer exists
 * (a deleted document type, an owner who left, a metadata field an administrator removed, a folder the user cannot see) is
 * dropped and reported in a notice, and the remaining criteria still apply: the alternate flow for a stale saved preset,
 * and for any bookmarked link. A folder the caller cannot see is treated exactly like one that does not exist, so the
 * answer reveals nothing about folders they have no access to. A malformed operator or value is the caller's mistake: 400.
 */
@Component
public class SearchFilterValidator {

    /** The request with unusable filters removed, what was removed, and the typed metadata field definitions. */
    public record Validated(SearchRequest request, List<Notice> notices, Map<UUID, FieldDef> fields) {
    }

    private static final Set<String> OPS = Set.of("eq", "gte", "lte", "contains");

    private final JdbcTemplate jdbc;
    private final SearchSourceRepository source;
    private final DocumentAccessService access;

    public SearchFilterValidator(JdbcTemplate jdbc, SearchSourceRepository source, DocumentAccessService access) {
        this.jdbc = jdbc;
        this.source = source;
        this.access = access;
    }

    public Validated validate(UUID tenantId, SearchRequest request) {
        SearchRequest r = request;
        List<String> gone = new ArrayList<>();

        if (r.documentTypeId() != null && !exists(TYPE_EXISTS, tenantId, r.documentTypeId())) {
            r = r.withoutDocumentType();
            gone.add("documentType");
        }
        if (r.departmentId() != null && !exists(DEPARTMENT_EXISTS, tenantId, r.departmentId())) {
            r = r.withoutDepartment();
            gone.add("department");
        }
        if (r.ownerId() != null && !exists(USER_EXISTS, tenantId, r.ownerId())) {
            r = r.withoutOwner();
            gone.add("owner");
        }
        if (r.folderId() != null && !folderUsable(tenantId, r.folderId())) {
            r = r.withoutFolder();
            gone.add("folder");
        }

        Map<UUID, FieldDef> fields = r.meta() == null || r.meta().isEmpty() ? Map.of() : source.fieldDefs(tenantId);
        List<SearchRequest.MetaFilter> kept = new ArrayList<>();
        if (r.meta() != null) {
            for (SearchRequest.MetaFilter m : r.meta()) {
                if (!OPS.contains(m.op())) {
                    throw bad("Unknown metadata operator '" + m.op() + "'.");
                }
                FieldDef def = fields.get(m.fieldId());
                if (def == null || !def.searchable() || "inactive".equals(def.status())) {
                    gone.add("metadata");
                    continue;
                }
                checkValue(m, def);
                kept.add(m);
            }
            r = r.withMeta(kept);
        }

        List<Notice> notices = new ArrayList<>();
        if (!gone.isEmpty()) {
            notices.add(Notice.of("FILTER_NO_LONGER_EXISTS",
                    "Some filters refer to something that no longer exists and were not applied.", gone.toArray(String[]::new)));
        }
        return new Validated(r, notices, fields);
    }

    private static void checkValue(SearchRequest.MetaFilter m, FieldDef def) {
        String type = def.dataType();
        boolean numeric = "number".equals(type) || "decimal".equals(type);
        boolean date = "date".equals(type) || "datetime".equals(type);
        boolean range = "gte".equals(m.op()) || "lte".equals(m.op());
        if (range && !numeric && !date) {
            throw bad("'" + m.op() + "' only applies to number and date fields.");
        }
        if ("contains".equals(m.op()) && (numeric || date || "boolean".equals(type))) {
            throw bad("'contains' only applies to text fields.");
        }
        if (m.value() == null || m.value().isBlank()) {
            throw bad("A metadata filter needs a value.");
        }
        if (numeric) {
            try {
                Double.parseDouble(m.value());
            } catch (NumberFormatException e) {
                throw bad("'" + def.key() + "' needs a number.");
            }
        }
        if (date) {
            try {
                java.time.OffsetDateTime.parse(m.value());
            } catch (RuntimeException e1) {
                try {
                    java.time.LocalDate.parse(m.value());
                } catch (RuntimeException e2) {
                    throw bad("'" + def.key() + "' needs a date such as 2026-03-31.");
                }
            }
        }
    }

    // One constant statement each: SQL is never assembled from parts (see SqlConcatenationGuardTest).
    private static final String TYPE_EXISTS =
            "select count(*) from document_types where tenant_id = ? and document_type_id = ? and not is_deleted";
    private static final String DEPARTMENT_EXISTS =
            "select count(*) from departments where tenant_id = ? and department_id = ? and not is_deleted";
    private static final String USER_EXISTS =
            "select count(*) from users where tenant_id = ? and user_id = ? and not is_deleted";

    private boolean exists(String sql, UUID tenantId, UUID id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, tenantId, id);
        return n != null && n > 0;
    }

    private boolean folderUsable(UUID tenantId, UUID folderId) {
        Integer n = jdbc.queryForObject("select count(*) from folders where tenant_id = ? and folder_id = ? and not is_deleted",
                Integer.class, tenantId, folderId);
        return n != null && n > 0 && access.forCaller().folders().canSee(folderId);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
