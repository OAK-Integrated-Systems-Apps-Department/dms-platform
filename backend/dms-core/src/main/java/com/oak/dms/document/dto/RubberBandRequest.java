package com.oak.dms.document.dto;

/**
 * A region the reviewer dragged over the page preview, in normalized
 * coordinates (0..1 of the page's width/height) -- the same frame every
 * stored {@code bbox_norm} lives in, so the selection means the same
 * thing regardless of what DPI the preview happened to render at or what
 * zoom the browser is showing.
 */
public record RubberBandRequest(
        int page,
        double x0,
        double y0,
        double x1,
        double y1) {
}
