package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;

/**
 * One office format {@link com.oak.dms.signature.service.DocumentStampService}
 * can burn a visual signature mark into, beyond the PDF/image handling it
 * already has inline. Each implementation owns exactly one mime type and is
 * picked by {@code supports()}, the same dispatch {@code WatermarkServiceFactory}
 * uses for {@code WatermarkFormatService} - a deliberately separate hierarchy
 * from that one, not shared code (see {@code DocumentStampService}'s own doc
 * comment on why signing and watermarking never share a stamping
 * implementation even where the underlying library call looks similar).
 */
public interface DocumentFormatStamper {

    boolean supports(String mimeType);

    /** How many pages/sheets/slides {@code source} has, for placement purposes. */
    int pageCount(byte[] source);

    /** {@code page} (0-based) rendered as a JPEG about {@code width} pixels wide, for the placement UI. */
    byte[] renderPagePreview(byte[] source, int page, int width);

    /**
     * Stamps {@code signaturePng} onto {@code source} at {@code page}, positioned/sized as ratios (0..1)
     * of that page's own width/height - see {@code DocumentStampService#stamp}'s own doc comment for why
     * only x/y/width (never height) are honored.
     */
    byte[] stamp(byte[] source, int page, double xRatio, double yRatio, double widthRatio, double heightRatio,
                byte[] signaturePng);

    /** Appends one blank page/sheet/slide - optional; most formats don't need their own "add a page" story. */
    default byte[] appendBlankPage(byte[] source) {
        throw SignatureException.badRequest("SIGNATURE_PLACEMENT_UNSUPPORTED_TYPE",
                "Adding a page isn't supported for this file type.");
    }
}
