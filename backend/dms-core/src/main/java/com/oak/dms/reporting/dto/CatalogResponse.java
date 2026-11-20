package com.oak.dms.reporting.dto;

import java.util.List;
import java.util.Map;

/**
 * Everything the builder needs in one call: the fields this organisation has left on, the effective settings (already capped at
 * the platform ceilings), and what the caller may do. A field the administrator disabled is simply not in the list.
 */
public record CatalogResponse(List<Field> fields, Settings settings, Capabilities capabilities,
                              List<String> relativeDateOptions, int maxRelativeDays) {

    /** @param operators wire names, in the order to offer them; @param type text | choice | reference | number | date | boolean */
    public record Field(String key, String label, String type, List<String> operators, boolean multi, boolean metadata,
                        boolean selectable, boolean filterable, boolean sortable, boolean groupable,
                        boolean defaultSelected) {
    }

    public record Limits(int maxFilters, int maxColumns, int maxMetadataFilters, int maxSortKeys, int maxRows,
                         int maxPdfRows) {
    }

    public record Settings(List<String> allowedFormats, String defaultFormat, String overLimitBehaviour,
                           String defaultTimeZone, boolean includeInactiveByDefault, List<String> defaultColumns,
                           List<Map<String, Object>> defaultSort, Limits limits, boolean schedulingEnabled,
                           String minScheduleInterval, List<String> allowedDeliveryChannels, String deliveryModeDefault,
                           boolean allowAttachments, int maxAttachmentMb, boolean allowExternalRecipients,
                           int maxRecipientsPerSchedule, boolean showFilterSummary,
                           int pauseAfterConsecutiveFailures) {
    }

    public record Capabilities(boolean build, boolean run, boolean schedule, boolean publish, boolean configure,
                               boolean viewAllRuns) {
    }
}
