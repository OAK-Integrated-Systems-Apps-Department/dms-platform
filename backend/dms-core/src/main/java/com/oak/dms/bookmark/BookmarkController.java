package com.oak.dms.bookmark;

import com.oak.dms.bookmark.BookmarkService.AddResult;
import com.oak.dms.bookmark.BookmarkService.BookmarksPage;
import com.oak.dms.bookmark.BookmarkService.SortDirection;
import com.oak.dms.bookmark.BookmarkService.SortKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private bookmarks - see {@link BookmarkService}. Permission codes from V10's BD module. */
@RestController
@RequestMapping("/api/v1/bookmarks")
@RequiredArgsConstructor
@Tag(name = "Bookmarks", description = "Private shortcuts to documents, visible only to the person who added them")
public class BookmarkController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final BookmarkService service;

    // Note length isn't validated here with @Valid/@Size: BookmarkService#add/updateNote enforce it and
    // raise FolderException("BOOKMARK_NOTE_TOO_LONG", ...) with the specific message the UI shows -
    // bean validation would only get in the way with a generic 400 first.
    public record AddRequest(String note) {
    }

    public record NoteRequest(String note) {
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "The caller's own available bookmarks, paged",
            description = "Newest first by default. A bookmark whose document is in the recycle bin or no longer "
                    + "visible to the caller is left out and counted in unavailableCount instead of removed - it "
                    + "reappears if the document comes back.")
    public BookmarksPage list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + BookmarkService.DEFAULT_SIZE) int size,
            @RequestParam(name = "sort", defaultValue = "bookmarkedAt") String sort,
            @RequestParam(name = "dir", defaultValue = "desc") String dir,
            @RequestParam(name = "q", required = false) String q) {
        SortKey sortKey = "title".equalsIgnoreCase(sort) ? SortKey.TITLE : SortKey.BOOKMARKED_AT;
        SortDirection direction = "asc".equalsIgnoreCase(dir) ? SortDirection.ASC : SortDirection.DESC;
        return service.list(page, size, sortKey, direction, q);
    }

    @PutMapping("/{documentId}")
    @PreAuthorize("@authz.hasPermission(" + P + "BD_BOOKMARK)")
    @Operation(summary = "Bookmark a document the caller can see, or update the note on one they already bookmarked",
            description = "Idempotent: bookmarking an already-bookmarked document never creates a second row. If it "
                    + "already exists and a note is supplied, the note is updated. 409 BOOKMARK_LIMIT_REACHED when a "
                    + "new bookmark is over the caller's cap.")
    public Map<String, Boolean> add(@PathVariable("documentId") UUID documentId,
                                    @RequestBody(required = false) AddRequest request) {
        AddResult result = service.add(documentId, request == null ? null : request.note());
        return Map.of("created", result.created());
    }

    @PatchMapping("/{documentId}/note")
    // No permission check beyond authentication, same reasoning as unbookmark below: editing the note
    // on a bookmark you already have is managing your own private data, not a privileged action, and
    // must keep working even for someone whose BD_BOOKMARK permission was later taken away.
    @Operation(summary = "Update the note on the caller's own bookmark",
            description = "404 when the caller has no bookmark on this document.")
    public ResponseEntity<Void> updateNote(@PathVariable("documentId") UUID documentId,
                                           @RequestBody NoteRequest request) {
        service.updateNote(documentId, request.note());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{documentId}")
    // No permission check: removing your OWN bookmark needs only authentication (4.4 decision table) -
    // nobody should be stuck unable to clean up their own shortcut because a role changed. The seeded
    // bd.bookmark-unbookmark-documents.remove-bookmark code stays in the catalog for role display, but
    // this endpoint deliberately never depends on it.
    @Operation(summary = "Remove the caller's own bookmark",
            description = "204 always, whether or not they had one. Removes only the caller's own row.")
    public ResponseEntity<Void> remove(@PathVariable("documentId") UUID documentId) {
        service.remove(documentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Which of these document ids the caller has bookmarked",
            description = "Up to 200 ids. Lets a list page render filled bookmark icons in one call without "
                    + "loading the whole bookmarks list.")
    public Map<String, List<UUID>> status(@RequestParam(name = "ids") List<UUID> ids) {
        return Map.of("bookmarkedDocumentIds", List.copyOf(service.status(ids)));
    }
}
