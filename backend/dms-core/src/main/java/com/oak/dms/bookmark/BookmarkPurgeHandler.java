package com.oak.dms.bookmark;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A purged document can never be bookmarked again, so its bookmarks go with it - permanently, not just
 * hidden (see {@link BookmarkService}'s class doc comment for the hidden-vs-deleted distinction).
 * {@code ON DELETE CASCADE} on {@code document_bookmarks} does NOT fire on a purge: the document and
 * version rows stay as tombstones ({@code purged_at}), they are never actually deleted. This hook is
 * therefore required, called from {@link com.oak.dms.document.RecycleBinService}'s purge transaction -
 * the same pattern as {@code RelationshipPurgeHandler}. A purge that fails leaves bookmarks as they were.
 * <p>
 * Runs from the purge sweep as well as from a person, so it takes the tenant explicitly and never reads
 * {@code TenantContext}.
 */
@Component
@RequiredArgsConstructor
public class BookmarkPurgeHandler {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public int removeBookmarksOf(UUID tenantId, UUID documentId) {
        return jdbc.update("delete from document_bookmarks where tenant_id = ? and document_id = ?",
                tenantId, documentId);
    }
}
