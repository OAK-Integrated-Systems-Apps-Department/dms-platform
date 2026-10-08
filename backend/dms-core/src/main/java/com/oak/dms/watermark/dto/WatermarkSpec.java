package com.oak.dms.watermark.dto;

import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;

/**
 * A watermark, fully decided: the final text with every placeholder already
 * substituted, and the effective style after request, template and system
 * defaults have been merged.
 * <p>
 * This is what gets written to {@code watermark_jobs.request_snapshot}, and
 * it is the ONLY thing the worker reads when it draws. Nothing downstream
 * re-resolves a placeholder or re-reads a template, which is what makes the
 * audit entry trustworthy: the text recorded when the job was requested is
 * necessarily the text on the page, even if the user was renamed, the
 * template rewritten or the clock moved on in between.
 * <p>
 * Serialised by the project's ordinary Jackson {@code ObjectMapper}.
 * {@link WatermarkPlacement} and {@link WatermarkPageScope} carry
 * {@code @JsonValue}/{@code @JsonCreator}, so JSON holds the same hyphenated
 * wire values the API and the database use rather than the Java constant
 * names.
 *
 * @param text      already resolved and sanitised - never a template string
 * @param color     {@code #rrggbb}, lowercase
 * @param opacity   5-100; never 0, so a watermark can't be invisible
 */
public record WatermarkSpec(
        String text,
        int fontSize,
        int opacity,
        int rotation,
        String color,
        WatermarkPlacement placement,
        WatermarkPageScope pageScope
) {

    /**
     * 1-255, the alpha the renderer actually composites with. Never 0 even
     * if something upstream let a 0 through - {@code Math.clamp} would be
     * the obvious call here but it is Java 21, and this module compiles at
     * language level 17.
     */
    public int alpha() {
        return Math.max(1, Math.min(255, Math.round(opacity * 255f / 100f)));
    }

    public boolean firstPageOnly() {
        return pageScope == WatermarkPageScope.FIRST_PAGE_ONLY;
    }
}
