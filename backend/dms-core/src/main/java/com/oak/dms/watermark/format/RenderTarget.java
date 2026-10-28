package com.oak.dms.watermark.format;

/**
 * The surface one watermark image is being rendered for: how big it is in
 * pixels, and how a point of the user's requested font size maps onto it.
 * <p>
 * Formats measure pages in units of their own - PDF and PowerPoint in
 * points, Word in twips, Excel in EMUs, an image in its own pixels - so each
 * format service converts once, here, and the renderer only ever deals in
 * pixels. {@code pointScale} is what keeps a "40pt" watermark looking the
 * same size on an A4 page and on a slide: the format service works out how
 * many pixels one point is on ITS surface and the renderer multiplies.
 * <p>
 * Two targets that are {@code equals} produce byte-identical images for the
 * same spec, which is what lets a document with uniform page sizes embed one
 * image and reference it from every page instead of one per page.
 *
 * @param widthPx    the rendered image's width in pixels
 * @param heightPx   the rendered image's height in pixels
 * @param pointScale pixels per point on this surface (e.g. 150 DPI / 72 for
 *                   a PDF page rendered at 150 DPI)
 */
public record RenderTarget(int widthPx, int heightPx, double pointScale) {

    public RenderTarget {
        if (widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("Render target must have a positive size, got "
                    + widthPx + "x" + heightPx);
        }
        if (!(pointScale > 0) || Double.isInfinite(pointScale)) {
            throw new IllegalArgumentException("Render target needs a finite positive point scale, got " + pointScale);
        }
    }

    public long pixels() {
        return (long) widthPx * heightPx;
    }

    /**
     * The same target shrunk to fit within {@code maxPixels}, keeping its
     * aspect ratio and its point scale in step so text stays proportionate.
     * Returns {@code this} when it already fits.
     */
    public RenderTarget cappedTo(long maxPixels) {
        if (maxPixels <= 0 || pixels() <= maxPixels) {
            return this;
        }
        double factor = Math.sqrt((double) maxPixels / pixels());
        return new RenderTarget(Math.max(1, (int) Math.floor(widthPx * factor)),
                Math.max(1, (int) Math.floor(heightPx * factor)),
                pointScale * factor);
    }

    /** A surface measured in points (PDF, PowerPoint), rendered at {@code dpi}. */
    public static RenderTarget ofPoints(double widthPoints, double heightPoints, int dpi) {
        double scale = dpi / 72.0;
        return new RenderTarget(Math.max(1, (int) Math.round(widthPoints * scale)),
                Math.max(1, (int) Math.round(heightPoints * scale)), scale);
    }

    /**
     * A surface that already is pixels (a photograph). The point scale is
     * derived from the width rather than fixed at 1, because a 40pt mark
     * sized for a page would be a speck on a 6000px photo and a smear on a
     * 400px thumbnail - {@code referenceWidthPx} is the width at which a
     * point means a pixel.
     */
    public static RenderTarget ofPixels(int widthPx, int heightPx, double referenceWidthPx) {
        double scale = referenceWidthPx <= 0 ? 1.0 : Math.max(0.25, widthPx / referenceWidthPx);
        return new RenderTarget(widthPx, heightPx, scale);
    }
}
