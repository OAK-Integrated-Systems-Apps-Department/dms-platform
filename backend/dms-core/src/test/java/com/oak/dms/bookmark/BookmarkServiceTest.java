package com.oak.dms.bookmark;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.bookmark.BookmarkService.BookmarkedDocument;
import com.oak.dms.bookmark.BookmarkService.BookmarksPage;
import com.oak.dms.bookmark.BookmarkService.Row;
import com.oak.dms.bookmark.BookmarkService.SortDirection;
import com.oak.dms.bookmark.BookmarkService.SortKey;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.folder.FolderException;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules around a bookmark that need no database: note length, the same "not found" for a missing
 * and an invisible document, and how one person's rows resolve into a page (visible/unavailable,
 * search, sort, paging). The cap, prune-on-write and cross-user isolation are exercised against a real
 * database in {@code BookmarkIT}.
 */
class BookmarkServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private NamedParameterJdbcTemplate jdbc;
    private DocumentService documents;
    private BookmarkService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        documents = mock(DocumentService.class);
        service = new BookmarkService(jdbc, documents, mock(AuditService.class));
        ReflectionTestUtils.setField(service, "maxPerUser", 500);
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static DocumentListItem item(UUID id, String title) {
        return item(id, title, "active");
    }

    private static DocumentListItem item(UUID id, String title, String lifecycleState) {
        return new DocumentListItem(id, title, UUID.randomUUID(), "Contract", null, "c.pdf", 10L,
                "application/pdf", 1, UUID.randomUUID(), OffsetDateTime.now(), false, "view", null, "assigned",
                null, lifecycleState, null);
    }

    private static Row row(UUID document, String note, OffsetDateTime at) {
        return new Row(document, note, at);
    }

    @Test
    void aNoteOverTheLimitIsRefused_andTouchesNothing() {
        String tooLong = "a".repeat(501);
        assertThatThrownBy(() -> service.add(documentId, tooLong))
                .isInstanceOfSatisfying(FolderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("BOOKMARK_NOTE_TOO_LONG"));
        verifyNoInteractions(jdbc, documents);
    }

    @Test
    void aNoteOverTheLimitOnUpdateIsRefused() {
        assertThatThrownBy(() -> service.updateNote(documentId, "a".repeat(501)))
                .isInstanceOfSatisfying(FolderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("BOOKMARK_NOTE_TOO_LONG"));
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void aDocumentTheCallerCannotSeeIsNotFound_neverBookmarked() {
        when(documents.visibleItems(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.add(documentId, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void buildPage_leavesOutWhatIsNotVisible_andCountsIt() {
        UUID visible = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        List<Row> rows = List.of(row(visible, null, now), row(hidden, null, now.minusHours(1)));

        BookmarksPage page = BookmarkService.buildPage(rows, Map.of(visible, item(visible, "Visible")),
                0, 25, SortKey.BOOKMARKED_AT, SortDirection.DESC, null);

        assertThat(page.unavailableCount()).isEqualTo(1);
        assertThat(page.items()).extracting(i -> i.document().documentId()).containsExactly(visible);
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void buildPage_flagsAnArchivedDocument() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Row> rows = List.of(row(documentId, null, now));

        BookmarksPage page = BookmarkService.buildPage(rows,
                Map.of(documentId, item(documentId, "Old policy", "archived")),
                0, 25, SortKey.BOOKMARKED_AT, SortDirection.DESC, null);

        assertThat(page.items().get(0).archived()).isTrue();
    }

    @Test
    void buildPage_sortsByTitleOrDate_eitherDirection() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        List<Row> rows = List.of(row(a, null, now.minusDays(1)), row(b, null, now));
        Map<UUID, DocumentListItem> visible = Map.of(a, item(a, "Zebra"), b, item(b, "Apple"));

        BookmarksPage byDateDesc = BookmarkService.buildPage(rows, visible, 0, 25,
                SortKey.BOOKMARKED_AT, SortDirection.DESC, null);
        assertThat(byDateDesc.items()).extracting(i -> i.document().documentId()).containsExactly(b, a);

        BookmarksPage byTitleAsc = BookmarkService.buildPage(rows, visible, 0, 25,
                SortKey.TITLE, SortDirection.ASC, null);
        assertThat(byTitleAsc.items()).extracting(i -> i.document().title()).containsExactly("Apple", "Zebra");
    }

    @Test
    void buildPage_filtersByTitle_caseInsensitive() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        List<Row> rows = List.of(row(a, null, now), row(b, null, now));
        Map<UUID, DocumentListItem> visible = Map.of(a, item(a, "Budget 2027"), b, item(b, "Policy handbook"));

        BookmarksPage page = BookmarkService.buildPage(rows, visible, 0, 25,
                SortKey.BOOKMARKED_AT, SortDirection.DESC, "BUDGET");

        assertThat(page.items()).extracting(i -> i.document().documentId()).containsExactly(a);
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void buildPage_pagesInMemory() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Row> many = new ArrayList<>();
        Map<UUID, DocumentListItem> visible = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            many.add(row(id, null, now.minusMinutes(i)));
            visible.put(id, item(id, "Doc " + i));
        }

        BookmarksPage page = BookmarkService.buildPage(many, visible, 1, 2,
                SortKey.BOOKMARKED_AT, SortDirection.DESC, null);

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        // Newest first (DESC), page index 1 (0-based) of size 2: the 3rd and 4th newest.
        assertThat(page.items()).extracting(BookmarkedDocument::note).hasSize(2);
    }
}
