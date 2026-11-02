package com.oak.dms.ownership.dto;

import java.util.UUID;

public record TransferDocumentResponse(
        UUID transferId,
        UUID batchId,
        UUID documentId,
        PersonRef previousOwner,
        PersonRef newOwner,
        DepartmentRef newOwnerDepartment,
        boolean accessGranted,
        boolean accessConvertedToRestricted,
        boolean newOwnerLacksViewPermission) {
}
