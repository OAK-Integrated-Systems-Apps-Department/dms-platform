package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Chooses a font that can actually draw the watermark text, and says
 * whether this JVM can draw text at all.
 *
 * <h2>Why this is not just {@code new Font("SansSerif", ...)}</h2>
 * Two different failures hide behind that one line.
 * <p>
 * The first is the deployment: the runtime image is
 * {@code eclipse-temurin:21-jre-alpine}, which ships with no fontconfig and
 * no font files. Asking AWT to measure or draw text there doesn't return
 * something ugly - it throws, deep inside the first job to run, long after
 * anyone could have noticed. {@link #probe()} forces that failure to happen
 * once at startup instead, where it becomes a clear log line and a 503
 * rather than a mystery.
 * <p>
 * The second is per-request: a watermark often carries a person's name, and
 * names are not Latin-1. A font that can't render a character draws a hollow
 * box, and a box in the middle of "CONFIDENTIAL - {username}" looks like a
 * bug in the document rather than a missing font on a server. So
 * {@link #resolve} asks each candidate how much of THIS text it can display
 * ({@link Font#canDisplayUpTo}) and takes the first that manages all of it,
 * falling back to whichever gets furthest.
 * <p>
 * The candidate list is {@code dms.watermark.font-families}, in order of
 * preference, ending in a logical family the JVM always maps to something.
 * Families the JVM doesn't have are dropped at startup, so the per-request
 * path never pays for a lookup that can't succeed.
 * <p>
 * That list is a <b>preference order, not an allowlist</b>. If none of the
 * configured families can draw the text, {@link #resolve} searches every
 * family installed on the machine. Without that, installing a CJK font
 * package in the runtime image would achieve nothing - the family is called
 * "Noto Sans CJK SC", which nobody would have thought to add to the
 * configuration - and the operator who installed it would have no way of
 * telling why their users' names still came out as boxes.
 */
@Slf4j
@Component
public class FontProvider {

    /**
     * Text the probe draws. Deliberately not plain ASCII: a broken font
     * setup sometimes still manages Latin-1 while failing on anything that
     * needs a real font file, and that is the setup that breaks on a name.
     */
    private static final String PROBE_TEXT = "Wq1 Åé Ω 中";

    /**
     * One short string per script family the image is expected to cover.
     * Touched once at startup so the JDK finishes building the logical
     * fonts' composites before any two jobs can ask about them at once -
     * see {@link #warmUp()}. A script with no font here is simply slower to
     * refuse later; the list costs nothing to be generous with.
     */
    private static final List<String> WARM_UP_TEXTS = List.of(
            "Wq1",                      // Latin
            "Åéü",       // Latin with marks
            "Αθ",             // Greek
            "Дм",             // Cyrillic
            "中文",             // Han
            "あカ",             // Kana
            "한글",             // Hangul
            "مح",             // Arabic
            "דו",             // Hebrew
            "रा",             // Devanagari
            "สุ");            // Thai

    private final List<String> families;
    /** Font objects are immutable and derivation is not free; one per (family, size) is plenty. */
    private final ConcurrentMap<String, Font> cache = new ConcurrentHashMap<>();
    /**
     * Every family this JVM has, in no particular order - the last resort
     * when none of the configured ones covers the text. Built once, lazily,
     * because on a well-configured server it is never needed.
     */
    private volatile List<String> allInstalled;

    public FontProvider(WatermarkProperties properties) {
        this.families = availableFrom(properties.getFontFamilies());
        warmUp();
    }

    /**
     * Resolves the font for one watermark, ONCE, before the job starts.
     * <p>
     * A document can need the mark drawn at several sizes - a portrait body
     * and a landscape foldout scale the point size differently - and asking
     * "which font can draw this?" again at each size invites two answers
     * inside one document. The decision belongs to the job, not to the page.
     *
     * @throws com.oak.dms.watermark.exception.WatermarkException 422 when no
     *         font on this server can draw the text. Raised HERE, at request
     *         time, so the person asking is told immediately rather than
     *         watching a job queue, run and fail - and so nothing is ever
     *         drawn as hollow boxes.
     */
    public FontChoice resolve(String text) {
        // Size 1: the question is which FAMILY can draw the text, which does
        // not depend on the size. FontChoice derives the sizes it needs.
        // The coverage check happens INSIDE choose(), under its lock, so no
        // composite font is ever touched off it.
        Font chosen = choose(text, 1f);
        if (coverageOf(chosen, text) >= 0) {
            throw WatermarkException.unprocessable("WATERMARK_SCRIPT_UNSUPPORTED",
                    "The watermark text uses characters this server has no font for, so it would print as "
                            + "empty boxes. Ask your administrator to install a font package covering that "
                            + "script.");
        }
        return new FontChoice(chosen);
    }

    /**
     * Forces the JDK to finish loading the logical fonts' composite
     * fall-backs, on one thread, before anything concurrent can ask about
     * them.
     * <p>
     * {@link Font#canDisplayUpTo} on a logical family consults a composite
     * whose physical fonts are loaded lazily. Two threads asking about the
     * same string during that window get different answers - observed on
     * both Windows and Alpine, where concurrent lookups for one CJK string
     * resolved to {@code SansSerif} on some threads and {@code Dialog} on
     * others. The consequence is not cosmetic: two concurrent jobs with the
     * same spec would render differently, and the "same spec, same pixels"
     * property {@code PdfWatermarkService} relies on to embed one image per
     * page geometry would stop holding.
     * <p>
     * Doing it once in the constructor removes the race, rather than
     * papering over it with a process-wide cache of decisions keyed by
     * user-supplied text.
     */
    private synchronized void warmUp() {
        try {
            // Every family, not only the configured ones, and not only one
            // script at a time. The lazy state is per composite font, so a
            // family whose composite is still half-built will answer
            // differently for a MIXED string (one CJK word plus one Greek
            // word) even after each script alone has been asked about. That
            // mixed case is exactly what a watermark is - a label in one
            // script and a name in another.
            String everything = String.join("", WARM_UP_TEXTS);
            for (String family : families) {
                at(family, 1f).canDisplayUpTo(everything);
            }
            for (String family : allInstalled()) {
                at(family, 1f).canDisplayUpTo(everything);
            }
        } catch (RuntimeException | LinkageError e) {
            log.warn("Could not warm up the watermark fonts: {}", e.toString());
            return;
        }
        for (String script : WARM_UP_TEXTS) {
            try {
                choose(script, 1f).canDisplayUpTo(script);
            } catch (RuntimeException | LinkageError e) {
                // A server with no usable fonts is reported by probe();
                // failing here would stop the application starting over a
                // feature that may not even be switched on.
                log.warn("Could not warm up the watermark fonts for '{}': {}", summarise(script), e.toString());
                return;
            }
        }
    }

    /**
     * Synchronized, deliberately.
     * <p>
     * {@link Font#canDisplayUpTo} on a logical family initialises its
     * composite fall-backs lazily, and two threads racing through that
     * initialisation get different answers - repeatedly observed as the same
     * CJK string resolving to {@code SansSerif} on one thread and
     * {@code Dialog} on another, on both Windows and Alpine. A startup
     * warm-up narrows the window but does not close it: the state is
     * per-composite and per-string, so no finite set of probe strings
     * covers it.
     * <p>
     * The lock is free in practice. Selection happens ONCE per job - see
     * {@link #resolve} and {@link FontChoice} - not once per page or per
     * geometry, so even a saturated job executor takes this monitor a
     * handful of times a second, for microseconds. Serialising it removes
     * the race outright, which a cache of decisions keyed by user-supplied
     * text only papered over.
     */
    /** canDisplayUpTo under the same monitor as {@link #choose} - see its comment. */
    private synchronized int coverageOf(Font font, String text) {
        return font.canDisplayUpTo(text);
    }

    private synchronized Font choose(String text, float sizePx) {
        Font best = null;
        int bestCoverage = -1;
        for (String family : families) {
            Font font = at(family, sizePx);
            int firstUnrenderable = font.canDisplayUpTo(text);
            if (firstUnrenderable < 0) {
                return font;
            }
            if (firstUnrenderable > bestCoverage) {
                bestCoverage = firstUnrenderable;
                best = font;
            }
        }
        // None of the CONFIGURED families covers it. Before giving up, look
        // at everything this JVM has.
        //
        // This matters more than it first appears. The configured list is a
        // preference order, not an allowlist - if it were an allowlist, then
        // installing a CJK font package in the image would do nothing at
        // all, because "Noto Sans CJK SC" isn't a name anybody thought to
        // add to the configuration. An operator adding a font package has
        // every right to expect that to be enough, and this is what makes it
        // so.
        for (String family : allInstalled()) {
            Font font = at(family, sizePx);
            int firstUnrenderable = font.canDisplayUpTo(text);
            if (firstUnrenderable < 0) {
                log.debug("No configured font covers '{}'; fell back to the installed family {}",
                        summarise(text), font.getFamily());
                return font;
            }
            if (firstUnrenderable > bestCoverage) {
                bestCoverage = firstUnrenderable;
                best = font;
            }
        }

        // Genuinely nothing on this machine can draw all of it. Take the
        // widest coverage, so at most the tail of the text degrades rather
        // than the whole mark.
        if (best != null) {
            log.warn("No font on this server can draw the whole watermark text; using {} ({} of {} characters). "
                            + "Install a font package covering the script in question - see backend/Dockerfile.",
                    best.getFamily(), bestCoverage, text.length());
            return best;
        }
        return at(Font.SANS_SERIF, sizePx);
    }

    private List<String> allInstalled() {
        List<String> cached = allInstalled;
        if (cached == null) {
            synchronized (this) {
                cached = allInstalled;
                if (cached == null) {
                    cached = installedFamilies();
                    allInstalled = cached;
                }
            }
        }
        return cached;
    }

    /** Enough of the text to identify the problem in a log, without logging the watermark itself. */
    private static String summarise(String text) {
        return text.length() <= 16 ? text : text.substring(0, 16) + "...";
    }

    /**
     * Can this JVM draw text at all? Called once at startup - see
     * {@code WatermarkAvailability}. Never throws: a false here turns the
     * feature off cleanly, while the exception it swallows would otherwise
     * surface as a 500 on somebody's first watermark.
     */
    public boolean probe() {
        try {
            BufferedImage canvas = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            var graphics = canvas.createGraphics();
            try {
                Font font = choose(PROBE_TEXT, 12f);
                graphics.setFont(font);
                // Measuring exercises the font machinery; drawing exercises
                // the rasteriser. A broken image can fail at either.
                if (graphics.getFontMetrics(font).stringWidth(PROBE_TEXT) <= 0) {
                    log.error("Watermark font probe measured zero width - this JVM has no usable fonts. "
                            + "Install fontconfig and a font package (see backend/Dockerfile).");
                    return false;
                }
                graphics.drawString(PROBE_TEXT, 0, 6);
            } finally {
                graphics.dispose();
            }
            log.info("Watermark font probe passed using '{}' (candidates: {})",
                    choose(PROBE_TEXT, 12f).getFamily(), families);
            return true;
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            log.error("Watermark font probe failed - this JVM cannot render text, so watermarking is "
                    + "unavailable. Install fontconfig and a font package (see backend/Dockerfile).", e);
            return false;
        }
    }

    /** The families actually usable here, in preference order - for diagnostics and the probe's log line. */
    public List<String> availableFamilies() {
        return families;
    }

    private Font at(String family, float sizePx) {
        return cache.computeIfAbsent(family + '' + sizePx,
                key -> new Font(family, Font.BOLD, 1).deriveFont(sizePx));
    }

    /**
     * Keeps the configured families this JVM actually has, plus a logical
     * family as the last resort. Logical names ({@code SansSerif},
     * {@code Serif}) are always "available" because the JVM maps them to
     * whatever it can find - which is the point of keeping one at the end,
     * and also why one can't be relied on alone: on an image with no fonts
     * at all the mapping resolves to nothing and only fails when used.
     */
    private static List<String> availableFrom(List<String> configured) {
        Set<String> installed = new LinkedHashSet<>();
        for (String name : installedFamilies()) {
            installed.add(name.toLowerCase(Locale.ROOT));
        }

        List<String> usable = new ArrayList<>();
        for (String family : configured) {
            if (family != null && !family.isBlank() && installed.contains(family.trim().toLowerCase(Locale.ROOT))) {
                usable.add(family.trim());
            }
        }
        if (!usable.contains(Font.SANS_SERIF)) {
            usable.add(Font.SANS_SERIF);
        }
        log.debug("Watermark fonts available, in preference order: {}", usable);
        return List.copyOf(usable);
    }

    /**
     * Every font family this JVM can see, sorted, or an empty list if it
     * can't see any.
     * <p>
     * The sort is not cosmetic. {@link GraphicsEnvironment#getAvailableFontFamilyNames}
     * makes no promise about order, and the lazy cache below is built under
     * double-checked locking, which permits two threads to build it at once.
     * Unsorted, those two builds can disagree, and then the font chosen for
     * a given text depends on which thread got there first - so the same
     * watermark renders differently on two concurrent jobs, and the "same
     * spec, same pixels" property the PDF per-geometry image cache rests on
     * quietly stops holding.
     * <p>
     * Caught by running the rendering tests inside the Alpine runtime image
     * in CI: two threads picked {@code SansSerif} and {@code Dialog} for the
     * same string. It had passed on Windows every time.
     */
    private static List<String> installedFamilies() {
        try {
            String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames(Locale.ROOT);
            String[] sorted = names.clone();
            java.util.Arrays.sort(sorted);
            return List.of(sorted);
        } catch (RuntimeException | LinkageError e) {
            log.warn("Could not list the installed fonts: {}", e.toString());
            return List.of();
        }
    }
}
