package com.oak.dms.ownership.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One owner change of a document. People are resolved even when the account was deactivated; for one that no
 * longer exists the name stored on the row is used ({@code status} is then null).
 */
public record OwnershipHistoryItem(
        UUID transferId,
        OffsetDateTime transferredAt,
        String reason,
        UUID batchId,
        String note,
        boolean accessGranted,
        PersonRef previousOwner,
        DepartmentRef previousDepartment,
        PersonRef newOwner,
        DepartmentRef newDepartment,
        PersonRef actor) {
}
