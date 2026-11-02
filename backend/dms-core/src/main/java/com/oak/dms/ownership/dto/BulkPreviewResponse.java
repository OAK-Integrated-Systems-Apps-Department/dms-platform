package com.oak.dms.ownership.dto;

import java.util.List;

/**
 * What a bulk transfer would do, written nothing: how many documents move, how many are skipped, and how many
 * would be switched to Restricted because the new owner could not otherwise open them.
 */
public record BulkPreviewResponse(
        int totalCount,
        int transferableCount,
        List<SkippedDocuments> skipped,
        int willConvertToRestrictedCount,
        int willAddAccessEntryCount,
        boolean newOwnerLacksViewPermission,
        PersonRef newOwner,
        DepartmentRef newOwnerDepartment,
        boolean holding) {
}
