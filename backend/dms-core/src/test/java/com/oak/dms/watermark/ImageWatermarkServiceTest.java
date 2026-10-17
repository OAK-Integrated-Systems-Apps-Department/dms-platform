package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.ImageWatermarkService;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The image format service. Every test looks at the output's pixels: a
 * watermark job that "succeeded" without changing the picture is the exact
 * failure this feature cannot afford.
 */
class ImageWatermarkServiceTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final WatermarkProperties properties = new WatermarkProperties();
    private final ImageWatermarkService service =
            new ImageWatermarkService(new WatermarkRenderer(new FontProvider(properties), properties), properties);

    @Test
    void itHandlesJpegAndPngAndNothingElse() {
        assertThat(service.supports("image/jpeg")).isTrue();
        assertThat(service.supports("image/png")).isTrue();
        assertThat(service.supports("image/gif")).isFalse();
        assertThat(service.supports("image/tiff")).isFalse();
        assertThat(service.supports("application/pdf")).isFalse();
        assertThat(service.supports(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png"})
    void theOutputOpensIsTheSameSizeAndIsVisiblyDifferentFromTheInput(String mime) throws IOException {
        byte[] source = plain(400, 300, mime);
        byte[] marked = run(source, mime, WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES);

        assertThat(marked).isNotEqualTo(source);
        BufferedImage output = decode(marked);
        assertThat(output).isNotNull();
        assertThat(output.getWidth()).isEqualTo(400);
        assertThat(output.getHeight()).isEqualTo(300);
        assertThat(changedPixels(decode(source), output))
                .describedAs("the mark must actually alter the picture")
                .isGreaterThan(500);
    }

    @ParameterizedTest
    @EnumSource(WatermarkPlacement.class)
    void everyPlacementAndScopeMarksTheImage(WatermarkPlacement placement) throws IOException {
        for (WatermarkPageScope scope : WatermarkPageScope.values()) {
            byte[] source = plain(400, 300, "image/png");
            BufferedImage output = decode(run(source, "image/png", placement, scope));
            assertThat(changedPixels(decode(source), output))
                    .describedAs("%s / %s should mark a single image", placement, scope)
                    .isGreaterThan(100);
        }
    }

    @Test
    void firstPageOnlyMarksASingleImageJustTheSameSinceItHasOnePage() throws IOException {
        byte[] source = plain(400, 300, "image/png");
        long all = changedPixels(decode(source),
                decode(run(source, "image/png", WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES)));
        long first = changedPixels(decode(source),
                decode(run(source, "image/png", WatermarkPlacement.CENTERED, WatermarkPageScope.FIRST_PAGE_ONLY)));
        assertThat(first).isEqualTo(all);
    }

    @Test
    void aPngKeepsItsTransparency() throws IOException {
        BufferedImage transparent = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = transparent.createGraphics();
        graphics.setColor(new Color(0, 0, 255, 255));
        graphics.fillRect(0, 0, 100, 200);   // left half opaque, right half transparent
        graphics.dispose();

        BufferedImage output = decode(run(encode(transparent, "png"), "image/png",
                WatermarkPlacement.CORNER, WatermarkPageScope.ALL_PAGES));

        assertThat(output.getColorModel().hasAlpha()).isTrue();
        // A corner badge sits top-right, so the bottom-right stays see-through.
        assertThat(output.getRGB(150, 190) >>> 24).isZero();
    }

    @Test
    void aJpegFlattensTransparencyOntoWhiteRatherThanBlack() throws IOException {
        // JPEG has no alpha. Converting naively renders every transparent
        // pixel black, which turns a logo on a transparent background into a
        // black rectangle.
        BufferedImage transparent = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        BufferedImage output = decode(run(encode(transparent, "png"), "image/jpeg",
                WatermarkPlacement.CORNER, WatermarkPageScope.ALL_PAGES));

        int corner = output.getRGB(10, 190);
        assertThat((corner >> 16) & 0xFF).isGreaterThan(200);
        assertThat((corner >> 8) & 0xFF).isGreaterThan(200);
        assertThat(corner & 0xFF).isGreaterThan(200);
    }

    @Test
    void anIndexedPngIsConvertedRatherThanPosterisingTheMark() throws IOException {
        // A 256-colour palette has no room for the blend of a translucent
        // mark; drawing straight onto one quantises it into whatever entries
        // happen to be nearest.
        BufferedImage indexed = new BufferedImage(300, 200, BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D graphics = indexed.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 300, 200);
        graphics.dispose();

        BufferedImage output = decode(run(encode(indexed, "png"), "image/png",
                WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));

        assertThat(output.getType()).isNotEqualTo(BufferedImage.TYPE_BYTE_INDEXED);
        assertThat(changedPixels(indexed, output)).isGreaterThan(500);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void aRotatedPhotoIsTurnedUprightBeforeTheMarkGoesOn(int orientation) throws IOException {
        // The whole point of reading EXIF: mark a sideways-decoding photo
        // without turning it first and the mark runs up the side of the
        // picture once a viewer applies the tag.
        byte[] source = ExifOrientationTest.jpegWithOrientation(orientation, true);
        BufferedImage output = decode(run(source, "image/jpeg",
                WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES));

        boolean quarterTurn = orientation >= 5;
        // The fixture is 40x20 landscape; orientations 5-8 make it portrait.
        assertThat(output.getWidth() > output.getHeight())
                .describedAs("orientation %d should%s stay landscape", orientation, quarterTurn ? " not" : "")
                .isEqualTo(!quarterTurn);
    }

    @Test
    void aPngIsNotPutThroughExifCorrection() throws IOException {
        // PNG has no EXIF orientation. Running the JPEG path over one would
        // be looking for a tag that cannot be there.
        byte[] source = plain(400, 300, "image/png");
        BufferedImage output = decode(run(source, "image/png",
                WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES));
        assertThat(output.getWidth()).isEqualTo(400);
        assertThat(output.getHeight()).isEqualTo(300);
    }

    @Test
    void anImageOverTheMegapixelCapIsRefusedFromItsHeaderAlone() throws IOException {
        properties.getLimits().setMaxImagePixels(10_000);   // 100x100
        byte[] source = plain(400, 300, "image/png");       // 120,000 pixels

        assertThatThrownBy(() -> run(source, "image/png", WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void garbageThatIsNotAnImageIsACleanFourTwoTwo() {
        assertThatThrownBy(() -> run("this is not an image".getBytes(), "image/png",
                WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("could not be read")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aTruncatedImageIsACleanFourTwoTwoRatherThanAStackTrace() throws IOException {
        byte[] full = plain(400, 300, "image/png");
        byte[] truncated = java.util.Arrays.copyOf(full, full.length / 3);

        assertThatThrownBy(() -> run(truncated, "image/png", WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class);
    }

    @Test
    void anEmptyInputIsRefusedRatherThanProducingAnEmptyOutput() {
        assertThatThrownBy(() -> run(new byte[0], "image/jpeg",
                WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class);
    }

    @Test
    void theMarkScalesWithThePictureRatherThanBeingFixedInPixels() throws IOException {
        // Fixed-pixel sizing would make a 40pt mark a speck on a 4000px photo
        // and a smear on a 300px thumbnail. Measured between two sizes that
        // are both comfortably large: at very small glyph sizes antialiasing
        // spreads ink over proportionally more pixels, so coverage is a noisy
        // proxy for size there even though the point size is exactly
        // proportional.
        assertThat(markedFraction(2400, 1800))
                .isCloseTo(markedFraction(1200, 900), org.assertj.core.data.Offset.offset(0.04));
    }

    @Test
    void evenASmallThumbnailGetsAProperlyProportionedMark() throws IOException {
        // The low end of the same rule: visible, and not so large it swallows
        // the picture.
        assertThat(markedFraction(300, 225)).isBetween(0.05, 0.40);
    }

    @Test
    void aDecompressionBombIsRefusedWithoutEverAllocatingItsPixels() throws IOException {
        // 20000x20000 of one colour is a few KB on disk and 1.6GB decoded.
        // The point of checking the header is that this costs nothing to
        // refuse - if it reached ImageIO.read the JVM would be dead before
        // any cap could fire. The fixture is built by writing a real PNG
        // header for those dimensions, so ImageIO reports them honestly.
        byte[] bomb = pngHeaderOnly(20000, 20000);
        properties.getLimits().setMaxImagePixels(50_000_000L);

        long before = System.nanoTime();
        assertThatThrownBy(() -> run(bomb, "image/png", WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        long millis = (System.nanoTime() - before) / 1_000_000;

        // Refused from the header: if it had decoded, this would be seconds
        // and hundreds of megabytes, not milliseconds.
        assertThat(millis).describedAs("refused in %dms", millis).isLessThan(2000);
    }

    @Test
    void theCapIsCheckedBeforeDecodingNotAfter() throws IOException {
        // Same point from the other side: a file whose header says it is
        // huge is refused even though its pixel data is truncated and could
        // never be decoded at all.
        properties.getLimits().setMaxImagePixels(1_000_000L);
        assertThatThrownBy(() -> run(pngHeaderOnly(5000, 5000), "image/png",
                WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("megapixels");
    }

    @Test
    void aCmykJpegIsRefusedWithAMessageSayingWhatToDoAboutIt() throws IOException {
        // ImageIO has no reader for Adobe's inverted-CMYK JPEG. The fixture
        // is a real JPEG whose SOF0 is patched to declare four components,
        // which is exactly what a CMYK file from a print workflow looks like
        // to the decoder.
        byte[] cmyk = jpegWithComponentCount(4);

        assertThatThrownBy(() -> run(cmyk, "image/jpeg", WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aSixteenBitPngIsConvertedRatherThanDrawnOntoDirectly() throws IOException {
        // 16 bits per channel has no TYPE_INT_* equivalent; compositing onto
        // one without converting first either fails or silently truncates.
        BufferedImage deep = new BufferedImage(200, 150, BufferedImage.TYPE_USHORT_GRAY);
        Graphics2D graphics = deep.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 200, 150);
        graphics.dispose();

        BufferedImage output = decode(run(encode(deep, "png"), "image/png",
                WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));

        assertThat(output).isNotNull();
        assertThat(output.getWidth()).isEqualTo(200);
        assertThat(changedPixels(Images16.toRgb(deep), output)).isGreaterThan(200);
    }

    @Test
    void jpegOutputUsesTheConfiguredQualityRatherThanTheEncoderDefault() throws IOException {
        // 0.92 is high enough that re-encoding is not what anyone notices.
        // Verified by comparison: the same image encoded by this service is
        // meaningfully larger than a low-quality encode of the same pixels.
        byte[] source = photographic(600, 400);
        byte[] marked = run(source, "image/jpeg", WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES);

        byte[] lowQuality = reencode(decode(marked), 0.3f);
        byte[] maxQuality = reencode(decode(marked), 1.0f);

        assertThat(marked.length)
                .describedAs("0.92 should be well above a 0.3 encode")
                .isGreaterThan(lowQuality.length);
        assertThat(marked.length)
                .describedAs("0.92 should be below a 1.0 encode")
                .isLessThan(maxQuality.length);
    }

    // ---------------------------------------------------------- helpers

    /** Re-encodes already-decoded pixels at a given quality, for size comparison. */
    private static byte[] reencode(BufferedImage image, float quality) throws IOException {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            javax.imageio.ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Noisy content, so JPEG quality actually affects the encoded size. */
    private static byte[] photographic(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return encode(image, "jpeg");
    }

    /** A valid PNG of the given size, written as one flat colour so the file stays tiny. */
    private static byte[] pngHeaderOnly(int width, int height) throws IOException {
        // TYPE_BYTE_BINARY keeps a 20000x20000 all-white PNG in the kilobytes
        // while still being a completely valid file that reports its real
        // dimensions from the IHDR chunk.
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        return encode(image, "png");
    }

    /** A real JPEG with its SOF0 component count patched - what a CMYK file looks like to a decoder. */
    private static byte[] jpegWithComponentCount(int components) throws IOException {
        byte[] jpeg = plain(80, 60, "image/jpeg");
        for (int i = 0; i + 9 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xC0) {
                // FFC0, length(2), precision(1), height(2), width(2), components(1)
                jpeg[i + 9] = (byte) components;
                return jpeg;
            }
        }
        throw new IllegalStateException("No SOF0 marker in the fixture");
    }

    /** Converts a non-RGB source so it can be compared against the service's output. */
    private static final class Images16 {
        static BufferedImage toRgb(BufferedImage source) {
            BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            graphics.drawImage(source, 0, 0, null);
            graphics.dispose();
            return rgb;
        }
    }

    private double markedFraction(int width, int height) throws IOException {
        byte[] source = plain(width, height, "image/png");
        BufferedImage output = decode(run(source, "image/png",
                WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));
        return (double) changedPixels(decode(source), output) / ((long) width * height);
    }

    private byte[] run(byte[] source, String mime, WatermarkPlacement placement, WatermarkPageScope scope)
            throws IOException {
        WatermarkSpec spec = new WatermarkSpec("CONFIDENTIAL", 40, 60, -45, "#ff0000", placement, scope);
        return service.watermark(new ByteArrayInputStream(source),
                new WatermarkOptions(spec, mime, "photo." + (mime.endsWith("png") ? "png" : "jpg")));
    }

    private static byte[] plain(int width, int height, String mime) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                mime.endsWith("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return encode(image, mime.endsWith("png") ? "png" : "jpeg");
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage toWrite = "jpeg".equals(format) && image.getColorModel().hasAlpha()
                ? stripAlpha(image) : image;
        assertThat(ImageIO.write(toWrite, format, out)).isTrue();
        return out.toByteArray();
    }

    private static BufferedImage stripAlpha(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return rgb;
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /** Pixels whose colour moved - JPEG is lossy, so an exact compare would be meaningless. */
    private static long changedPixels(BufferedImage before, BufferedImage after) {
        long changed = 0;
        for (int y = 0; y < Math.min(before.getHeight(), after.getHeight()); y++) {
            for (int x = 0; x < Math.min(before.getWidth(), after.getWidth()); x++) {
                int a = before.getRGB(x, y);
                int b = after.getRGB(x, y);
                if (Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) > 12
                        || Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) > 12
                        || Math.abs((a & 0xFF) - (b & 0xFF)) > 12) {
                    changed++;
                }
            }
        }
        return changed;
    }
}
