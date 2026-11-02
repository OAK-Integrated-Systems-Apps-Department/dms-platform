package com.oak.dms.ownership.dto;

import java.util.List;
import java.util.UUID;

/** Everything the Offboard dialog shows before the admin confirms. */
public record OffboardingPreviewResponse(
        PersonRef user,
        DepartmentRef department,
        long ownedTotal,
        long visibleCount,
        long hiddenCount,
        long inRecycleBinCount,
        SuggestedTarget suggestedTarget,
        WorkFlags flags,
        boolean canOffboard,
        List<String> blockers) {

    /** Who the holding chain would pick for this user's documents. */
    public record SuggestedTarget(UUID userId, String name, String source, DepartmentRef department) {
    }
}
