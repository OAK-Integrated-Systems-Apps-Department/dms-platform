package com.oak.dms.watermark.format;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The handful of pixel operations the format services share: turning an
 * image, mirroring it, and getting one into a colour model that can be drawn
 * on.
 * <p>
 * Each is a single obvious pass rather than a composed affine transform.
 * Rotation and mirroring appear in two unrelated places - EXIF correction
 * and matching a PDF page's {@code /Rotate} - and in both the cost of being
 * subtly wrong is a watermark running up the side of the page, which no test
 * that only checks "did it throw" would ever catch.
 */
final class Images {

    private Images() {
    }

    /**
     * The image turned clockwise by 0, 90, 180 or 270 degrees. Width and
     * height swap for the quarter turns.
     */
    static BufferedImage rotateClockwise(BufferedImage source, int degrees) {
        int turn = ((degrees % 360) + 360) % 360;
        if (turn == 0) {
            return source;
        }
        if (turn != 90 && turn != 180 && turn != 270) {
            throw new IllegalArgumentException("Only quarter turns are supported, got " + degrees);
        }

        int width = source.getWidth();
        int height = source.getHeight();
        boolean quarter = turn == 90 || turn == 270;
        BufferedImage result = new BufferedImage(quarter ? height : width, quarter ? width : height,
                typeOf(source));

        // Written out per pixel on purpose: a quarter turn is exact, so this
        // never resamples, never blurs an edge and never leaves a half-pixel
        // seam - all of which a general transform with interpolation can.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                switch (turn) {
                    case 90 -> result.setRGB(height - 1 - y, x, rgb);
                    case 180 -> result.setRGB(width - 1 - x, height - 1 - y, rgb);
                    default -> result.setRGB(y, width - 1 - x, rgb);
                }
            }
        }
        return result;
    }

    /** The image flipped left-to-right. */
    static BufferedImage mirrorHorizontal(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage result = new BufferedImage(width, height, typeOf(source));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result.setRGB(width - 1 - x, y, source.getRGB(x, y));
            }
        }
        return result;
    }

    /**
     * The image in a colour model that can be composited onto.
     * <p>
     * Indexed images (a 256-colour PNG or GIF) have no room in their palette
     * for the blend of a semi-transparent mark, so drawing onto one silently
     * posterises the watermark into whichever palette entries happen to be
     * closest. Grayscale and 16-bit-per-channel images have their own
     * versions of the same problem. Converting to a direct colour model
     * first costs one copy and makes the result predictable.
     *
     * @param keepAlpha true to keep (or add) an alpha channel - PNG output;
     *                  false to flatten onto white, since JPEG has no alpha
     *                  and would otherwise render transparency as black
     */
    static BufferedImage toDrawable(BufferedImage source, boolean keepAlpha) {
        int wanted = keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (source.getType() == wanted) {
            return source;
        }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), wanted);
        Graphics2D graphics = result.createGraphics();
        try {
            if (!keepAlpha) {
                // White, not black: a transparent PNG converted for JPEG
                // output should look like paper, not like a hole.
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
            }
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static int typeOf(BufferedImage source) {
        // Never carry an indexed or custom model through a rotation -
        // setRGB into one quantises every pixel.
        int type = source.getType();
        return type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB
                ? type
                : (source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }
}
