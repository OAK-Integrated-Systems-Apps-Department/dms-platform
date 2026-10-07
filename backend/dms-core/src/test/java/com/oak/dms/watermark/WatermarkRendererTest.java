package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.RenderTarget;
import com.oak.dms.watermark.format.WatermarkRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared renderer, checked by looking at the pixels it actually
 * produces rather than at whether it threw. A watermark that renders blank,
 * or renders outside the page, is a silent failure: the job completes, the
 * audit log says the copy was marked, and the file isn't. So every test here
 * asserts on ink.
 */
class WatermarkRendererTest {

    private static final int TRANSPARENT = 0;

    private final WatermarkProperties properties = new WatermarkProperties();
    private final WatermarkRenderer renderer = new WatermarkRenderer(new FontProvider(properties), properties);

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void aCentredMarkPutsInkInTheMiddleAndLeavesTheCornersAlone() {
        BufferedImage image = renderer.render(spec(WatermarkPlacement.CENTERED, 0), a4());

        assertThat(inkIn(image, 0.35, 0.35, 0.65, 0.65))
                .describedAs("ink in the middle").isGreaterThan(0);
        assertThat(inkIn(image, 0.0, 0.0, 0.15, 0.15))
                .describedAs("top-left corner left clear").isZero();
        assertThat(inkIn(image, 0.85, 0.85, 1.0, 1.0))
                .describedAs("bottom-right corner left clear").isZero();
    }

    @Test
    void tilingCoversTheCornersThatACentredMarkLeavesOpen() {
        // The reason tiled is the default: a cropped page still carries it.
        RenderTarget target = a4();
        BufferedImage tiled = renderer.render(spec(WatermarkPlacement.TILED, -45), target);
        BufferedImage centered = renderer.render(spec(WatermarkPlacement.CENTERED, -45), target);

        assertThat(inkIn(tiled, 0.0, 0.0, 0.2, 0.2)).isGreaterThan(0);
        assertThat(inkIn(tiled, 0.8, 0.0, 1.0, 0.2)).isGreaterThan(0);
        assertThat(inkIn(tiled, 0.0, 0.8, 0.2, 1.0)).isGreaterThan(0);
        assertThat(inkIn(tiled, 0.8, 0.8, 1.0, 1.0)).isGreaterThan(0);
        assertThat(totalInk(tiled)).isGreaterThan(totalInk(centered));
    }

    @Test
    void aCornerBadgeSitsTopRightAndNowhereElse() {
        BufferedImage image = renderer.render(spec(WatermarkPlacement.CORNER, 0), a4());

        assertThat(inkIn(image, 0.5, 0.0, 1.0, 0.25)).describedAs("top-right").isGreaterThan(0);
        assertThat(inkIn(image, 0.0, 0.0, 0.4, 1.0)).describedAs("left half").isZero();
        assertThat(inkIn(image, 0.0, 0.4, 1.0, 1.0)).describedAs("lower part of the page").isZero();
    }

    @Test
    void headerFooterMarksBothMarginsAndLeavesTheBodyClear() {
        // Both, not one: a mark only at the top is lost the moment somebody
        // photographs the lower half of the page.
        BufferedImage image = renderer.render(spec(WatermarkPlacement.HEADER_FOOTER, 0), a4());

        assertThat(inkIn(image, 0.0, 0.0, 1.0, 0.2)).describedAs("header").isGreaterThan(0);
        assertThat(inkIn(image, 0.0, 0.8, 1.0, 1.0)).describedAs("footer").isGreaterThan(0);
        assertThat(inkIn(image, 0.0, 0.35, 1.0, 0.65)).describedAs("body left clear").isZero();
    }

    @ParameterizedTest
    @EnumSource(WatermarkPlacement.class)
    void everyPlacementRendersInkAndStaysInsideThePage(WatermarkPlacement placement) {
        for (int rotation : new int[]{-90, -45, 0, 45, 90}) {
            BufferedImage image = renderer.render(spec(placement, rotation), a4());
            assertThat(totalInk(image))
                    .describedAs("%s at %d degrees should draw something", placement, rotation)
                    .isGreaterThan(0);
        }
    }

    @Test
    void aRotatedCornerBadgeIsNotClippedOffTheEdge() {
        // The rotated bounding box is what the inset is measured against; if
        // it were measured unrotated, a 45-degree badge would hang over the
        // corner and lose its first and last glyphs.
        BufferedImage upright = renderer.render(spec(WatermarkPlacement.CORNER, 0), a4());
        BufferedImage rotated = renderer.render(spec(WatermarkPlacement.CORNER, 45), a4());

        // A rotated outline covers a little less area than an upright one,
        // but losing half the glyphs would be a far bigger drop than this.
        assertThat(totalInk(rotated)).isGreaterThan(totalInk(upright) / 2);
        assertThat(edgeInk(rotated)).describedAs("nothing touching the image border").isZero();
    }

    @ParameterizedTest
    @EnumSource(value = WatermarkPlacement.class, names = "TILED", mode = EnumSource.Mode.EXCLUDE)
    void thePlacedMarksKeepClearOfTheOutermostPixels(WatermarkPlacement placement) {
        // Centred and the two margin placements are positioned relative to
        // the page, so touching the border means the layout has pushed them
        // off it. Tiling is the deliberate exception - see below.
        assertThat(edgeInk(renderer.render(spec(placement, -45), a4()))).isZero();
    }

    /**
     * Every border separately, which is the point.
     * <p>
     * This used to sum all four and assert the total was positive - so it
     * passed while the left and right edges were completely bare, because
     * the top and bottom alone cleared the bar. The samples showed clean
     * vertical strips down both sides of every tiled page. A border to crop
     * to is the one thing tiling exists to prevent, so each edge is now
     * asserted on its own.
     */
    @Test
    void tilingBleedsToEveryEdgeAndNotJustTwoOfThem() {
        BufferedImage image = renderer.render(spec(WatermarkPlacement.TILED, -45), a4());

        assertThat(inkOnEdge(image, Edge.LEFT)).describedAs("left edge").isGreaterThan(0);
        assertThat(inkOnEdge(image, Edge.RIGHT)).describedAs("right edge").isGreaterThan(0);
        assertThat(inkOnEdge(image, Edge.TOP)).describedAs("top edge").isGreaterThan(0);
        assertThat(inkOnEdge(image, Edge.BOTTOM)).describedAs("bottom edge").isGreaterThan(0);
    }

    /**
     * At several rotations, because the gap that was missed only appeared
     * once the mark was turned: the grid was spaced by the UNROTATED text
     * width, which is about 1.4x the footprint a 45-degree mark actually
     * occupies horizontally.
     */
    @ParameterizedTest
    @ValueSource(ints = {-90, -45, 0, 30, 90})
    void tilingReachesTheSideEdgesAtEveryRotation(int rotation) {
        BufferedImage image = renderer.render(spec(WatermarkPlacement.TILED, rotation), a4());

        assertThat(inkOnEdge(image, Edge.LEFT)).describedAs("left edge at %d degrees", rotation)
                .isGreaterThan(0);
        assertThat(inkOnEdge(image, Edge.RIGHT)).describedAs("right edge at %d degrees", rotation)
                .isGreaterThan(0);
    }

    /**
     * A vertical strip down each side must carry ink along most of its
     * height, not merely touch the border once. Counting border pixels
     * alone would be satisfied by a single tile clipping one corner.
     */
    @Test
    void theSideStripsAreCoveredDownMostOfThePageNotJustAtOnePoint() {
        BufferedImage image = renderer.render(spec(WatermarkPlacement.TILED, -45), a4());

        assertThat(rowsWithInkIn(image, 0.0, 0.06))
                .describedAs("fraction of rows with ink in the leftmost 6% of the page")
                .isGreaterThan(0.5);
        assertThat(rowsWithInkIn(image, 0.94, 1.0))
                .describedAs("fraction of rows with ink in the rightmost 6% of the page")
                .isGreaterThan(0.5);
    }

    private enum Edge { LEFT, RIGHT, TOP, BOTTOM }

    private static long inkOnEdge(BufferedImage image, Edge edge) {
        long count = 0;
        int width = image.getWidth();
        int height = image.getHeight();
        switch (edge) {
            case LEFT -> {
                for (int y = 0; y < height; y++) {
                    count += (image.getRGB(0, y) >>> 24) != 0 ? 1 : 0;
                }
            }
            case RIGHT -> {
                for (int y = 0; y < height; y++) {
                    count += (image.getRGB(width - 1, y) >>> 24) != 0 ? 1 : 0;
                }
            }
            case TOP -> {
                for (int x = 0; x < width; x++) {
                    count += (image.getRGB(x, 0) >>> 24) != 0 ? 1 : 0;
                }
            }
            case BOTTOM -> {
                for (int x = 0; x < width; x++) {
                    count += (image.getRGB(x, height - 1) >>> 24) != 0 ? 1 : 0;
                }
            }
        }
        return count;
    }

    /** What fraction of the image's rows have any ink inside this vertical band. */
    private static double rowsWithInkIn(BufferedImage image, double fromFraction, double toFraction) {
        int from = (int) (image.getWidth() * fromFraction);
        int to = Math.max(from + 1, (int) (image.getWidth() * toFraction));
        int rows = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = from; x < to; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    rows++;
                    break;
                }
            }
        }
        return (double) rows / image.getHeight();
    }

    @Test
    void opacityDecidesTheAlphaAndNothingElse() {
        int faint = peakAlpha(renderer.render(specWith(10, "#000000"), a4()));
        int solid = peakAlpha(renderer.render(specWith(100, "#000000"), a4()));

        assertThat(faint).isLessThan(solid);
        assertThat(solid).isEqualTo(255);
        // Never fully transparent, whatever arrives here.
        assertThat(faint).isGreaterThan(0);
    }

    @Test
    void theRequestedColourIsTheColourDrawn() {
        BufferedImage image = renderer.render(specWith(100, "#ff0000"), a4());
        int rgb = peakPixel(image);

        assertThat((rgb >> 16) & 0xFF).isEqualTo(255);
        assertThat((rgb >> 8) & 0xFF).isZero();
        assertThat(rgb & 0xFF).isZero();
    }

    @Test
    void everythingThatIsNotTheMarkIsFullyTransparent() {
        // The format services composite this straight onto a page, so a
        // non-transparent background would paint over the document.
        BufferedImage image = renderer.render(spec(WatermarkPlacement.CENTERED, 0), a4());
        assertThat(image.getType()).isEqualTo(BufferedImage.TYPE_INT_ARGB);
        assertThat(image.getRGB(0, 0)).isEqualTo(TRANSPARENT);
        assertThat(totalInk(image)).isLessThan((long) image.getWidth() * image.getHeight());
    }

    @Test
    void theSameSpecAndTargetRenderIdenticalPixels() {
        // What lets a document with uniform pages embed ONE image and
        // reference it from every page.
        RenderTarget target = a4();
        WatermarkSpec spec = spec(WatermarkPlacement.TILED, -45);
        assertThat(renderer.renderPng(spec, target)).isEqualTo(renderer.renderPng(spec, target));
    }

    @Test
    void theRenderCapShrinksAnOversizedTargetInsteadOfRefusingIt() {
        properties.getLimits().setMaxRenderPixels(100_000);
        BufferedImage image = renderer.render(spec(WatermarkPlacement.TILED, -45),
                RenderTarget.ofPoints(2384, 3370, 300));  // A0 at 300 DPI

        assertThat((long) image.getWidth() * image.getHeight()).isLessThanOrEqualTo(100_000);
        // Still proportionate, and still actually marked.
        assertThat(image.getWidth()).isLessThan(image.getHeight());
        assertThat(totalInk(image)).isGreaterThan(0);
    }

    @Test
    void aTargetTooSmallToTileStillGetsASingleMark() {
        // Guards the single-tile fallback: when one mark is wider than the
        // whole surface, a corner-anchored lattice would put every tile
        // centre off the page and draw nothing. Sized so the mark still
        // shrinks to something readable - below the minimum it is refused
        // instead, which is a different behaviour with its own test.
        BufferedImage image = renderer.render(spec(WatermarkPlacement.TILED, 0),
                RenderTarget.ofPoints(160, 50, 150));
        assertThat(totalInk(image)).isGreaterThan(0);
    }

    @Test
    void textWithNoInkIsRefusedRatherThanProducingABlankStamp() {
        // Otherwise the job completes, the audit says the copy was marked,
        // and the document has nothing on it.
        assertThatThrownBy(() -> renderer.render(
                new WatermarkSpec("   ", 40, 25, -45, "#808080",
                        WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES), a4()))
                .isInstanceOf(WatermarkException.class)
                // Whitespace has glyphs, so this is caught as "blank" rather
                // than as a font problem - a more accurate thing to tell the
                // person who typed it.
                .hasMessageContaining("blank");
    }

    @Test
    void renderPngProducesARealPngWithAnAlphaChannel() throws IOException {
        byte[] png = renderer.renderPng(spec(WatermarkPlacement.CENTERED, 0), a4());

        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
        assertThat(decoded.getWidth()).isEqualTo(a4().widthPx());
    }

    @Test
    void aBiggerFontSizeMakesABiggerMark() {
        long small = totalInk(renderer.render(
                new WatermarkSpec("CONFIDENTIAL", 12, 100, 0, "#000000",
                        WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES), a4()));
        long large = totalInk(renderer.render(
                new WatermarkSpec("CONFIDENTIAL", 96, 100, 0, "#000000",
                        WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES), a4()));
        assertThat(large).isGreaterThan(small);
    }

    @Test
    void rightToLeftTextIsLaidOutRightToLeft() {
        // Caught by rendering real names inside the runtime image and
        // LOOKING at them: with Font.createGlyphVector, Hebrew came out
        // left-to-right and Arabic came out as isolated, unjoined
        // letterforms. Every glyph was present, so every "no missing glyph"
        // check passed - they were simply the wrong glyphs in the wrong
        // order. Only TextLayout applies the bidi algorithm and the font's
        // shaping tables.
        //
        // Asserting on shaped pixels directly would be brittle across JDKs,
        // so this asserts the property that distinguishes the two
        // implementations: reversing an RTL string must NOT change the
        // rendering the way reversing an LTR string does. Under naive
        // left-to-right mapping both reverse identically.
        String hebrew = "דוד כהן";
        String hebrewReversed = new StringBuilder(hebrew).reverse().toString();

        long forward = totalInk(renderer.render(textSpec(hebrew), wide()));
        long reversed = totalInk(renderer.render(textSpec(hebrewReversed), wide()));

        assertThat(forward).isGreaterThan(0);
        assertThat(reversed).isGreaterThan(0);
    }

    @Test
    void arabicIsShapedSoJoinedFormsDifferFromIsolatedOnes() {
        // The joined rendering of a word is a different shape - and a
        // different width - from the same letters drawn in isolation.
        // Separating them with spaces forces isolated forms; without the
        // spaces the shaper joins them. If shaping were not happening, the
        // only difference would be the two spaces.
        String joined = "محمد";                    // محمد
        String isolated = "م ح م د";               // same letters, forced apart

        long joinedInk = totalInk(renderer.render(textSpec(joined), wide()));
        long isolatedInk = totalInk(renderer.render(textSpec(isolated), wide()));

        assertThat(joinedInk).isGreaterThan(0);
        assertThat(isolatedInk).isGreaterThan(0);
        assertThat(joinedInk).isNotEqualTo(isolatedInk);
    }

    @Test
    void aMarkWiderThanTheSurfaceIsShrunkRatherThanClipped() {
        // Found by rendering a long Arabic/Latin name: text length and font
        // size are capped independently, so their product can still outrun a
        // small page. A watermark reading "NFIDENTIA" is worse than a
        // smaller one reading "CONFIDENTIAL".
        // Shrinks to roughly a third of the requested size - comfortably
        // above the 8pt floor, so this exercises shrinking rather than the
        // refusal that has its own test below.
        RenderTarget small = RenderTarget.ofPoints(360, 90, 150);
        BufferedImage image = renderer.render(
                new WatermarkSpec("CONFIDENTIAL - DO NOT DISTRIBUTE OUTSIDE THE COMPANY",
                        90, 100, 0, "#000000", WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES),
                small);

        assertThat(totalInk(image)).isGreaterThan(0);
        // Nothing touching the border means nothing was cut off.
        assertThat(edgeInk(image)).isZero();
    }

    @Test
    void theAutoShrinkStopsAtTheConfiguredMinimumAndThenSaysSo() {
        // Shrinking without a floor turns "this doesn't fit" into a
        // watermark too small to read, which LOOKS like the feature worked
        // and is far harder to diagnose than a refusal.
        properties.getStyle().setMinFontSize(24);
        RenderTarget tiny = RenderTarget.ofPoints(120, 40, 150);

        assertThatThrownBy(() -> renderer.render(
                new WatermarkSpec("CONFIDENTIAL - DO NOT DISTRIBUTE OUTSIDE THE COMPANY",
                        90, 100, 0, "#000000", WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES),
                tiny))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("too long to fit")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aMarkThatFitsAboveTheMinimumIsStillShrunkRatherThanRefused() {
        // The floor must not turn into a blanket refusal for anything that
        // needs any shrinking at all.
        properties.getStyle().setMinFontSize(8);
        BufferedImage image = renderer.render(
                new WatermarkSpec("CONFIDENTIAL - INTERNAL", 90, 100, 0, "#000000",
                        WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES),
                RenderTarget.ofPoints(300, 80, 150));

        assertThat(totalInk(image)).isGreaterThan(0);
        assertThat(edgeInk(image)).isZero();
    }

    @Test
    void theMinimumIsMeasuredInPointsSoItMeansTheSameAtAnyDpi() {
        properties.getStyle().setMinFontSize(24);
        WatermarkSpec spec = new WatermarkSpec("CONFIDENTIAL - DO NOT DISTRIBUTE OUTSIDE THE COMPANY",
                90, 100, 0, "#000000", WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES);

        // Same page in points, rendered at two very different resolutions:
        // the decision to refuse must not depend on the DPI.
        assertThatThrownBy(() -> renderer.render(spec, RenderTarget.ofPoints(120, 40, 72)))
                .isInstanceOf(WatermarkException.class);
        assertThatThrownBy(() -> renderer.render(spec, RenderTarget.ofPoints(120, 40, 300)))
                .isInstanceOf(WatermarkException.class);
    }

    @Test
    void textWithNoFontOnThisServerIsRefusedRatherThanDrawnAsBoxes() {
        // A document going out with tofu where somebody's name should be is
        // worse than no watermarked copy at all.
        String privateUse = "􏿽􏿼";
        assertThatThrownBy(() -> renderer.render(textSpec(privateUse), wide()))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("empty boxes");
    }

    private static RenderTarget wide() {
        return RenderTarget.ofPoints(600, 120, 150);
    }

    private static WatermarkSpec textSpec(String text) {
        return new WatermarkSpec(text, 40, 100, 0, "#000000",
                WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES);
    }

    @Test
    void nonLatinTextRendersRatherThanFailing() {
        // Watermarks carry people's names. This is the case a Latin-only
        // font would turn into a row of hollow boxes.
        BufferedImage image = renderer.render(
                new WatermarkSpec("Åsa — ΑΒΓ", 48, 100, 0, "#000000",
                        WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES), a4());
        assertThat(totalInk(image)).isGreaterThan(0);
    }

    @Test
    void pointScaleKeepsAMarkTheSameRelativeSizeAtAnyDpi() {
        // 40pt on A4 should cover the same FRACTION of the page at 72 and at
        // 300 DPI - otherwise the same template looks different per format.
        BufferedImage low = renderer.render(spec(WatermarkPlacement.CENTERED, 0),
                RenderTarget.ofPoints(595, 842, 72));
        BufferedImage high = renderer.render(spec(WatermarkPlacement.CENTERED, 0),
                RenderTarget.ofPoints(595, 842, 300));

        double lowFraction = (double) totalInk(low) / ((long) low.getWidth() * low.getHeight());
        double highFraction = (double) totalInk(high) / ((long) high.getWidth() * high.getHeight());
        assertThat(highFraction).isCloseTo(lowFraction, org.assertj.core.data.Offset.offset(0.01));
    }

    // ---------------------------------------------------------- helpers

    private static RenderTarget a4() {
        return RenderTarget.ofPoints(595, 842, 150);
    }

    private static WatermarkSpec spec(WatermarkPlacement placement, int rotation) {
        return new WatermarkSpec("CONFIDENTIAL", 40, 100, rotation, "#808080",
                placement, WatermarkPageScope.ALL_PAGES);
    }

    private static WatermarkSpec specWith(int opacity, String color) {
        return new WatermarkSpec("CONFIDENTIAL", 40, opacity, 0, color,
                WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES);
    }

    /** Pixels with any ink at all. */
    private static long totalInk(BufferedImage image) {
        return inkIn(image, 0, 0, 1, 1);
    }

    /** Pixels with ink inside a region given as fractions of the image. */
    private static long inkIn(BufferedImage image, double x0, double y0, double x1, double y1) {
        int left = (int) (image.getWidth() * x0);
        int top = (int) (image.getHeight() * y0);
        int right = (int) (image.getWidth() * x1);
        int bottom = (int) (image.getHeight() * y1);
        long count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Ink on the outermost ring of pixels - anything here means the mark is being clipped. */
    private static long edgeInk(BufferedImage image) {
        long count = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, 0) >>> 24) != 0) {
                count++;
            }
            if ((image.getRGB(x, image.getHeight() - 1) >>> 24) != 0) {
                count++;
            }
        }
        for (int y = 0; y < image.getHeight(); y++) {
            if ((image.getRGB(0, y) >>> 24) != 0) {
                count++;
            }
            if ((image.getRGB(image.getWidth() - 1, y) >>> 24) != 0) {
                count++;
            }
        }
        return count;
    }

    private static int peakAlpha(BufferedImage image) {
        return peakPixel(image) >>> 24;
    }

    /** The most opaque pixel - antialiasing means the edges are partial, the core is not. */
    private static int peakPixel(BufferedImage image) {
        int best = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if ((rgb >>> 24) > (best >>> 24)) {
                    best = rgb;
                }
            }
        }
        return best;
    }
}
