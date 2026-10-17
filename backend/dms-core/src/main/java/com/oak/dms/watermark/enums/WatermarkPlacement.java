package com.oak.dms.watermark.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.oak.dms.watermark.exception.WatermarkException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Where the watermark sits on the page (watermark_templates.placement,
 * watermark_jobs.request_snapshot). Two of the stored values contain a
 * hyphen, so unlike {@code ExportJobStatus} the constant name isn't the
 * stored value - {@link #getValue()} is.
 * <p>
 * Every placement is laid out once by the shared renderer and drawn as one
 * image by every format service, so a new placement is added here and in the
 * renderer, never per format.
 */
public enum WatermarkPlacement {

    /** Repeated diagonally across the whole page - the default, and the hardest to crop out. */
    TILED("tiled"),
    /** One mark in the middle of the page. */
    CENTERED("centered"),
    /** A small badge in the top-right corner, out of the way of body text. */
    CORNER("corner"),
    /**
     * One line along the top margin AND one along the bottom - both, always.
     * <p>
     * Not configurable in Phase 1 by choice. A mark only at the top is lost
     * the moment someone photographs the lower half of a page, and the
     * placement is named for both edges, so "both" is the behaviour worth
     * having as the single option rather than a default with a knob beside
     * it. Should a tenant ever need one edge only, it is a nullable
     * {@code edge} column on watermark_templates and a nullable field on
     * {@link com.oak.dms.watermark.dto.WatermarkSpec} - purely additive, no
     * backfill, and the renderer already lays out each edge separately.
     */
    HEADER_FOOTER("header-footer");

    private final String value;

    WatermarkPlacement(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** Strict parse - an unknown value is the caller's mistake, not a silent fallback to the default. */
    @JsonCreator
    public static WatermarkPlacement fromValue(String value) {
        for (WatermarkPlacement placement : values()) {
            if (placement.value.equals(value)) {
                return placement;
            }
        }
        throw WatermarkException.badRequest("WATERMARK_INVALID_PLACEMENT",
                "placement must be one of " + allValues() + " - got '" + value + "'.");
    }

    public static String allValues() {
        return Arrays.stream(values()).map(WatermarkPlacement::getValue).collect(Collectors.joining(", "));
    }
}
