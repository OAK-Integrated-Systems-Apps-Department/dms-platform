package com.oak.dms.relationship.event;

import java.util.UUID;

/**
 * A new document was created from an upload the user chose to keep next to a document duplicate detection
 * had matched (or that bulk import added as a separate copy). Published by DocumentVersionPromoter inside
 * the promotion transaction; the link is made after it commits.
 */
public record DuplicateKeptEvent(UUID tenantId, UUID newDocumentId, UUID originalDocumentId, UUID uploadedBy,
                                 UUID scanResultId) {
}
