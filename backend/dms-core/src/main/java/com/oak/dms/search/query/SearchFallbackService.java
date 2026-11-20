package com.oak.dms.search.query;

import com.oak.dms.document.DocumentService;
import com.oak.dms.document.DocumentView;
import com.oak.dms.document.OwnerFilter;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.query.SearchResponse.Highlights;
import com.oak.dms.search.query.SearchResponse.Item;
import com.oak.dms.search.query.SearchResponse.Notice;
import com.oak.dms.search.query.SearchResponse.Total;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The title search the product has always had, wearing the search API's clothes (D9). Used whenever the engine cannot
 * answer: it is down, slow or red, the index is empty or still being built, or search is switched off for the tenant.
 * It goes through {@link DocumentService#listDocumentsIn}, i.e. the browse queries with all their visibility,
 * lifecycle and Recycle Bin rules, unchanged, so falling back can only ever show what browsing shows.
 *
 * <p>It honours the filters browsing has (title words, type, owner, department, folder, active vs archived) and says in a
 * notice which of the user's criteria it could not apply (body text, metadata, dates, subfolders).
 */
@Component
public class SearchFallbackService {

    private final DocumentService documents;
    private final SearchCursor cursors;
    private final SearchProperties props;
    private final SearchResultMarks marks;

    public SearchFallbackService(DocumentService documents, SearchCursor cursors, SearchProperties props,
                                 SearchResultMarks marks) {
        this.marks = marks;
        this.documents = documents;
        this.cursors = cursors;
        this.props = props;
    }

    public SearchResponse search(UUID tenantId, UUID userId, SearchRequest r, List<String> states, SearchCursor.State cursor,
                                 List<Notice> inherited) {
        boolean wantsActive = states.contains("active");
        boolean wantsArchive = states.contains("inactive") || states.contains("archived");
        DocumentView view = wantsActive ? DocumentView.ACTIVE : DocumentView.ARCHIVE;
        int page = cursor == null ? 0 : Math.max(0, cursor.page());
        Sort sort = switch (r.sort() == null ? "relevance" : r.sort()) {
            case "title" -> Sort.by(r.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, "title");
            case "created" -> Sort.by(r.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, "createdAt");
            case "modified" -> Sort.by(r.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, "updatedAt");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        Page<DocumentListItem> found = documents.listDocumentsIn(view, r.folderId(), r.folderId() == null,
                r.hasKeywords() ? r.q() : null, r.documentTypeId(), null, new OwnerFilter(r.ownerId(), r.departmentId()),
                PageRequest.of(page, r.size(), sort));

        SearchResultMarks.Marks m = marks.forPage(found.getContent().stream().map(DocumentListItem::documentId).toList());
        List<Item> items = new ArrayList<>();
        for (DocumentListItem d : found.getContent()) {
            items.add(new Item(d.documentId(), d.title(), d.fileName(), d.documentTypeId(), d.documentTypeName(), d.folderId(),
                    d.ownerUserId(), d.ownerDepartmentId(), d.createdAt(), null, d.lifecycleState(), false, d.mimeType(),
                    null, Highlights.NONE, d.versionNumber(), d.sizeBytes(), m.thumbnailUrl(d.documentId()),
                    m.pinned(d.documentId()), m.bookmarked(d.documentId())));
        }

        List<Notice> notices = new ArrayList<>(inherited);
        notices.add(Notice.of("FALLBACK", "Full search isn't available right now, so only document titles were searched."));
        List<String> notApplied = new ArrayList<>();
        if (r.hasKeywords()) {
            notApplied.add("text inside documents");
        }
        if (r.meta() != null && !r.meta().isEmpty()) {
            notApplied.add("metadata");
        }
        if (r.createdFrom() != null || r.createdTo() != null) {
            notApplied.add("created date");
        }
        if (r.modifiedFrom() != null || r.modifiedTo() != null) {
            notApplied.add("modified date");
        }
        if (r.folderId() != null && r.includeSubfolders()) {
            notApplied.add("subfolders");
        }
        if (wantsActive && wantsArchive) {
            notApplied.add("inactive and archived documents");
        }
        if (!notApplied.isEmpty()) {
            notices.add(Notice.of("FILTERS_NOT_APPLIED", "Some criteria could not be applied in this mode.", notApplied.toArray(String[]::new)));
        }
        if (items.isEmpty()) {
            notices.add(Notice.of("NO_RESULTS", "No results found."));
        }
        String next = found.hasNext()
                ? cursors.encode(new SearchCursor.State(null, 0, page + 1, "basic"), SearchCursor.fingerprint(tenantId, userId, r))
                : null;
        // The count comes from the browse query, which only counts what this user can see: exact and safe.
        return new SearchResponse("basic", items, new Total(found.getTotalElements(), true), next, notices, null);
    }
}
