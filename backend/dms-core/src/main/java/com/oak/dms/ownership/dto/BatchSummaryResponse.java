package com.oak.dms.ownership.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record BatchSummaryResponse(
        UUID batchId,
        String kind,
        String reason,
        int totalCount,
        int transferredCount,
        int skippedCount,
        List<SkippedDocuments> skipped,
        int accessGrantedCount,
        int convertedToRestrictedCount,
        int flaggedTaskCount,
        boolean newOwnerLacksViewPermission,
        PersonRef newOwner,
        DepartmentRef newOwnerDepartment,
        boolean holding,
        PersonRef createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {
}
