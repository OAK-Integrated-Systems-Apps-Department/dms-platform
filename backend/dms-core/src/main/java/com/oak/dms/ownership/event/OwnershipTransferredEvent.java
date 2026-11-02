package com.oak.dms.ownership.event;

import java.util.Map;
import java.util.UUID;

/**
 * Published inside the transfer's transaction after a single, bulk, holding or offboarding transfer. The
 * notification listener turns it into messages (BEFORE_COMMIT, so they commit with the transfer).
 *
 * @param singleDocumentId  set for a one-document transfer (the message may then name it), null for a batch
 * @param singleDocumentTitle the title of that document
 * @param previousOwnerCounts how many documents each previous owner lost
 * @param holding           documents were parked with a holding owner (state 'holding')
 * @param holdingDepartmentId the department whose holding chain was used, when {@code holding}
 */
public record OwnershipTransferredEvent(UUID tenantId, UUID batchId, String kind, UUID actorUserId,
                                        UUID newOwnerUserId, UUID newOwnerDepartmentId, int transferredCount,
                                        Map<UUID, Integer> previousOwnerCounts, UUID singleDocumentId,
                                        String singleDocumentTitle, boolean holding, UUID holdingDepartmentId) {
}
