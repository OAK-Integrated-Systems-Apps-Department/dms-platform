package com.oak.dms.watermark.format;

import java.awt.Font;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The font decided on for one watermark, resolved ONCE and then carried
 * through every page of the job.
 *
 * <h2>Why the decision is made once and passed around</h2>
 * A document can need the mark drawn at several sizes - a portrait body and
 * a landscape foldout have different page geometries, and the point size
 * scales with each. Asking "which font can draw this text?" again at each
 * size invites two answers inside a single document, and a report whose
 * appendix is set in a different typeface from its body looks like a forgery
 * rather than a watermark.
 * <p>
 * It also moves the only expensive part of font selection - scanning the
 * installed families when the configured ones don't cover the text - to once
 * per job instead of once per page geometry, and it moves the failure
 * ("nothing here can draw this") to the request, where the person asking can
 * be told, rather than into a worker thread where it becomes a failed job.
 * <p>
 * The size cache here is per-choice and therefore per-job, which is what
 * makes it safe: it holds at most a handful of entries and dies with the
 * job, unlike a process-wide cache keyed by user-supplied text.
 */
public final class FontChoice {

    private final Font base;
    private final ConcurrentMap<Float, Font> bySize = new ConcurrentHashMap<>();

    FontChoice(Font base) {
        this.base = base;
    }

    /** @param sizePx size in PIXELS, already scaled for the render DPI */
    public Font at(float sizePx) {
        return bySize.computeIfAbsent(sizePx, base::deriveFont);
    }

    public String family() {
        return base.getFamily();
    }

    @Override
    public String toString() {
        return "FontChoice[" + family() + "]";
    }
}
