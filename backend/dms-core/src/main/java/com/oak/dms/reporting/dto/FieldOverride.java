package com.oak.dms.reporting.dto;

/**
 * What an administrator has set for one field. An override can only RESTRICT what the code offers: a field the code does not let
 * anyone filter by cannot be switched on to filter by, whatever is sent here. Omitted switches mean "leave on" ({@code enabled},
 * {@code selectable}, {@code filterable}, {@code sortable}, {@code groupable}) or "off" ({@code defaultSelected}, {@code showCode}).
 *
 * @param fieldKey      {@code title}, {@code status}, ... or {@code meta.<fieldKey>} for a custom field
 * @param enabled       false hides the field everywhere: builder, templates (with a warning) and schedules
 * @param labelOverride what people see instead of the standard label
 * @param showCode      for a choice field: the code instead of the label
 */
public record FieldOverride(String fieldKey, Boolean enabled, String labelOverride, Boolean selectable, Boolean filterable,
                            Boolean sortable, Boolean groupable, Boolean defaultSelected, Integer displayOrder, Boolean showCode) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public boolean selectableOrDefault() {
        return selectable == null || selectable;
    }

    public boolean filterableOrDefault() {
        return filterable == null || filterable;
    }

    public boolean sortableOrDefault() {
        return sortable == null || sortable;
    }

    public boolean groupableOrDefault() {
        return groupable == null || groupable;
    }

    public boolean defaultSelectedOrDefault() {
        return defaultSelected != null && defaultSelected;
    }

    public boolean showCodeOrDefault() {
        return showCode != null && showCode;
    }
}
