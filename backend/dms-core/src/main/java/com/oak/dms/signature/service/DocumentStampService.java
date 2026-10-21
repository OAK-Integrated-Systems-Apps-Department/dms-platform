package com.oak.dms.signature.service;

import com.oak.dms.signature.exception.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Set;

/**
 * Burns a user's visual signature image onto a specific place on a specific
 * page of a document - a real, permanent mark in the file (unlike
 * {@code UserVisualSignature}'s cosmetic-only evidence row), requested in a
 * separate step BEFORE sign-and-approve (see {@code DocumentSignatureService}'s
 * own doc comment on why this can never run inside that transaction). The
 * caller is responsible for turning the returned bytes into a new
 * {@code DocumentVersion} through the normal ingestion/scan pipeline - this
 * class only ever produces bytes in memory, never touches storage.
 * <p>
 * PDF handling mirrors {@code PdfWatermarkService} closely (same refusals,
 * same APPEND-content-stream technique, same lossless alpha-preserving
 * embed) - deliberately not shared code, since watermarking stamps the same
 * mark on every page at a fixed position while this stamps one mark once, at
 * a caller-chosen page and rectangle.
 */
@Slf4j
@Component
public class DocumentStampService {

    /** The two still-image types this can stamp directly (a JPEG target keeps its own format; PNG keeps alpha). */
    public static final Set<String> IMAGE_MIME_TYPES = Set.of("image/png", "image/jpeg");
    private static final String MIME_PDF = "application/pdf";

    /** Every mime type this service can stamp - callers skip stamping entirely for anything else. */
    public boolean supports(String mimeType) {
        return MIME_PDF.equals(mimeType) || IMAGE_MIME_TYPES.contains(mimeType);
    }

    /** How many pages/images {@code mimeType} has, for placement purposes - always 1 for a still image. */
    public int pageCount(byte[] source, String mimeType) {
        if (!MIME_PDF.equals(mimeType)) {
            return 1;
        }
        try (PDDocument document = Loader.loadPDF(source)) {
            return document.getNumberOfPages();
        } catch (InvalidPasswordException e) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PDF_ENCRYPTED",
                    "This PDF is password-protected, so it can't be signed in place.");
        } catch (IOException e) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PDF_DAMAGED",
                    "This PDF could not be read - it may be damaged or incomplete.");
        }
    }

    /** {@code page} (0-based) rendered as a JPEG about {@code width} pixels wide, for the placement UI - ignored for a still image, which is returned as-is. */
    public byte[] renderPagePreview(byte[] source, String mimeType, int page, int width) {
        if (!MIME_PDF.equals(mimeType)) {
            return source;
        }
        try (PDDocument document = Loader.loadPDF(source)) {
            requirePage(document, page);
            PDRectangle box = cropBox(document.getPage(page));
            float scale = Math.max(1, width) / box.getWidth();
            BufferedImage image = new PDFRenderer(document).renderImage(page, scale, ImageType.RGB);
            return jpeg(image);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render page " + page + " for signature placement", e);
        }
    }

    /**
     * Stamps {@code signaturePng} (a small transparent-background PNG, see
     * {@code UserVisualSignatureService}/{@code imageFileToTransparentSignature}) onto {@code source}, at the
     * rectangle given as ratios (0..1) of the target page/image's own width/height - resolution-independent,
     * since the placement UI previews a page at whatever size fits the screen, never the page's real point/pixel
     * size.
     *
     * @throws SignatureException 400 if {@code mimeType} isn't {@link #supports(String) supported}, the PDF is
     *                             signed/encrypted/restricted, or {@code page} is out of range
     */
    public byte[] stamp(byte[] source, String mimeType, int page, double xRatio, double yRatio, double widthRatio,
                        double heightRatio, byte[] signaturePng) {
        if (MIME_PDF.equals(mimeType)) {
            return stampPdf(source, page, xRatio, yRatio, widthRatio, heightRatio, signaturePng);
        }
        if (IMAGE_MIME_TYPES.contains(mimeType)) {
            return stampImage(source, mimeType, xRatio, yRatio, widthRatio, heightRatio, signaturePng);
        }
        throw SignatureException.badRequest("SIGNATURE_PLACEMENT_UNSUPPORTED_TYPE",
                "Placing a signature on the page isn't supported for this file type.");
    }

    // --------------------------------------------------------------- PDF

    private byte[] stampPdf(byte[] source, int page, double xRatio, double yRatio, double widthRatio,
                            double heightRatio, byte[] signaturePng) {
        try (PDDocument document = Loader.loadPDF(source)) {
            refuseIfSigned(document);
            refuseIfNotModifiable(document);
            requirePage(document, page);

            PDPage pdPage = document.getPage(page);
            PDRectangle box = cropBox(pdPage);
            // Same technique as PdfWatermarkService: decode to a BufferedImage first and embed losslessly,
            // which keeps the PNG's alpha channel as a soft mask - the page shows through the transparent
            // parts of the signature instead of the mark painting an opaque box over it.
            BufferedImage markImage = ImageIO.read(new ByteArrayInputStream(signaturePng));
            if (markImage == null) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_IMAGE_DAMAGED",
                        "The signature image could not be read.");
            }
            PDImageXObject image = LosslessFactory.createFromImage(document, markImage);

            float x = box.getLowerLeftX() + (float) (xRatio * box.getWidth());
            // PDF's y axis runs bottom-up; the placement UI's runs top-down like the screen - flip here,
            // once, rather than asking the frontend to reason about PDF coordinate conventions at all.
            float height = (float) (heightRatio * box.getHeight());
            float y = box.getLowerLeftY() + box.getHeight() - (float) (yRatio * box.getHeight()) - height;
            float width = (float) (widthRatio * box.getWidth());

            try (PDPageContentStream content = new PDPageContentStream(document, pdPage,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                content.drawImage(image, x, y, width, height);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 64 * 1024);
            document.save(out);
            return out.toByteArray();
        } catch (InvalidPasswordException e) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PDF_ENCRYPTED",
                    "This PDF is password-protected, so a signed copy can't be produced.");
        } catch (IOException e) {
            log.debug("Could not stamp a PDF with a signature", e);
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PDF_DAMAGED",
                    "This PDF could not be read - it may be damaged or incomplete.");
        }
    }

    private void refuseIfSigned(PDDocument document) {
        if (!document.getSignatureDictionaries().isEmpty()) {
            throw SignatureException.conflict("SIGNATURE_PLACEMENT_PDF_SIGNED",
                    "This PDF already carries its own digital signature. Stamping it would break that "
                            + "signature, so a copy can't be produced.");
        }
    }

    private void refuseIfNotModifiable(PDDocument document) {
        if (document.isEncrypted() && !document.getCurrentAccessPermission().canModify()) {
            throw SignatureException.conflict("SIGNATURE_PLACEMENT_PDF_RESTRICTED",
                    "This PDF's security settings don't allow it to be changed, so it can't be signed in place.");
        }
    }

    private static void requirePage(PDDocument document, int page) {
        if (page < 0 || page >= document.getNumberOfPages()) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PAGE_OUT_OF_RANGE",
                    "That page doesn't exist in this document.");
        }
    }

    private static PDRectangle cropBox(PDPage page) {
        return page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------- image

    private byte[] stampImage(byte[] source, String mimeType, double xRatio, double yRatio, double widthRatio,
                              double heightRatio, byte[] signaturePng) {
        try {
            BufferedImage base = ImageIO.read(new ByteArrayInputStream(source));
            if (base == null) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_IMAGE_DAMAGED",
                        "This image could not be read - it may be damaged or in an unsupported format.");
            }
            BufferedImage mark = ImageIO.read(new ByteArrayInputStream(signaturePng));

            BufferedImage out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(base, 0, 0, null);
                int x = (int) Math.round(xRatio * base.getWidth());
                int y = (int) Math.round(yRatio * base.getHeight());
                int width = (int) Math.round(widthRatio * base.getWidth());
                int height = (int) Math.round(heightRatio * base.getHeight());
                g.drawImage(mark, x, y, width, height, null);
            } finally {
                g.dispose();
            }

            String format = "image/jpeg".equals(mimeType) ? "jpg" : "png";
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            // JPEG has no alpha channel - flattening onto white keeps the output a valid JPEG rather than
            // producing one with corrupted color (writing an ARGB image as JPEG silently garbles it).
            BufferedImage toWrite = "jpg".equals(format) ? flattenOnWhite(out) : out;
            if (!ImageIO.write(toWrite, format, result)) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_IMAGE_UNSUPPORTED",
                        "Could not write a signed copy of this image.");
            }
            return result.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stamp an image with a signature", e);
        }
    }

    private static BufferedImage flattenOnWhite(BufferedImage source) {
        BufferedImage flat = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, source.getWidth(), source.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return flat;
    }
}
