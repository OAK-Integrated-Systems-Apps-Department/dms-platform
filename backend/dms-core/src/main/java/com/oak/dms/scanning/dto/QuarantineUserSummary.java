package com.oak.dms.scanning.dto;

import java.util.UUID;

/** A user shown on a quarantine screen (uploader, reviewer, requester) - id plus display name only. */
public record QuarantineUserSummary(
        UUID userId,
        String displayName
) {
}
