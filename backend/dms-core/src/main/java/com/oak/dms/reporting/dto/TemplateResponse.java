package com.oak.dms.reporting.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A template as the caller sees it.
 *
 * @param access   owner | edit | run - what the caller may do with it
 * @param warnings one line per field the administrator has since switched off. The template still runs, without those fields.
 * @param grants   only for someone who may change them (the owner)
 */
public record TemplateResponse(
        UUID id,
        String name,
        String description,
        String scope,
        UUID ownerUserId,
        String ownerName,
        ReportDefinition definition,
        boolean locked,
        List<String> userEditableFilters,
        String access,
        List<String> warnings,
        List<TemplateShareRequest.Grant> grants,
        long activeSchedules,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String OWNER = "owner";
    public static final String EDIT = "edit";
    public static final String RUN = "run";
}
