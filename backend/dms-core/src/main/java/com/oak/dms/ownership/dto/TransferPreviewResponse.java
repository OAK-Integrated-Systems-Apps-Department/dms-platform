package com.oak.dms.ownership.dto;

import java.util.UUID;

/**
 * What a single transfer would do, without doing it - for the confirmation step of the transfer dialog.
 * {@code willConvertToRestricted} is true when the new owner could not otherwise open the document and it
 * follows a restricted folder, so it would be switched to its own (copied) access list.
 */
public record TransferPreviewResponse(
        UUID documentId,
        PersonRef currentOwner,
        PersonRef newOwner,
        DepartmentRef newOwnerDepartment,
        boolean alreadyOwner,
        boolean newOwnerCanOpenAlready,
        boolean willAddAccessEntry,
        boolean willConvertToRestricted,
        boolean newOwnerLacksViewPermission) {
}
