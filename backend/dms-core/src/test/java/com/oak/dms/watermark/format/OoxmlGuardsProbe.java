package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;

/**
 * Lets the latency benchmark time {@code OoxmlGuards} on its own.
 * <p>
 * {@code OoxmlGuards} is package-private deliberately - it is an internal
 * detail of the format services and nothing outside should call it - so this
 * sits in the same package rather than widening the production class's
 * visibility for a measurement. The benchmark needs the split because "is
 * the new zip walk the expensive part?" is the first thing anyone will ask
 * of those numbers.
 */
public final class OoxmlGuardsProbe {

    private OoxmlGuardsProbe() {
    }

    public static void check(byte[] source, WatermarkProperties.Limits limits) {
        OoxmlGuards.check(source, limits);
    }
}
