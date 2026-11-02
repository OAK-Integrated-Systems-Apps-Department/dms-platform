package com.oak.dms.signature.dto;

import java.util.UUID;

/**
 * What the placement UI needs before showing the document: whether this
 * file type can be stamped at all ({@code DocumentStampService#supports}),
 * and if so, how many pages/images it has (always 1 for a still image) to
 * render one at a time.
 */
public record SignaturePlacementInfoResponse(
        UUID documentId,
        UUID versionId,
        String mimeType,
        boolean supported,
        int pageCount,
        /** True once this task has placed a signature at least once - the placement UI then offers to remove it. */
        boolean alreadyPlaced
) {
}
