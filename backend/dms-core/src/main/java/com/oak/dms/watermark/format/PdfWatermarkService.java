package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Watermarks a PDF by appending a drawing operation to each page's content
 * stream. The existing content is never rewritten - the mark is added after
 * it, so text, vectors, annotations and bookmarks come through untouched.
 *
 * <h2>What it refuses, and why</h2>
 * <ul>
 *   <li><b>Encrypted</b> (user password) - the bytes can't be read at all.</li>
 *   <li><b>Owner password without modify permission</b> - PDFBox opens these
 *       happily with an empty user password, so nothing would stop us
 *       writing to one. The author set a permission saying not to, and
 *       silently ignoring it because the library lets us would be exactly
 *       the wrong call.</li>
 *   <li><b>Digitally signed</b> - any change to the bytes invalidates every
 *       signature on the file. A watermarked copy whose signature no longer
 *       verifies is worse than no copy: it looks tampered with, because it
 *       is.</li>
 * </ul>
 * All three are 422 with a sentence saying which one it was.
 *
 * <h2>One image per page geometry</h2>
 * A {@link PDImageXObject} is embedded once in the document and referenced
 * from every page that uses it, so a 500-page report carries one copy of the
 * watermark rather than 500. "Geometry" is the visible size AND the page's
 * {@code /Rotate}, because a landscape appendix in a portrait report needs a
 * differently-shaped mark. A document with more distinct geometries than
 * {@code dms.watermark.limits.max-distinct-page-sizes} reuses the largest
 * one, scaled - slightly soft on the odd page, rather than embedding an
 * unbounded number of images.
 *
 * <h2>Rotation and crop boxes</h2>
 * Pages carry a {@code /Rotate} that viewers apply at display time, and a
 * CropBox that need not start at the origin. Rather than deriving a content
 * stream matrix for each combination - easy to get subtly wrong, and the
 * failure mode is a mark running up the side of the page - the watermark
 * IMAGE is pre-rotated to cancel the page's rotation, then drawn into the
 * CropBox as-is. Same result, and the arithmetic is one quarter turn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfWatermarkService implements WatermarkFormatService {

    private static final String MIME_PDF = "application/pdf";

    private final WatermarkRenderer renderer;
    private final WatermarkProperties properties;

    @Override
    public java.util.Set<String> mimeTypes() {
        return java.util.Set.of(MIME_PDF);
    }

    @Override
    public byte[] watermark(InputStream input, WatermarkOptions options) throws IOException {
        byte[] source = input.readAllBytes();
        try (PDDocument document = open(source)) {
            refuseIfSigned(document);
            refuseIfNotModifiable(document);

            int pages = document.getNumberOfPages();
            if (pages == 0) {
                throw WatermarkException.unprocessable("WATERMARK_PDF_EMPTY",
                        "This PDF has no pages, so there is nothing to watermark.");
            }
            int maxPages = properties.getLimits().getMaxPages();
            if (pages > maxPages) {
                throw WatermarkException.tooLarge("This PDF has " + pages + " pages, and " + maxPages
                        + " is the most that can be watermarked.");
            }

            int lastPage = options.spec().firstPageOnly() ? 1 : pages;
            // One font for the whole document: a report whose landscape
            // appendix is set in a different typeface from its body looks
            // like a forgery rather than a watermark.
            FontChoice font = renderer.resolveFont(options.spec().text());
            Map<Geometry, PDImageXObject> embedded = new LinkedHashMap<>();
            for (int index = 0; index < lastPage; index++) {
                stamp(document, document.getPage(index), options, embedded, font);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 64 * 1024);
            document.save(out);
            return out.toByteArray();
        } catch (InvalidPasswordException e) {
            throw WatermarkException.unprocessable("WATERMARK_PDF_ENCRYPTED",
                    "This PDF is password-protected, so it can't be opened to add a watermark.");
        } catch (IOException e) {
            // PDFBox reports a damaged file as a plain IOException, which the
            // worker would otherwise turn into a generic failure.
            log.debug("Could not read a PDF for watermarking", e);
            throw WatermarkException.unprocessable("WATERMARK_PDF_DAMAGED",
                    "This PDF could not be read. It may be damaged or incomplete.");
        }
    }

    private PDDocument open(byte[] source) throws IOException {
        return Loader.loadPDF(source);
    }

    /**
     * Refuses a signed document. Checked before anything is written, because
     * the point is to leave the file untouched rather than to undo a change.
     * <p>
     * A signature dictionary is enough - we don't verify that the signature
     * is valid, and shouldn't. Whether the signature currently verifies is
     * the recipient's business; what matters here is that it would certainly
     * stop verifying if we rewrote the bytes.
     */
    private void refuseIfSigned(PDDocument document) {
        if (!document.getSignatureDictionaries().isEmpty()) {
            throw WatermarkException.unprocessable("WATERMARK_PDF_SIGNED",
                    "This PDF is digitally signed. Adding a watermark would break the signature, "
                            + "so the copy can't be produced.");
        }
    }

    private void refuseIfNotModifiable(PDDocument document) {
        // Opened with an empty user password but an owner password set: the
        // author's permissions still apply even though the library ignored
        // them on open.
        if (document.isEncrypted() && !document.getCurrentAccessPermission().canModify()) {
            throw WatermarkException.unprocessable("WATERMARK_PDF_RESTRICTED",
                    "This PDF's security settings don't allow it to be changed, so a watermarked copy "
                            + "can't be produced.");
        }
    }

    private void stamp(PDDocument document, PDPage page, WatermarkOptions options,
                       Map<Geometry, PDImageXObject> embedded, FontChoice font) throws IOException {
        PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        Geometry geometry = new Geometry(box.getWidth(), box.getHeight(), rotation);

        PDImageXObject image = embedded.get(geometry);
        if (image == null) {
            image = embedded.size() >= properties.getLimits().getMaxDistinctPageSizes()
                    ? largestOf(embedded)
                    : embed(document, geometry, options, font);
            embedded.put(geometry, image);
        }

        // APPEND with resetContext: the mark is drawn in a clean graphics
        // state on top of the page, and whatever state the existing content
        // left behind can't bleed into it (or the other way round).
        try (PDPageContentStream content = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            // Positioned at the CropBox's own origin, which is not always (0, 0).
            content.drawImage(image, box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
        }
    }

    private PDImageXObject embed(PDDocument document, Geometry geometry, WatermarkOptions options, FontChoice font)
            throws IOException {
        // The mark is rendered for the page as the READER sees it - width and
        // height swapped on a quarter-turned page - then turned back so it
        // sits correctly in the page's own unrotated coordinate space.
        boolean quarterTurn = geometry.rotation() == 90 || geometry.rotation() == 270;
        double visibleWidth = quarterTurn ? geometry.height() : geometry.width();
        double visibleHeight = quarterTurn ? geometry.width() : geometry.height();

        BufferedImage mark = renderer.render(options.spec(),
                RenderTarget.ofPoints(visibleWidth, visibleHeight, renderer.renderDpi()), font);
        if (geometry.rotation() != 0) {
            mark = Images.rotateClockwise(mark, 360 - geometry.rotation());
        }
        // Lossless keeps the alpha channel as a soft mask, so the page shows
        // through the mark instead of the mark painting a box over it.
        return LosslessFactory.createFromImage(document, mark);
    }

    /** The biggest geometry already embedded - reused, scaled, once the cap is hit. */
    private PDImageXObject largestOf(Map<Geometry, PDImageXObject> embedded) {
        return embedded.entrySet().stream()
                .max(Map.Entry.comparingByKey(
                        java.util.Comparator.comparingDouble(g -> g.width() * g.height())))
                .orElseThrow()
                .getValue();
    }

    /**
     * What makes two pages able to share one embedded image: the same visible
     * size and the same {@code /Rotate}. Sizes are rounded to a tenth of a
     * point, so pages that differ only by floating-point noise - which
     * generators produce routinely - still count as the same.
     */
    private record Geometry(double width, double height, int rotation) {
        Geometry(double width, double height, int rotation) {
            this.width = Math.round(width * 10) / 10.0;
            this.height = Math.round(height * 10) / 10.0;
            this.rotation = rotation;
        }
    }
}
