package com.oak.dms.ownership.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OwnedDocumentItem(
        UUID documentId,
        String title,
        String documentTypeName,
        UUID folderId,
        String folderName,
        boolean inRecycleBin,
        boolean template,
        String ownershipState,
        PersonRef owner,
        DepartmentRef ownerDepartment,
        String pendingReason,
        OffsetDateTime createdAt) {
}
