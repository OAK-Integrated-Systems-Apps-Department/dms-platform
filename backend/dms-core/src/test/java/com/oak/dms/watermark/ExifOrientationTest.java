package com.oak.dms.watermark;

import com.oak.dms.watermark.format.ExifOrientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The EXIF orientation parser and the eight transforms it drives.
 * <p>
 * Everything here is built in code - there are no binary fixtures in this
 * repo - by writing a real JPEG with ImageIO and splicing a hand-built APP1
 * segment in front of it. That means the tests exercise the same byte layout
 * a camera produces, in both byte orders, rather than a mock.
 * <p>
 * The parser's contract is that it is TOTAL: every malformed, truncated or
 * nonsensical input yields orientation 1. A strange EXIF block is not a
 * reason to deny somebody a watermark, and an exception escaping here would
 * fail a job over metadata nobody asked about.
 */
class ExifOrientationTest {

    // --------------------------------------------------- reading the tag

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void everyValidOrientationIsReadBackInBothByteOrders(int orientation) throws IOException {
        assertThat(ExifOrientation.of(jpegWithOrientation(orientation, true)))
                .describedAs("big-endian (MM)").isEqualTo(orientation);
        assertThat(ExifOrientation.of(jpegWithOrientation(orientation, false)))
                .describedAs("little-endian (II)").isEqualTo(orientation);
    }

    @Test
    void aJpegWithNoExifAtAllIsTreatedAsUpright() throws IOException {
        assertThat(ExifOrientation.of(plainJpeg(40, 20))).isEqualTo(ExifOrientation.NORMAL);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 9, 255, 65535})
    void anOrientationOutsideOneToEightIsIgnored(int bogus) throws IOException {
        assertThat(ExifOrientation.of(jpegWithOrientation(bogus, true))).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void aTruncatedFileNeverThrowsAndNeverReturnsNonsense() throws IOException {
        byte[] full = jpegWithOrientation(6, true);
        // Every prefix of a real file, including ones that cut the APP1
        // segment, the TIFF header and the IFD directory in half.
        //
        // Note what is NOT asserted: that a truncated file always reads as
        // upright. The orientation entry is complete well before the file
        // is, so a prefix that happens to contain the whole 12-byte entry
        // legitimately still reports 6 - the tag really is there. The
        // contract is that this is total and always sane, not that
        // truncation destroys the tag.
        for (int length = 0; length <= full.length; length++) {
            byte[] truncated = Arrays.copyOf(full, length);
            assertThat(ExifOrientation.of(truncated))
                    .describedAs("truncated to %d bytes", length)
                    .isBetween(1, 8);
        }
    }

    @Test
    void aFileCutBeforeTheOrientationEntryEndsReadsAsUpright() throws IOException {
        byte[] full = jpegWithOrientation(6, true);
        // TIFF header (8) + entry count (2) + the whole 12-byte entry: the
        // parser requires the complete entry, not merely the two value bytes
        // inside it, before it will trust what it reads.
        int entryEnd = indexOfExif(full) + 6 + 8 + 2 + 12;

        assertThat(ExifOrientation.of(Arrays.copyOf(full, entryEnd - 1)))
                .describedAs("one byte short of a complete entry")
                .isEqualTo(ExifOrientation.NORMAL);
        // With the entry complete it reads - which is why the loop above can
        // only assert sanity, not that truncation destroys the tag.
        assertThat(ExifOrientation.of(Arrays.copyOf(full, entryEnd))).isEqualTo(6);
    }

    @Test
    void garbageWhereTheExifShouldBeDoesNotThrow() throws IOException {
        byte[] jpeg = jpegWithOrientation(6, true);
        byte[] corrupted = jpeg.clone();
        // Scribble over the TIFF header and the IFD, leaving the APP1 marker
        // and length intact so the parser definitely walks into it.
        for (int i = 10; i < Math.min(60, corrupted.length); i++) {
            corrupted[i] = (byte) 0xA5;
        }
        assertThat(ExifOrientation.of(corrupted)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void aBogusByteOrderMarkIsIgnored() throws IOException {
        byte[] jpeg = jpegWithOrientation(8, true);
        int tiffStart = indexOfExif(jpeg) + 6;
        jpeg[tiffStart] = 'X';
        jpeg[tiffStart + 1] = 'Y';
        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void anIfdOffsetPointingOutsideTheSegmentIsIgnored() throws IOException {
        byte[] jpeg = jpegWithOrientation(8, true);
        int tiffStart = indexOfExif(jpeg) + 6;
        // Offset to IFD0 lives at TIFF+4, big-endian here.
        jpeg[tiffStart + 4] = 0x7F;
        jpeg[tiffStart + 5] = (byte) 0xFF;
        jpeg[tiffStart + 6] = (byte) 0xFF;
        jpeg[tiffStart + 7] = (byte) 0xFF;
        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void theRightTagWithTheWrongTypeIsNotGuessedAt() throws IOException {
        byte[] jpeg = jpegWithOrientation(6, true);
        int tiffStart = indexOfExif(jpeg) + 6;
        int entry = tiffStart + 8 + 2;   // IFD0 is at TIFF+8, entries start after the 2-byte count
        jpeg[entry + 2] = 0;
        jpeg[entry + 3] = 4;             // LONG instead of SHORT
        assertThat(ExifOrientation.of(jpeg)).isEqualTo(ExifOrientation.NORMAL);
    }

    @Test
    void nullAndEmptyAndNonJpegInputAreAllUpright() {
        assertThat(ExifOrientation.of(null)).isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of(new byte[0])).isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of("not a jpeg at all".getBytes())).isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of(new byte[]{(byte) 0xFF, (byte) 0xD8})).isEqualTo(ExifOrientation.NORMAL);
    }

    // ------------------------------------------------- applying the turn

    /**
     * The EXIF table, as dimensions. A 40x20 landscape source becomes
     * portrait for the four quarter-turn orientations (5-8) and stays
     * landscape for the rest.
     */
    @ParameterizedTest
    @CsvSource({
            "1, 40, 20",
            "2, 40, 20",
            "3, 40, 20",
            "4, 40, 20",
            "5, 20, 40",
            "6, 20, 40",
            "7, 20, 40",
            "8, 20, 40"
    })
    void eachOrientationProducesTheExpectedShape(int orientation, int expectedWidth, int expectedHeight) {
        BufferedImage corrected = ExifOrientation.apply(marker(40, 20), orientation);
        assertThat(corrected.getWidth()).isEqualTo(expectedWidth);
        assertThat(corrected.getHeight()).isEqualTo(expectedHeight);
    }

    /**
     * Where the marked corner ends up. The source has a single red block in
     * its top-left; after correction it must land where the EXIF spec says.
     * This is what separates a rotation from a mirror - the two produce
     * identical dimensions and only differ in which corner moves where, so a
     * dimensions-only test would pass with 2 and 4 swapped.
     */
    @ParameterizedTest
    @CsvSource({
            "1, TOP_LEFT",
            "2, TOP_RIGHT",      // mirror horizontal
            "3, BOTTOM_RIGHT",   // rotate 180
            "4, BOTTOM_LEFT",    // mirror vertical
            "5, TOP_LEFT",       // mirror horizontal + rotate 270 CW (transpose)
            "6, TOP_RIGHT",      // rotate 90 CW
            "7, BOTTOM_RIGHT",   // mirror horizontal + rotate 90 CW (transverse)
            "8, BOTTOM_LEFT"     // rotate 270 CW
    })
    void theMarkedCornerMovesWhereTheSpecSaysItShould(int orientation, String expectedCorner) {
        BufferedImage corrected = ExifOrientation.apply(marker(40, 20), orientation);
        assertThat(cornerWithMark(corrected)).isEqualTo(expectedCorner);
    }

    @Test
    void orientationOneIsReturnedUntouchedRatherThanCopied() {
        // The overwhelming majority of images. Copying them would be a
        // pointless full-image allocation on every single watermark.
        BufferedImage source = marker(40, 20);
        assertThat(ExifOrientation.apply(source, 1)).isSameAs(source);
    }

    @Test
    void anUnknownOrientationLeavesTheImageAlone() {
        BufferedImage source = marker(40, 20);
        assertThat(ExifOrientation.apply(source, 99)).isSameAs(source);
        assertThat(ExifOrientation.apply(source, 0)).isSameAs(source);
    }

    @Test
    void theMirroredOrientationsAreNotJustRotations() {
        // 2 and 4 have the same dimensions as 1 and 3; 5 and 7 the same as 6
        // and 8. If apply() ever collapsed a mirror into a rotation, the
        // dimension tests above would still pass.
        BufferedImage source = marker(40, 20);
        assertThat(pixels(ExifOrientation.apply(source, 2)))
                .isNotEqualTo(pixels(ExifOrientation.apply(source, 1)));
        assertThat(pixels(ExifOrientation.apply(source, 4)))
                .isNotEqualTo(pixels(ExifOrientation.apply(source, 3)));
        assertThat(pixels(ExifOrientation.apply(source, 5)))
                .isNotEqualTo(pixels(ExifOrientation.apply(source, 8)));
        assertThat(pixels(ExifOrientation.apply(source, 7)))
                .isNotEqualTo(pixels(ExifOrientation.apply(source, 6)));
    }

    @Test
    void applyingTheSameOrientationTwiceIsNotAssumedToBeIdentity() {
        // Guards against someone "simplifying" apply() into something that
        // silently self-cancels for the mirror cases.
        BufferedImage once = ExifOrientation.apply(marker(40, 20), 6);
        BufferedImage twice = ExifOrientation.apply(once, 6);
        assertThat(twice.getWidth()).isEqualTo(40);
        assertThat(cornerWithMark(twice)).isEqualTo("BOTTOM_RIGHT");
    }

    // ---------------------------------------------------------- fixtures

    /** A plain image with one red block in the top-left eighth. */
    private static BufferedImage marker(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, width / 4, height / 4);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Which corner the red block sits in now. */
    private static String cornerWithMark(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (isRed(image, 1, 1)) {
            return "TOP_LEFT";
        }
        if (isRed(image, w - 2, 1)) {
            return "TOP_RIGHT";
        }
        if (isRed(image, 1, h - 2)) {
            return "BOTTOM_LEFT";
        }
        if (isRed(image, w - 2, h - 2)) {
            return "BOTTOM_RIGHT";
        }
        return "NONE";
    }

    private static boolean isRed(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        return ((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) < 80 && (rgb & 0xFF) < 80;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static byte[] plainJpeg(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(marker(width, height), "jpeg", out);
        return out.toByteArray();
    }

    /**
     * A real JPEG with a hand-built APP1/EXIF segment spliced in straight
     * after the SOI - exactly where a camera puts it.
     */
    static byte[] jpegWithOrientation(int orientation, boolean bigEndian) throws IOException {
        byte[] jpeg = plainJpeg(40, 20);
        byte[] app1 = app1Segment(orientation, bigEndian);

        byte[] result = new byte[jpeg.length + app1.length];
        result[0] = jpeg[0];                                        // FF
        result[1] = jpeg[1];                                        // D8
        System.arraycopy(app1, 0, result, 2, app1.length);
        System.arraycopy(jpeg, 2, result, 2 + app1.length, jpeg.length - 2);
        return result;
    }

    /** FFE1, length, "Exif\0\0", TIFF header, IFD0 with one entry: Orientation. */
    private static byte[] app1Segment(int orientation, boolean bigEndian) {
        // 6 (Exif header) + 8 (TIFF header) + 2 (entry count) + 12 (entry) + 4 (next-IFD offset)
        int payload = 6 + 8 + 2 + 12 + 4;
        byte[] segment = new byte[2 + 2 + payload];
        int p = 0;
        segment[p++] = (byte) 0xFF;
        segment[p++] = (byte) 0xE1;
        segment[p++] = (byte) ((payload + 2) >> 8);
        segment[p++] = (byte) ((payload + 2) & 0xFF);

        segment[p++] = 'E';
        segment[p++] = 'x';
        segment[p++] = 'i';
        segment[p++] = 'f';
        segment[p++] = 0;
        segment[p++] = 0;

        int tiff = p;
        segment[p++] = (byte) (bigEndian ? 'M' : 'I');
        segment[p++] = (byte) (bigEndian ? 'M' : 'I');
        p = put16(segment, p, 42, bigEndian);
        p = put32(segment, p, 8, bigEndian);          // IFD0 sits right after the header

        assert p == tiff + 8;
        p = put16(segment, p, 1, bigEndian);          // one entry
        p = put16(segment, p, 0x0112, bigEndian);     // Orientation
        p = put16(segment, p, 3, bigEndian);          // SHORT
        p = put32(segment, p, 1, bigEndian);          // count
        p = put16(segment, p, orientation, bigEndian);// value, inline
        p = put16(segment, p, 0, bigEndian);          // padding of the 4-byte value field
        put32(segment, p, 0, bigEndian);              // no next IFD
        return segment;
    }

    private static int put16(byte[] target, int offset, int value, boolean bigEndian) {
        target[offset] = (byte) (bigEndian ? value >> 8 : value & 0xFF);
        target[offset + 1] = (byte) (bigEndian ? value & 0xFF : value >> 8);
        return offset + 2;
    }

    private static int put32(byte[] target, int offset, int value, boolean bigEndian) {
        for (int i = 0; i < 4; i++) {
            int shift = bigEndian ? (24 - 8 * i) : (8 * i);
            target[offset + i] = (byte) ((value >> shift) & 0xFF);
        }
        return offset + 4;
    }

    private static int indexOfExif(byte[] data) {
        for (int i = 0; i + 6 <= data.length; i++) {
            if (data[i] == 'E' && data[i + 1] == 'x' && data[i + 2] == 'i' && data[i + 3] == 'f'
                    && data[i + 4] == 0 && data[i + 5] == 0) {
                return i;
            }
        }
        throw new IllegalStateException("No Exif header in the fixture");
    }
}
