package com.oak.dms.search.query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the API returns. By construction it holds nothing about documents the caller may not see: every item was verified in
 * PostgreSQL, the total is never the engine's raw number, and notices are generic codes.
 *
 * @param mode {@code search} (index) or {@code basic} (the title-search fallback)
 */
public record SearchResponse(String mode, List<Item> items, Total total, String nextCursor, List<Notice> notices,
                             String didYouMean) {

    /** Spring logs response bodies at DEBUG: counts only, never titles, highlights or suggestions. */
    @Override
    public String toString() {
        return "SearchResponse[mode=" + mode + ", items=" + (items == null ? 0 : items.size()) + "]";
    }

    public record Item(UUID documentId, String title, String fileName, UUID documentTypeId, String documentTypeName,
                       UUID folderId, UUID ownerUserId, UUID ownerDepartmentId, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt, String lifecycleState, boolean legalHold, String mimeType,
                       String bodyStatus, Highlights highlights, Integer versionNumber, Long sizeBytes,
                       String thumbnailUrl, boolean pinned, boolean bookmarked) {
        @Override
        public String toString() {
            return "Item[" + documentId + "]";
        }
    }

    /** Snippets with the matched words wrapped in {@code <mark>}; everything else is HTML-escaped. */
    public record Highlights(String title, String fileName, List<String> body) {
        @Override
        public String toString() {
            return "Highlights[...]";
        }

        public static final Highlights NONE = new Highlights(null, null, List.of());
    }

    /** {@code exact} is true only when every candidate was verified; otherwise {@code value} means "this many or more". */
    public record Total(long value, boolean exact) {
    }

    /** A machine-readable code, a human message, and the parts of the request it is about (never document data). */
    public record Notice(String code, String message, List<String> details) {
        public static Notice of(String code, String message, String... details) {
            return new Notice(code, message, List.of(details));
        }
    }

    public record Suggestions(List<Suggestion> suggestions) {
    }

    public record Suggestion(UUID documentId, String title) {
    }
}
