package com.oak.dms.search.query;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One search, as asked. Every filter is optional and combines with the keywords (and with each other) as AND; the filter
 * model is a plain record so a new filter (tags, one day) is one field here, one clause in the query builder and one entry
 * in the validator.
 *
 * @param statuses lifecycle states to include: {@code active} (the default), {@code inactive}, {@code archived}
 * @param sort     {@code relevance} (default), {@code title}, {@code created}, {@code modified}
 */
public record SearchRequest(String q, UUID documentTypeId, UUID departmentId, UUID ownerId, Instant createdFrom,
                            Instant createdTo, Instant modifiedFrom, Instant modifiedTo, Set<String> statuses,
                            UUID folderId, boolean includeSubfolders, List<MetaFilter> meta, String sort,
                            boolean ascending, int size, String cursor) {

    /** A typed filter on one configurable metadata field. {@code op} is eq, gte, lte or contains. */
    public record MetaFilter(UUID fieldId, String op, String value) {
    }

    public static final Set<String> STATES = Set.of("active", "inactive", "archived");

    public boolean hasKeywords() {
        return q != null && !q.isBlank();
    }

    public boolean wantsArchived() {
        return statuses != null && (statuses.contains("inactive") || statuses.contains("archived"));
    }

    public SearchRequest withoutDocumentType() {
        return new SearchRequest(q, null, departmentId, ownerId, createdFrom, createdTo, modifiedFrom, modifiedTo, statuses,
                folderId, includeSubfolders, meta, sort, ascending, size, cursor);
    }

    public SearchRequest withoutDepartment() {
        return new SearchRequest(q, documentTypeId, null, ownerId, createdFrom, createdTo, modifiedFrom, modifiedTo, statuses,
                folderId, includeSubfolders, meta, sort, ascending, size, cursor);
    }

    public SearchRequest withoutOwner() {
        return new SearchRequest(q, documentTypeId, departmentId, null, createdFrom, createdTo, modifiedFrom, modifiedTo,
                statuses, folderId, includeSubfolders, meta, sort, ascending, size, cursor);
    }

    public SearchRequest withoutFolder() {
        return new SearchRequest(q, documentTypeId, departmentId, ownerId, createdFrom, createdTo, modifiedFrom, modifiedTo,
                statuses, null, false, meta, sort, ascending, size, cursor);
    }

    public SearchRequest withMeta(List<MetaFilter> kept) {
        return new SearchRequest(q, documentTypeId, departmentId, ownerId, createdFrom, createdTo, modifiedFrom, modifiedTo,
                statuses, folderId, includeSubfolders, kept, sort, ascending, size, cursor);
    }
}
