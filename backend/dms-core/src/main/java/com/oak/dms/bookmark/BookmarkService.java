package com.oak.dms.bookmark;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.folder.FolderException;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Private bookmarks (4.4 Bookmark Documents): a shortcut only its owner ever sees. A bookmark never
 * grants access, never changes a document's location, metadata or permissions - every read and every
 * add goes through {@link DocumentService#visibleItems}, the document browser's own access rules
 * (BD-NFR-004), exactly like {@code PinService}.
 * <p>
 * The difference from a pin is what happens when the document becomes unreachable. A pin the caller
 * placed on a document they can no longer see is deleted the next time it's read - unpinning is cheap,
 * so nothing is lost by dropping it. A bookmark is kept: it is HIDDEN (not shown, counted in
 * {@code unavailableCount}, never with a title) while the document is in the recycle bin or the
 * caller's access is revoked, and reappears if the document is restored or access returns. Only a hard
 * purge removes the row, via {@link BookmarkPurgeHandler}. The one place a hidden row is ever deleted
 * from here is {@link #add}, when the caller is at their cap - see {@link #pruneUnavailable}.
 */
@Service
@RequiredArgsConstructor
public class BookmarkService {

    static final int MAX_NOTE_LENGTH = 500;
    static final int MAX_STATUS_IDS = 200;
    static final int MIN_SIZE = 1;
    static final int MAX_SIZE = 100;
    static final int DEFAULT_SIZE = 25;

    /**
     * Upper bound on how many of one person's own rows {@link #list} ever reads, independent of the
     * configured {@code dms.bookmarks.max-per-user}: that cap is only enforced going forward at
     * {@link #add} time, and (being plain deployment config, not a per-tenant setting with its own
     * table) could in principle be lowered after rows already exist. A fixed, generous read limit - the
     * same reasoning as {@code PinService.MAX_READ} - keeps a read bounded regardless.
     */
    static final int MAX_READ = 1000;

    public enum SortKey { BOOKMARKED_AT, TITLE }

    public enum SortDirection { ASC, DESC }

    /** One bookmarked document: the same row the document browser shows, plus the bookmark's own fields. */
    public record BookmarkedDocument(DocumentListItem document, OffsetDateTime bookmarkedAt, String note,
                                     boolean archived) {
    }

    /** A page of the caller's available bookmarks, newest first by default. */
    public record BookmarksPage(long total, int page, int size, int unavailableCount,
                                List<BookmarkedDocument> items) {
    }

    /** Whether {@link #add} created a new row (false = it already existed - idempotent). */
    public record AddResult(boolean created) {
    }

    /** One row as stored, before the document is resolved through {@code visibleItems}. Package-private so
     *  {@link #buildPage} can be tested directly without a database. */
    record Row(UUID documentId, String note, OffsetDateTime bookmarkedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentService documentService;
    private final AuditService auditService;

    @Value("${dms.bookmarks.max-per-user:500}")
    private int maxPerUser;

    /**
     * The caller's own available bookmarks: read (bounded, newest first), resolved through
     * {@code visibleItems} in one call, then filtered/sorted/searched/paged in memory - cheap because
     * one person's rows are themselves capped. A row whose document isn't visible (in the recycle bin,
     * access revoked, or simply gone) is left out and counted in {@code unavailableCount} instead of
     * being shown - see the class doc comment for why it isn't deleted here.
     */
    @Transactional(readOnly = true)
    public BookmarksPage list(int page, int size, SortKey sort, SortDirection dir, String q) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        int pageSize = Math.max(MIN_SIZE, Math.min(size, MAX_SIZE));
        int pageIndex = Math.max(page, 0);

        List<Row> rows = jdbc.query("""
                select document_id, note, bookmarked_at from document_bookmarks
                where tenant_id = :tenant and user_id = :user
                order by bookmarked_at desc
                limit :cap
                """, new MapSqlParameterSource().addValue("tenant", tenantId).addValue("user", userId)
                .addValue("cap", MAX_READ),
                (rs, i) -> new Row(rs.getObject("document_id", UUID.class), rs.getString("note"),
                        rs.getObject("bookmarked_at", OffsetDateTime.class)));

        Set<UUID> documentIds = new LinkedHashSet<>();
        rows.forEach(r -> documentIds.add(r.documentId()));
        Map<UUID, DocumentListItem> byId = new HashMap<>();
        documentService.visibleItems(documentIds).forEach(d -> byId.put(d.documentId(), d));

        return buildPage(rows, byId, pageIndex, pageSize, sort, dir, q);
    }

    /**
     * The in-memory half of {@link #list}: resolve, filter, sort and page - no database. Package-private
     * so it can be unit-tested directly, the same split {@code PinService.pinnedDocuments} uses.
     */
    static BookmarksPage buildPage(List<Row> rows, Map<UUID, DocumentListItem> visible, int pageIndex, int pageSize,
                                   SortKey sort, SortDirection dir, String q) {
        int unavailableCount = (int) rows.stream().filter(r -> !visible.containsKey(r.documentId())).count();

        List<BookmarkedDocument> items = new ArrayList<>();
        for (Row row : rows) {
            DocumentListItem item = visible.get(row.documentId());
            if (item == null) continue;
            items.add(new BookmarkedDocument(item, row.bookmarkedAt(), row.note(),
                    "archived".equals(item.lifecycleState())));
        }

        if (q != null && !q.isBlank()) {
            String term = q.strip().toLowerCase();
            items = items.stream().filter(i -> i.document().title().toLowerCase().contains(term)).toList();
        }

        Comparator<BookmarkedDocument> comparator = sort == SortKey.TITLE
                ? Comparator.comparing(i -> i.document().title().toLowerCase())
                : Comparator.comparing(BookmarkedDocument::bookmarkedAt);
        if (dir != SortDirection.ASC) comparator = comparator.reversed();
        items = items.stream().sorted(comparator).toList();

        int total = items.size();
        int from = Math.min(pageIndex * pageSize, total);
        int to = Math.min(from + pageSize, total);
        return new BookmarksPage(total, pageIndex, pageSize, unavailableCount, items.subList(from, to));
    }

    /**
     * Bookmarks a document the caller can see, or - if they already bookmarked it - updates its note.
     * Idempotent: bookmarking it again never creates a second row (BD-FR-005). The same "not found" for
     * a document that doesn't exist and one the caller may not see, so existence is never leaked
     * (BD-NFR-005 reads this as "no id in any request" - the id here is the document's own, not another
     * user's).
     */
    @Transactional
    public AddResult add(UUID documentId, String note) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        validateNote(note);

        List<DocumentListItem> found = documentService.visibleItems(List.of(documentId));
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Document", documentId);
        }

        // Two adds at once must not both squeeze under the cap: one person's bookmarks are counted and
        // added one request at a time, the same pattern as PinService#pin.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "bookmarks:" + userId), rs -> {
                });

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("user", userId).addValue("document", documentId);
        boolean existed = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from document_bookmarks where tenant_id = :tenant "
                        + "and user_id = :user and document_id = :document)", params, Boolean.class));

        if (!existed) {
            enforceCapOrThrow(tenantId, userId);
        }

        int inserted = jdbc.update("insert into document_bookmarks (tenant_id, user_id, document_id, note) "
                + "values (:tenant, :user, :document, :note) on conflict (user_id, document_id) do nothing",
                new MapSqlParameterSource(params.getValues()).addValue("note", note));
        boolean created = inserted > 0;
        if (!created && note != null) {
            jdbc.update("update document_bookmarks set note = :note, updated_at = now() "
                    + "where tenant_id = :tenant and user_id = :user and document_id = :document",
                    new MapSqlParameterSource(params.getValues()).addValue("note", note));
        }

        if (created) {
            auditService.event(AuditActionCatalog.BOOKMARK_ADDED)
                    .objectType(AuditObjectTypes.DOCUMENT)
                    .objectId(documentId)
                    .resourceName(found.get(0).title())
                    .outcome(AuditOutcome.SUCCESS)
                    .log();
        }
        return new AddResult(created);
    }

    /** At the cap: prune what's no longer visible, then re-check. Throws BOOKMARK_LIMIT_REACHED if still at it. */
    private void enforceCapOrThrow(UUID tenantId, UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenantId).addValue("user", userId);
        int count = countOf(params);
        if (count >= maxPerUser) {
            pruneUnavailable(tenantId, userId);
            count = countOf(params);
        }
        if (count >= maxPerUser) {
            throw new FolderException(HttpStatus.CONFLICT, "BOOKMARK_LIMIT_REACHED",
                    "You can bookmark up to " + maxPerUser + " documents. Remove one to add another.");
        }
    }

    private int countOf(MapSqlParameterSource params) {
        Integer count = jdbc.queryForObject("select count(*) from document_bookmarks "
                + "where tenant_id = :tenant and user_id = :user", params, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Deletes the caller's own bookmarks whose documents are no longer visible (recycle bin, access
     * revoked, or gone) - the only place a hidden row is ever cleaned up (see the class doc comment).
     * Run only when the caller is at their cap and trying to add one more.
     */
    private void pruneUnavailable(UUID tenantId, UUID userId) {
        List<UUID> documentIds = jdbc.queryForList("select document_id from document_bookmarks "
                + "where tenant_id = :tenant and user_id = :user",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("user", userId), UUID.class);
        if (documentIds.isEmpty()) {
            return;
        }
        Set<UUID> visible = new HashSet<>();
        documentService.visibleItems(documentIds).forEach(d -> visible.add(d.documentId()));
        List<UUID> gone = documentIds.stream().filter(id -> !visible.contains(id)).toList();
        if (!gone.isEmpty()) {
            jdbc.update("delete from document_bookmarks where tenant_id = :tenant and user_id = :user "
                    + "and document_id in (:ids)", new MapSqlParameterSource()
                    .addValue("tenant", tenantId).addValue("user", userId).addValue("ids", gone));
        }
    }

    /**
     * Updates the note on a bookmark the caller already has. 404 if they have none on this document -
     * matches {@code PinService#audience}'s "nothing of theirs" shape, not the add/remove "quietly does
     * nothing" one, since this is an edit of something that must already exist to make sense.
     */
    @Transactional
    public void updateNote(UUID documentId, String note) {
        validateNote(note);
        int rows = jdbc.update("update document_bookmarks set note = :note, updated_at = now() "
                + "where tenant_id = :tenant and user_id = :user and document_id = :document",
                new MapSqlParameterSource().addValue("tenant", TenantContext.getTenantId())
                        .addValue("user", TenantContext.getUserId()).addValue("document", documentId)
                        .addValue("note", note));
        if (rows == 0) {
            throw new ResourceNotFoundException("Bookmark", documentId);
        }
    }

    /**
     * Removes the caller's own bookmark. Quietly does nothing when they had none - removing your own
     * bookmark is cleanup, not a privileged action, so this never checks {@code visibleItems} either:
     * a bookmark hidden by a lost document or revoked access must stay removable.
     */
    @Transactional
    public void remove(UUID documentId) {
        int removed = jdbc.update("delete from document_bookmarks where tenant_id = :tenant "
                + "and user_id = :user and document_id = :document",
                new MapSqlParameterSource().addValue("tenant", TenantContext.getTenantId())
                        .addValue("user", TenantContext.getUserId()).addValue("document", documentId));
        if (removed > 0) {
            auditService.event(AuditActionCatalog.BOOKMARK_REMOVED)
                    .objectType(AuditObjectTypes.DOCUMENT)
                    .objectId(documentId)
                    .outcome(AuditOutcome.SUCCESS)
                    .log();
        }
    }

    /**
     * Which of {@code documentIds} the caller has bookmarked - lets a list page render filled icons in
     * one call without loading the whole bookmarks list (BD-NFR-002). No visibility check: the caller
     * already supplied these ids themselves (they're rendering rows for them), so this can't leak
     * anything they don't already have.
     */
    @Transactional(readOnly = true)
    public Set<UUID> status(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> capped = documentIds.size() > MAX_STATUS_IDS
                ? documentIds.subList(0, MAX_STATUS_IDS) : documentIds;
        return new HashSet<>(jdbc.queryForList("select document_id from document_bookmarks "
                + "where tenant_id = :tenant and user_id = :user and document_id in (:ids)",
                new MapSqlParameterSource().addValue("tenant", TenantContext.getTenantId())
                        .addValue("user", TenantContext.getUserId()).addValue("ids", capped), UUID.class));
    }

    private void validateNote(String note) {
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "BOOKMARK_NOTE_TOO_LONG",
                    "Keep the note under " + MAX_NOTE_LENGTH + " characters.");
        }
    }
}
