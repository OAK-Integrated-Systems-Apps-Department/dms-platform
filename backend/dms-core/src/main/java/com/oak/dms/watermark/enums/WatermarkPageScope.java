package com.oak.dms.watermark.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.oak.dms.watermark.exception.WatermarkException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which pages of the copy carry the watermark (watermark_templates.page_scope).
 * <p>
 * "First page" means the obvious thing for each format, which is not the
 * same thing in each: the first page of a PDF, the first page of the first
 * section of a Word document, the first <em>visible</em> worksheet of a
 * workbook, slide 1 of a deck. A single image has one page, so both scopes
 * do the same thing and {@link #FIRST_PAGE_ONLY} is accepted silently.
 */
public enum WatermarkPageScope {

    ALL_PAGES("all-pages"),
    FIRST_PAGE_ONLY("first-page-only");

    private final String value;

    WatermarkPageScope(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static WatermarkPageScope fromValue(String value) {
        for (WatermarkPageScope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }
        throw WatermarkException.badRequest("WATERMARK_INVALID_PAGE_SCOPE",
                "pageScope must be one of " + allValues() + " - got '" + value + "'.");
    }

    public static String allValues() {
        return Arrays.stream(values()).map(WatermarkPageScope::getValue).collect(Collectors.joining(", "));
    }
}
