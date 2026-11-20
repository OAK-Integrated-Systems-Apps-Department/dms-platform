package com.oak.dms.reporting.dto;

/**
 * One field as the administrator sees it: what the code offers, and what they have changed. {@code can*} say what the field supports
 * at all - a switch for something it cannot do has no effect.
 *
 * @param officialTemplatesUsing official templates whose filters, columns, sort or group-by use the field (a count only)
 * @param schedulesUsing         active or paused schedules, anyone's, whose template uses the field (a count only, no names)
 * @param override null when the administrator has not touched the field (it behaves as the code defines it)
 */
public record AdminFieldResponse(String key, String label, String type, boolean custom, boolean canSelect, boolean canFilter,
                                 boolean canSort, boolean canGroup, boolean defaultSelected, FieldOverride override,
                                 int officialTemplatesUsing, int schedulesUsing) {
    /** Without usage figures (an older caller): both counts are 0. */
    public AdminFieldResponse(String key, String label, String type, boolean custom, boolean canSelect, boolean canFilter,
                              boolean canSort, boolean canGroup, boolean defaultSelected, FieldOverride override) {
        this(key, label, type, custom, canSelect, canFilter, canSort, canGroup, defaultSelected, override, 0, 0);
    }
}
