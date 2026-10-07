package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Draws one watermark onto a transparent image, which every format service
 * then stamps onto its own pages.
 *
 * <h2>Why one renderer, and why an image</h2>
 * PDF, Word, Excel, PowerPoint and JPEG have five unrelated ways of putting
 * text on a page, each with its own font embedding, its own encoding limits
 * and its own idea of where the origin is. Rendering text once here and
 * handing every format the same PNG means a watermark looks identical in all
 * five, non-Latin names work everywhere without embedding a font subset five
 * different ways, and a new placement is written once instead of five times.
 * <p>
 * The cost is honest and worth naming: the mark is raster, so it is neither
 * selectable nor searchable in the output, and on a very large page it is
 * softer than vector text would be. {@code dms.watermark.limits.render-dpi}
 * is the dial for that, capped by {@code max-render-pixels}.
 * <p>
 * Text is laid out with {@link TextLayout} and drawn as an outline rather than with
 * {@code drawString}. TextLayout applies the bidirectional algorithm and the
 * font's shaping tables, so Arabic joins and Hebrew runs right to left;
 * outlines rotate and composite predictably, antialias the
 * same way on every JVM, and - the reason that matters here - produce the
 * same pixels for the same input, which is what lets a caller cache one
 * image per page size instead of re-rendering per page.
 *
 * <h2>Placements</h2>
 * <ul>
 *   <li>{@code tiled} - repeated across the whole page. The default, and the
 *       one that survives cropping.</li>
 *   <li>{@code centered} - a single mark in the middle.</li>
 *   <li>{@code corner} - a small badge in the top-right, clear of body text.</li>
 *   <li>{@code header-footer} - one line along the top margin AND one along
 *       the bottom. Both, because a mark that appears only at the top is
 *       lost the moment someone photographs the lower half of a page.</li>
 * </ul>
 * Rotation is honoured for all four. For the two margin placements the
 * rotated bounding box is measured and the mark inset by it, so a rotated
 * badge stays on the page instead of being clipped at the edge.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatermarkRenderer {

    /** Gap between tiles, as a multiple of the text's own size. Enough to read, close enough to cover. */
    private static final double TILE_GAP_X = 0.6;
    private static final double TILE_GAP_Y = 2.2;
    /** Margin placements sit this fraction of the shorter page edge in from it. */
    private static final double MARGIN_INSET = 0.035;
    /** A corner badge is drawn smaller than the requested size - it is a label, not a statement. */
    private static final double CORNER_SCALE = 0.55;
    /** Stops a pathological tile count on a huge page with tiny text. */
    private static final int MAX_TILES = 4000;

    private final FontProvider fontProvider;
    private final WatermarkProperties properties;

    /**
     * The watermark for one surface, as a transparent ARGB image the caller
     * composites or embeds. Nothing outside the mark is touched: every other
     * pixel is fully transparent, so the page shows through.
     */
    public BufferedImage render(WatermarkSpec spec, RenderTarget target) {
        return render(spec, target, fontProvider.resolve(spec.text()));
    }

    /**
     * The same, with the font already decided. Format services resolve once
     * per job and pass the same {@link FontChoice} to every page geometry,
     * so a document can never end up with its appendix set in a different
     * typeface from its body - and so the "no font for this script" refusal
     * happens at request time rather than inside a worker thread.
     */
    public BufferedImage render(WatermarkSpec spec, RenderTarget target, FontChoice font) {
        RenderTarget capped = target.cappedTo(properties.getLimits().getMaxRenderPixels());
        if (capped != target) {
            log.debug("Watermark target {}x{} exceeded the render cap - rendering at {}x{}",
                    target.widthPx(), target.heightPx(), capped.widthPx(), capped.heightPx());
        }

        BufferedImage image = new BufferedImage(capped.widthPx(), capped.heightPx(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.setColor(colorOf(spec));
            graphics.setComposite(AlphaComposite.SrcOver);

            float sizePx = (float) (spec.fontSize() * capped.pointScale()
                    * (spec.placement() == WatermarkPlacement.CORNER
                        ? CORNER_SCALE : 1.0));
            FontRenderContext fontRenderContext = graphics.getFontRenderContext();
            Mark mark = layOut(spec.text(), Math.max(1f, sizePx), font, fontRenderContext);

            // A mark wider than the surface would be clipped at both ends,
            // and a watermark reading "NFIDENTIA" is worse than a smaller
            // one reading "CONFIDENTIAL". Found by rendering a long mixed
            // Arabic/Latin name: the guardrails cap text length and font
            // size independently, so their product can still outrun a small
            // page. Laid out once more at the size that fits.
            double fit = fitScale(mark, spec.rotation(), capped);
            if (fit < 1.0) {
                float shrunk = (float) (sizePx * fit);
                // ...but only down to a point. Shrinking without a floor
                // turns "this doesn't fit" into a watermark too small to
                // read, which looks like the feature worked and is harder to
                // diagnose than a refusal. Below the floor, say so.
                float floor = minimumRenderedSizePx(capped);
                if (shrunk < floor) {
                    throw WatermarkException.unprocessable("WATERMARK_TEXT_TOO_LONG_FOR_PAGE",
                            "This watermark text is too long to fit on the page at a readable size. "
                                    + "Shorten the text, or use a smaller font size.");
                }
                log.debug("Watermark too wide for {}x{} - shrinking to {}% to fit",
                        capped.widthPx(), capped.heightPx(), Math.round(fit * 100));
                mark = layOut(spec.text(), shrunk, font, fontRenderContext);
            }

            switch (spec.placement()) {
                case TILED -> drawTiled(graphics, mark, spec, capped);
                case CENTERED -> drawRotatedAt(graphics, mark, spec.rotation(),
                        capped.widthPx() / 2.0, capped.heightPx() / 2.0);
                case CORNER -> drawCorner(graphics, mark, spec, capped);
                case HEADER_FOOTER -> drawHeaderFooter(graphics, mark, spec, capped);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * The same image as PNG bytes - what the OOXML formats embed as a media
     * part and what the template preview endpoint returns. PNG because it is
     * the one lossless format with an alpha channel that Word, Excel,
     * PowerPoint and PDF all accept without conversion.
     */
    public byte[] renderPng(WatermarkSpec spec, RenderTarget target) {
        return encode(render(spec, target));
    }

    /**
     * The same, with the font already decided - what the three OOXML
     * services use, so one document embeds one typeface however many page
     * geometries it turns out to have.
     */
    public byte[] renderPng(WatermarkSpec spec, RenderTarget target, FontChoice font) {
        return encode(render(spec, target, font));
    }

    private byte[] encode(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw WatermarkException.unavailable("WATERMARK_NO_PNG_WRITER",
                        "This server has no PNG encoder available, so watermarks can't be produced.");
            }
        } catch (IOException e) {
            // In-memory stream: an IOException here is not a disk problem.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * One laid-out mark: its outline, positioned so the origin is the
     * baseline start, and the visual bounds of that outline.
     */
    private record Mark(Shape outline, Rectangle2D bounds) {
    }

    /**
     * Lays the text out properly, which for several scripts is not the same
     * thing as mapping characters to glyphs in order.
     * <p>
     * {@link TextLayout} is used rather than {@link Font#createGlyphVector}
     * because createGlyphVector performs no complex-script processing at
     * all. Caught by rendering real names inside the runtime image and
     * looking at them: Arabic came out as isolated, unjoined letterforms,
     * and Hebrew came out left-to-right. Both passed every "no missing
     * glyph" check, because every glyph WAS present - just the wrong glyphs,
     * in the wrong order. TextLayout applies the Unicode bidirectional
     * algorithm and the font's shaping tables, so joining, reordering,
     * conjuncts and right-to-left direction all come out as the reader of
     * that script expects.
     */
    private Mark layOut(String text, float sizePx, FontChoice choice, FontRenderContext fontRenderContext) {
        // Deliberately does NOT re-check canDisplayUpTo here.
        //
        // FontProvider.resolve has already established that this family can
        // draw this text, under its own lock, once for the job. Asking again
        // would do it on a worker thread, on a freshly DERIVED Font whose
        // composite fall-backs the JDK may still be building - which is the
        // very race the lock exists to avoid, reintroduced on the render
        // path. The check belongs at the request, where the person asking
        // can be told; by here the answer is settled.
        Font font = choice.at(sizePx);

        if (text.isBlank()) {
            throw WatermarkException.unprocessable("WATERMARK_NOT_RENDERABLE",
                    "The watermark text is blank, so there would be nothing to draw.");
        }

        TextLayout layout = new TextLayout(text, font, fontRenderContext);
        Shape outline = layout.getOutline(null);
        Rectangle2D bounds = outline.getBounds2D();
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            // No glyph had any ink. The caller must not go on to stamp a
            // blank image onto a document and call it watermarked.
            throw WatermarkException.unprocessable("WATERMARK_NOT_RENDERABLE",
                    "The watermark text could not be drawn with the fonts available on the server.");
        }
        return new Mark(outline, bounds);
    }

    // ------------------------------------------------------------ layout

    private void drawTiled(Graphics2D graphics, Mark mark,
                           WatermarkSpec spec, RenderTarget target) {
        // Tiles are laid out on an axis-aligned grid and each one rotated
        // about its own centre. Rotating the grid instead would leave bald
        // corners on the page, which is exactly where someone would crop.
        //
        // The SPACING is measured on the rotated footprint, not on the
        // unrotated text bounds. Those are two very different numbers: a
        // long mark turned 45 degrees occupies about 0.7 of its own length
        // horizontally, so spacing the grid by the unrotated width left a
        // gap wider than the tile itself at each end of every row. The page
        // came back with clean vertical strips down the left and right
        // edges - a border to crop to, which is the one thing tiling exists
        // to prevent. Found by looking at the samples; the edge test at the
        // time summed all four borders together and passed on the top and
        // bottom alone.
        double stepX = mark.bounds().getWidth() * (1 + TILE_GAP_X);
        double stepY = mark.bounds().getHeight() * TILE_GAP_Y;
        if (stepX <= 1 || stepY <= 1) {
            drawRotatedAt(graphics, mark, spec.rotation(),
                    target.widthPx() / 2.0, target.heightPx() / 2.0);
            return;
        }

        // The fallback is about the MARK being bigger than the surface, not
        // about the step being bigger. A long name on a thumbnail is wider
        // than the whole image, and tiling there would put every tile
        // centre off it and draw nothing readable. But a step wider than
        // the page is ordinary - it just means one column - and bailing out
        // on that condition turned a full A4 page of tiling into a single
        // diagonal mark the moment the surface grew to the whole sheet.
        Rectangle2D footprint = rotatedBounds(mark.bounds(), spec.rotation());
        if (footprint.getWidth() >= target.widthPx() || footprint.getHeight() >= target.heightPx()) {
            drawRotatedAt(graphics, mark, spec.rotation(),
                    target.widthPx() / 2.0, target.heightPx() / 2.0);
            return;
        }

        // The grid is ANCHORED to the surface rather than centred on it:
        // the step is rounded DOWN to a whole number of intervals across
        // the page, so a tile centre lands exactly on x=0 and on x=width,
        // and likewise top and bottom.
        //
        // Centring instead left the phase to chance. Whether a tile
        // happened to fall near an edge depended on how width divided by
        // the step, and on this page it did not: the nearest tile centre
        // sat half a step outside, its ink stopped short, and every tiled
        // page came back with a clean vertical strip down the left and
        // right - a border to crop to, which is the one thing tiling
        // exists to prevent. Nudging the overscan only moved the problem
        // to another edge, because that changes the phase too.
        // One interval is the normal case for a page barely wider than the
        // mark, and it is not a degenerate one: the half-pitch stagger
        // below puts even rows' tiles on the two EDGES and odd rows' tiles
        // in the MIDDLE, so the rows alternate and every column of the page
        // is covered by one or the other. Forcing a second interval instead
        // halves the pitch, and since the mark is nearly as wide as the
        // page that produced tiles overlapping each other two deep - the
        // page came back as unreadable clutter.
        int intervalsX = Math.max(1, (int) Math.ceil(target.widthPx() / stepX));
        int intervalsY = Math.max(1, (int) Math.ceil(target.heightPx() / stepY));
        double pitchX = target.widthPx() / (double) intervalsX;
        double pitchY = target.heightPx() / (double) intervalsY;

        // One extra ring outside the surface on every side, so rotated ink
        // and the half-step stagger below still reach past the border.
        int columns = intervalsX + 3;
        int rows = intervalsY + 3;
        if ((long) columns * rows > MAX_TILES) {
            log.debug("Watermark tiling would need {}x{} tiles - falling back to a single centred mark",
                    columns, rows);
            drawRotatedAt(graphics, mark, spec.rotation(),
                    target.widthPx() / 2.0, target.heightPx() / 2.0);
            return;
        }

        // Starts one pitch OUTSIDE the surface, so the ring of tiles that
        // never fully appears is what carries ink across each border.
        double startX = -pitchX;
        double startY = -pitchY;

        for (int row = 0; row < rows; row++) {
            // Every other row offset by half a pitch: a square grid leaves
            // readable horizontal lanes through the page, a staggered one
            // doesn't. The staggered rows are also why the overscan ring
            // above is needed - they are the rows that would otherwise stop
            // half a tile short of the left and right edges.
            double offsetX = row % 2 == 0 ? 0 : pitchX / 2;
            for (int column = 0; column < columns; column++) {
                drawRotatedAt(graphics, mark, spec.rotation(),
                        startX + column * pitchX + offsetX, startY + row * pitchY);
            }
        }
    }

    private void drawCorner(Graphics2D graphics, Mark mark,
                            WatermarkSpec spec, RenderTarget target) {
        double inset = Math.min(target.widthPx(), target.heightPx()) * MARGIN_INSET;
        Rectangle2D rotated = rotatedBounds(mark.bounds(), spec.rotation());
        // Positioned by the ROTATED box, so a rotated badge is inset by how
        // much room it actually needs rather than sliding off the corner.
        double centreX = target.widthPx() - inset - rotated.getWidth() / 2;
        double centreY = inset + rotated.getHeight() / 2;
        drawRotatedAt(graphics, mark, spec.rotation(), centreX, centreY);
    }

    private void drawHeaderFooter(Graphics2D graphics, Mark mark,
                                  WatermarkSpec spec, RenderTarget target) {
        double inset = Math.min(target.widthPx(), target.heightPx()) * MARGIN_INSET;
        Rectangle2D rotated = rotatedBounds(mark.bounds(), spec.rotation());
        double centreX = target.widthPx() / 2.0;
        drawRotatedAt(graphics, mark, spec.rotation(), centreX, inset + rotated.getHeight() / 2);
        drawRotatedAt(graphics, mark, spec.rotation(), centreX,
                target.heightPx() - inset - rotated.getHeight() / 2);
    }

    /** One mark, rotated about its own visual centre and placed with that centre at (x, y). */
    private void drawRotatedAt(Graphics2D graphics, Mark mark,
                               int rotationDegrees, double x, double y) {
        AffineTransform original = graphics.getTransform();
        try {
            graphics.translate(x, y);
            if (rotationDegrees != 0) {
                graphics.rotate(Math.toRadians(rotationDegrees));
            }
            // The outline's bounds are relative to the layout's own origin,
            // which is the baseline start - not the top-left of the ink.
            // Subtracting the bounds' offset is what actually centres it.
            graphics.translate(-mark.bounds().getWidth() / 2 - mark.bounds().getX(),
                    -mark.bounds().getHeight() / 2 - mark.bounds().getY());
            graphics.fill(mark.outline());
        } finally {
            graphics.setTransform(original);
        }
    }

    /**
     * The smallest the auto-shrink may go, in pixels on THIS surface.
     * <p>
     * Configured in points ({@code dms.watermark.style.min-font-size}, the
     * same floor a request or template is held to) and converted through the
     * target's own point scale, so the limit means the same physical size on
     * a 72 DPI page and a 300 DPI one rather than being a raw pixel count
     * that silently means something different per format.
     */
    private float minimumRenderedSizePx(RenderTarget target) {
        return (float) (properties.getStyle().getMinFontSize() * target.pointScale());
    }

    /**
     * How much the mark has to shrink to fit the surface once rotated, or 1
     * when it already does.
     * <p>
     * Deliberately leaves a margin: a mark touching the very edge looks
     * clipped even when it isn't, and the margin placements inset by that
     * same fraction anyway.
     */
    private static double fitScale(Mark mark, int rotationDegrees, RenderTarget target) {
        Rectangle2D rotated = rotatedBounds(mark.bounds(), rotationDegrees);
        double usableWidth = target.widthPx() * (1 - 2 * MARGIN_INSET);
        double usableHeight = target.heightPx() * (1 - 2 * MARGIN_INSET);
        if (rotated.getWidth() <= usableWidth && rotated.getHeight() <= usableHeight) {
            return 1.0;
        }
        return Math.max(0.05, Math.min(usableWidth / rotated.getWidth(), usableHeight / rotated.getHeight()));
    }

    /** How much room the mark needs once rotated - used to keep margin placements on the page. */
    private static Rectangle2D rotatedBounds(Rectangle2D bounds, int rotationDegrees) {
        if (rotationDegrees == 0) {
            return bounds;
        }
        return AffineTransform.getRotateInstance(Math.toRadians(rotationDegrees))
                .createTransformedShape(bounds).getBounds2D();
    }

    private static Color colorOf(WatermarkSpec spec) {
        String hex = spec.color();
        int rgb = Integer.parseInt(hex.substring(1), 16);
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, spec.alpha());
    }

    /** The font for this text, resolved once per job - see {@link FontProvider#resolve}. */
    public FontChoice resolveFont(String text) {
        return fontProvider.resolve(text);
    }

    /**
     * Exposed so the format services and the preview endpoint all size their
     * targets off the same configured DPI rather than each picking one.
     */
    public int renderDpi() {
        return properties.getLimits().getRenderDpi();
    }
}
