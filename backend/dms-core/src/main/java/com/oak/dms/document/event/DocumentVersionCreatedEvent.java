package com.oak.dms.document.event;

import java.util.UUID;

/**
 * A new version became a document's latest version - published by
 * DocumentVersionPromoter inside the promotion transaction (synchronous
 * listeners join it). Carries tenant and actor itself because promotion can
 * run in the retry scheduler or a cloud callback, without a TenantContext.
 */
public record DocumentVersionCreatedEvent(UUID tenantId, UUID documentId, UUID previousLatestVersionId,
                                          UUID newVersionId, UUID uploadedBy) {
}
