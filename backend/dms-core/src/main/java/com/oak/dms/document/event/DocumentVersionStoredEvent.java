package com.oak.dms.document.event;

import java.util.UUID;

/**
 * A version's file is in its final location and its row is being committed - published for every new version
 * (the first upload as well as a replacement or a restore) by DocumentVersionPromoter inside the promotion
 * transaction. Listeners that need the committed row (the rendition generator) wait for the commit.
 */
public record DocumentVersionStoredEvent(UUID tenantId, UUID documentId, UUID versionId) {
}
