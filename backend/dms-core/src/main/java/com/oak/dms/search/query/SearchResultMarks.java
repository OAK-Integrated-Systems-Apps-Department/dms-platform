package com.oak.dms.search.query;

import com.oak.dms.bookmark.BookmarkService;
import com.oak.dms.pin.PinService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a results page shows beyond the index (S11): the caller's pinned and bookmarked marks and the thumbnail link.
 *
 * <p>Called with the ids of <b>verified</b> rows only (the page that already passed the PostgreSQL visibility check),
 * never with candidates, and never from the index: marks are per user and would be wrong the moment someone pins.
 * A hidden document therefore never reaches this class, so it never gets a mark or a thumbnail URL. The thumbnail link
 * is also withheld from a caller who lacks {@code docm.documents.view} (search can be granted without it): the picture
 * endpoint would refuse them, so the page shows the file-type icon instead of a broken image.
 */
@Component
public class SearchResultMarks {

    private final PinService pins;
    private final BookmarkService bookmarks;
    private final AuthorizationService authz;

    public SearchResultMarks(PinService pins, BookmarkService bookmarks, AuthorizationService authz) {
        this.pins = pins;
        this.bookmarks = bookmarks;
        this.authz = authz;
    }

    /** One indexed query each, bounded by the page size. */
    public Marks forPage(Collection<UUID> verifiedIds) {
        if (verifiedIds == null || verifiedIds.isEmpty()) {
            return new Marks(Set.of(), Set.of(), false);
        }
        List<UUID> ids = List.copyOf(verifiedIds);
        return new Marks(pins.status(ids), bookmarks.status(ids),
                authz.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW));
    }

    public record Marks(Set<UUID> pinned, Set<UUID> bookmarked, boolean canThumbnail) {

        public boolean pinned(UUID id) {
            return pinned.contains(id);
        }

        public boolean bookmarked(UUID id) {
            return bookmarked.contains(id);
        }

        /** The existing endpoint (re-checks access on every request, 204 when there is no picture). */
        public String thumbnailUrl(UUID id) {
            return canThumbnail ? "/api/v1/documents/" + id + "/thumbnail" : null;
        }
    }
}
