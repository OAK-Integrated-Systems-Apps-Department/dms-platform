package com.oak.dms.relationship.event;

import java.util.UUID;

/**
 * A link was created - by a person, or by the automatic "Duplicate of" (then the actor is whoever
 * uploaded the copy). Published inside the creating transaction; the notification listener runs
 * before commit. {@code notifiedDocumentId} is the document the user linked <em>to</em> as they chose it,
 * before a symmetric pair is put in canonical order.
 */
public record RelationshipCreatedEvent(UUID tenantId, UUID relationshipId, UUID relationshipTypeId, UUID sourceDocumentId,
                                       UUID targetDocumentId, UUID notifiedDocumentId, UUID actorUserId, UUID batchId) {
}
