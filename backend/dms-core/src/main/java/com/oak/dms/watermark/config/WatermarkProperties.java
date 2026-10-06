package com.oak.dms.watermark.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code dms.watermark.*} - every limit the feature enforces, in one
 * place. The V68 CHECK constraints are deliberately wider than these: the
 * database stops nonsense, this stops bad watermarks, and only this is
 * meant to be tuned.
 * <p>
 * The {@link Style} bounds are the system defaults a request and a template
 * both fall through to. The precedence everywhere is
 * <em>request &gt; template &gt; here</em>.
 */
@Component
@ConfigurationProperties(prefix = "dms.watermark")
@Getter
@Setter
public class WatermarkProperties {

    /**
     * Off by default. While off the endpoints aren't mapped at all, so they
     * answer 404 - the feature doesn't exist rather than existing and
     * refusing. Not the same as the font probe failing at startup, which
     * leaves the endpoints mapped and answers 503 WATERMARK_UNAVAILABLE.
     */
    private boolean enabled = false;

    /** How long a produced copy is kept before the sweep deletes it. */
    private Duration outputTtl = Duration.ofHours(24);

    /** Watermark jobs one user may have pending or processing at once - the hard cap. */
    private int maxActiveJobsPerUser = 2;

    /**
     * Largest source file this will even open, per format, checked against
     * DocumentVersion.sizeBytes BEFORE a byte is read. Per format because
     * the cost is not the file, it is what the library does with it: POI
     * expands an xlsx to several times its stored size in memory, PDFBox
     * reads a PDF far more lazily. See application.yml for the arithmetic.
     */
    private Map<String, DataSize> maxBytes = new LinkedHashMap<>(Map.of(
            "pdf", DataSize.ofMegabytes(25),
            "docx", DataSize.ofMegabytes(25),
            "xlsx", DataSize.ofMegabytes(10),
            "pptx", DataSize.ofMegabytes(15),
            "image", DataSize.ofMegabytes(25)));

    /** Fallback for a format with no entry above. */
    private DataSize defaultMaxBytes = DataSize.ofMegabytes(25);

    /** How long one file may take to watermark before the job is failed and the thread abandoned. */
    private Duration formatTimeout = Duration.ofSeconds(60);

    /** A job still 'processing' this long after it started is failed rather than resumed at startup. */
    private Duration stuckAfter = Duration.ofMinutes(15);

    /**
     * Fonts to draw the watermark with, best first. The first one that can
     * display every character of the text wins - watermarks carry people's
     * names, and a missing glyph draws a hollow box rather than failing, so
     * the list should cover the scripts your tenants' users write in.
     * Families this JVM doesn't have are dropped at startup; a logical
     * family is always appended as the last resort.
     */
    private List<String> fontFamilies = new ArrayList<>(List.of(
            "DejaVu Sans", "Noto Sans", "Liberation Sans", "Arial", "SansSerif"));

    private final Style style = new Style();
    private final Limits limits = new Limits();
    private final Executor executor = new Executor();
    private final RateLimit rateLimit = new RateLimit();

    /**
     * The default look, and the range a request or template may move it
     * within. Opacity's floor is 5 rather than 0 on purpose: an invisible
     * watermark is indistinguishable from a missing one, and a user who set
     * it to 0 would believe their copy was marked when it wasn't.
     */
    @Getter
    @Setter
    public static class Style {
        private int fontSize = 40;
        private int minFontSize = 8;
        private int maxFontSize = 200;

        private int opacity = 25;
        private int minOpacity = 5;
        private int maxOpacity = 100;

        private int rotation = -45;
        private int minRotation = -90;
        private int maxRotation = 90;

        /** {@code #RRGGBB} only - no names, no alpha channel (opacity is its own setting). */
        private String color = "#808080";

        private String placement = "tiled";
        private String pageScope = "all-pages";

        /** Longest final text, after placeholders are resolved. */
        private int maxTextLength = 200;

        /** Longest any single resolved placeholder value may be before it is truncated. */
        private int maxTokenLength = 64;
    }

    /** Caps on what the format services will process. */
    @Getter
    @Setter
    public static class Limits {
        private int maxPages = 2000;
        private int maxSheets = 200;
        private int maxSlides = 500;
        /** Decoded image size. Checked from the header BEFORE decoding - a 50 MP decode is ~200 MB. */
        private long maxImagePixels = 50_000_000L;
        /** Distinct page sizes that get their own rendered tile within one document. */
        private int maxDistinctPageSizes = 4;
        /** Resolution the watermark image is rendered at before being drawn onto the page. */
        private int renderDpi = 150;
        /** Hard ceiling on the rendered tile, whatever the page size and DPI work out to. */
        private long maxRenderPixels = 16_000_000L;
        /** OOXML zip-bomb guards (ZipSecureFile). */
        private double minInflateRatio = 0.001d;
        private DataSize maxZipEntrySize = DataSize.ofMegabytes(256);
    }

    /** Both pools - see WatermarkExecutorConfig for why the format pool is the wider one. */
    @Getter
    @Setter
    public static class Executor {
        private int jobCoreSize = 2;
        private int jobMaxSize = 2;
        private int jobQueueCapacity = 20;
        /** Wider than the job pool so one thread abandoned by a timeout doesn't stall the feature. */
        private int formatMaxSize = 4;
        /** Sent as Retry-After when the queue is full. */
        private Duration retryAfter = Duration.ofSeconds(30);
    }

    /** Redis fixed window, per user and per IP. Fails OPEN - the active-job cap is the hard limit. */
    @Getter
    @Setter
    public static class RateLimit {
        private Duration window = Duration.ofMinutes(1);
        private int watermarksPerWindow = 10;
        private int previewsPerWindow = 30;
        /** The in-memory fallback used for previews when Redis can't be reached. */
        private int previewFallbackPerWindow = 30;
    }
}
