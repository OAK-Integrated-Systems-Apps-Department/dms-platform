package com.oak.dms.watermark.format;

import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;

/**
 * Reads the EXIF orientation tag out of a JPEG, and applies it.
 *
 * <h2>Why this exists at all</h2>
 * Phone cameras don't rotate the pixels they capture. They store the sensor's
 * raw landscape frame and record how the phone was held in EXIF tag 0x0112,
 * leaving the viewer to turn it. {@code ImageIO.read} ignores that tag
 * entirely, so a portrait photo decodes sideways - and a watermark drawn onto
 * it would be sideways too, on an image that every viewer then rotates
 * upright, leaving the mark running up the side of the picture.
 * <p>
 * So the orientation is applied to the pixels BEFORE the watermark is drawn,
 * and the output carries no EXIF at all. That is a real and deliberate loss -
 * the watermarked copy has no GPS, camera or capture time - and it is called
 * out in the API docs. The alternative, keeping the tag and drawing in the
 * pre-rotation frame, puts the mark at the mercy of whether each viewer
 * honours EXIF, and they don't agree.
 *
 * <h2>Why it is hand-rolled</h2>
 * One integer, at a well-specified offset, from a format that has not changed
 * in thirty years. A library for this would bring a full EXIF/IPTC/XMP parser
 * and a second licence for a single tag. Everything here is bounds-checked
 * and total: a truncated file, a garbage byte order, a tag of the wrong type,
 * a value outside 1-8 - every one of them yields orientation 1 (leave it
 * alone). This never throws and never refuses an image, because a strange
 * EXIF block is not a reason to deny somebody a watermark.
 */
@Slf4j
public final class ExifOrientation {

    /** "Leave the pixels as they are" - and what every unreadable case falls back to. */
    public static final int NORMAL = 1;

    private static final int JPEG_SOI = 0xFFD8;
    private static final int MARKER_APP1 = 0xFFE1;
    private static final int MARKER_SOS = 0xFFDA;
    private static final int TAG_ORIENTATION = 0x0112;
    private static final int TYPE_SHORT = 3;
    private static final byte[] EXIF_HEADER = {'E', 'x', 'i', 'f', 0, 0};

    private ExifOrientation() {
    }

    /**
     * The orientation this JPEG asks for: 1-8, or {@link #NORMAL} when there
     * is no tag, no EXIF block, or anything at all that doesn't parse.
     */
    public static int of(byte[] jpeg) {
        try {
            return parse(jpeg);
        } catch (RuntimeException e) {
            // Total by contract - a malformed EXIF block must not cost
            // somebody their watermark.
            log.debug("Unreadable EXIF block, treating the image as upright: {}", e.toString());
            return NORMAL;
        }
    }

    /**
     * The image turned the way the camera meant it to be seen. Returns the
     * input untouched for orientation 1, which is the overwhelming majority.
     */
    public static BufferedImage apply(BufferedImage image, int orientation) {
        // Every orientation is "mirror horizontally first, then rotate
        // clockwise" - the table from the EXIF spec, written out rather
        // than folded into one affine transform, because two cheap passes
        // on an image this size are worth far more than the half hour
        // somebody would otherwise spend working out which of eight
        // hand-derived matrices is subtly wrong.
        return switch (orientation) {
            case 2 -> Images.mirrorHorizontal(image);
            case 3 -> Images.rotateClockwise(image, 180);
            case 4 -> Images.rotateClockwise(Images.mirrorHorizontal(image), 180);
            case 5 -> Images.rotateClockwise(Images.mirrorHorizontal(image), 270);
            case 6 -> Images.rotateClockwise(image, 90);
            case 7 -> Images.rotateClockwise(Images.mirrorHorizontal(image), 90);
            case 8 -> Images.rotateClockwise(image, 270);
            default -> image;
        };
    }

    // ------------------------------------------------------------ parsing

    private static int parse(byte[] data) {
        if (data == null || data.length < 4 || u16(data, 0, true) != JPEG_SOI) {
            return NORMAL;
        }

        // Walk the marker segments looking for APP1. Stop at the start of
        // scan: everything after it is entropy-coded image data, and
        // scanning that for a two-byte pattern would find one eventually.
        int offset = 2;
        while (offset + 4 <= data.length) {
            if ((data[offset] & 0xFF) != 0xFF) {
                return NORMAL;  // not where a marker should be - give up quietly
            }
            int marker = u16(data, offset, true);
            if (marker == MARKER_SOS) {
                return NORMAL;
            }
            int segmentLength = u16(data, offset + 2, true);
            if (segmentLength < 2) {
                return NORMAL;
            }
            if (marker == MARKER_APP1 && startsWithExif(data, offset + 4)) {
                return fromTiff(data, offset + 4 + EXIF_HEADER.length,
                        Math.min(data.length, offset + 2 + segmentLength));
            }
            offset += 2 + segmentLength;
        }
        return NORMAL;
    }

    /** The TIFF block inside APP1: byte order, magic 42, offset to IFD0, then IFD0's entries. */
    private static int fromTiff(byte[] data, int tiffStart, int limit) {
        if (tiffStart + 8 > limit) {
            return NORMAL;
        }
        boolean bigEndian;
        int byteOrder = u16(data, tiffStart, true);
        if (byteOrder == 0x4D4D) {          // 'MM'
            bigEndian = true;
        } else if (byteOrder == 0x4949) {   // 'II'
            bigEndian = false;
        } else {
            return NORMAL;
        }
        if (u16(data, tiffStart + 2, bigEndian) != 42) {
            return NORMAL;
        }

        long ifdOffset = u32(data, tiffStart + 4, bigEndian);
        int ifd = (int) (tiffStart + ifdOffset);
        // ifdOffset is attacker-influenced; every use of it is bounds-checked.
        if (ifdOffset < 8 || ifd < tiffStart || ifd + 2 > limit) {
            return NORMAL;
        }

        int entries = u16(data, ifd, bigEndian);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > limit) {
                return NORMAL;  // truncated directory
            }
            if (u16(data, entry, bigEndian) != TAG_ORIENTATION) {
                continue;
            }
            if (u16(data, entry + 2, bigEndian) != TYPE_SHORT) {
                return NORMAL;  // right tag, wrong type - don't guess
            }
            // A SHORT fits in the value field, so it is stored inline, in
            // the field's first two bytes whatever the byte order.
            int value = u16(data, entry + 8, bigEndian);
            return value >= 1 && value <= 8 ? value : NORMAL;
        }
        return NORMAL;
    }

    private static boolean startsWithExif(byte[] data, int offset) {
        if (offset + EXIF_HEADER.length > data.length) {
            return false;
        }
        for (int i = 0; i < EXIF_HEADER.length; i++) {
            if (data[offset + i] != EXIF_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] data, int offset, boolean bigEndian) {
        if (offset + 2 > data.length || offset < 0) {
            throw new IndexOutOfBoundsException("16-bit read at " + offset + " of " + data.length);
        }
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        return bigEndian ? (first << 8) | second : (second << 8) | first;
    }

    private static long u32(byte[] data, int offset, boolean bigEndian) {
        if (offset + 4 > data.length || offset < 0) {
            throw new IndexOutOfBoundsException("32-bit read at " + offset + " of " + data.length);
        }
        long a = data[offset] & 0xFFL;
        long b = data[offset + 1] & 0xFFL;
        long c = data[offset + 2] & 0xFFL;
        long d = data[offset + 3] & 0xFFL;
        return bigEndian ? (a << 24) | (b << 16) | (c << 8) | d : (d << 24) | (c << 16) | (b << 8) | a;
    }
}
