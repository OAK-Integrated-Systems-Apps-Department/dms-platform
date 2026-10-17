package com.oak.dms.watermark.service;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.format.RenderTarget;
import com.oak.dms.watermark.format.WatermarkRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Proves, at request time, that the watermark can actually be drawn.
 *
 * <h2>Why this is worth a throwaway render</h2>
 * Two refusals can only be discovered by laying the text out: there is no
 * font on this server for the script it is written in, and the text cannot
 * fit the page at a readable size. Both are the user's to fix - shorten the
 * text, or ask an administrator for a font package - and both are useless
 * to them if they arrive as a failed job ten seconds after a 202. A failed
 * job also leaves a row somebody later has to explain.
 * <p>
 * So the mark is rendered once here, onto a reference page, and thrown away.
 * It costs roughly the same as one page of the real work (about 16ms warm),
 * paid on a request that was going to do far more than that anyway.
 *
 * <h2>Why a reference page and not the real one</h2>
 * The document's real page size isn't known without opening it, and opening
 * it is exactly what the size and format checks are trying to avoid doing on
 * the request thread. A4 is the honest approximation: it is what almost
 * every document in a DMS is, and the fit check it performs is the
 * conservative one. A genuinely smaller page - a half-page insert - can
 * still fail later in the worker, which is correct; what this stops is the
 * common case failing late.
 */
@Component
@RequiredArgsConstructor
public class WatermarkRenderGuard {

    /** A4 at the configured render DPI - see the class comment on why. */
    private static final double A4_WIDTH_POINTS = 595;
    private static final double A4_HEIGHT_POINTS = 842;

    private final WatermarkRenderer renderer;
    private final WatermarkProperties properties;

    /**
     * @throws com.oak.dms.watermark.exception.WatermarkException 422 if the
     *         text cannot be drawn - no font for the script, or too long to
     *         fit at a readable size
     */
    public void check(WatermarkSpec spec) {
        // resolve() is where the "no font for this script" refusal lives;
        // render() is where the fit check does. Both throw the user-facing
        // exception, so this method simply doesn't catch anything.
        renderer.render(spec, RenderTarget.ofPoints(A4_WIDTH_POINTS, A4_HEIGHT_POINTS,
                properties.getLimits().getRenderDpi()));
    }
}
