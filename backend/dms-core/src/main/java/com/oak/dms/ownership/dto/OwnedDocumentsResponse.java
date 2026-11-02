package com.oak.dms.ownership.dto;

import java.util.List;

/**
 * One page of owned documents the caller may see. {@code hiddenCount} is how many more the owner holds that the
 * caller cannot open (only a tenant admin can transfer those) - a number, never a title, type or id.
 */
public record OwnedDocumentsResponse(
        List<OwnedDocumentItem> items,
        int page,
        int size,
        long total,
        long totalOwned,
        long visibleCount,
        long hiddenCount) {
}
