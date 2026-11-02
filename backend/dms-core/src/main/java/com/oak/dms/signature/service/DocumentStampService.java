package com.oak.dms.signature.service;

import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.signature.service.format.DocumentFormatStamper;
import lombok.RequiredArgsConstructor;
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
import java.util.List;
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
 * a caller-chosen page and rectangle. PDF and the two still-image types stay
 * handled inline below, unchanged; DOCX/XLSX/PPTX are each their own
 * {@link DocumentFormatStamper}, dispatched the same way
 * {@code WatermarkServiceFactory} picks a watermark format - kept inline
 * for PDF/image rather than also extracted, so as not to disturb
 * already-production code for this change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStampService {

    /** The two still-image types this can stamp directly (a JPEG target keeps its own format; PNG keeps alpha). */
    public static final Set<String> IMAGE_MIME_TYPES = Set.of("image/png", "image/jpeg");
    private static final String MIME_PDF = "application/pdf";

    private final List<DocumentFormatStamper> formatStampers;

    /** Every mime type this service can stamp - callers skip stamping entirely for anything else. */
    public boolean supports(String mimeType) {
        return MIME_PDF.equals(mimeType) || IMAGE_MIME_TYPES.contains(mimeType) || formatStamperFor(mimeType) != null;
    }

    /** How many pages/images/sheets/slides {@code mimeType} has, for placement purposes - always 1 for a still image. */
    public int pageCount(byte[] source, String mimeType) {
        DocumentFormatStamper stamper = formatStamperFor(mimeType);
        if (stamper != null) {
            return stamper.pageCount(source);
        }
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
        DocumentFormatStamper stamper = formatStamperFor(mimeType);
        if (stamper != null) {
            return stamper.renderPagePreview(source, page, width);
        }
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
     * position/width given as ratios (0..1) of the target page/image's own width/height - resolution-independent,
     * since the placement UI previews a page at whatever size fits the screen, never the page's real point/pixel
     * size.
     * <p>
     * {@code heightRatio} is accepted (the placement UI's drag box has one) but NOT used to size the drawn
     * image - see {@link #aspectHeight}'s own doc comment for why the height is always derived from
     * {@code signaturePng}'s own aspect ratio instead. Only {@code xRatio}/{@code yRatio}/{@code widthRatio}
     * actually affect where/how big the mark ends up.
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
        DocumentFormatStamper stamper = formatStamperFor(mimeType);
        if (stamper != null) {
            return stamper.stamp(source, page, xRatio, yRatio, widthRatio, heightRatio, signaturePng);
        }
        throw SignatureException.badRequest("SIGNATURE_PLACEMENT_UNSUPPORTED_TYPE",
                "Placing a signature on the page isn't supported for this file type.");
    }

    private DocumentFormatStamper formatStamperFor(String mimeType) {
        return formatStampers.stream().filter(s -> s.supports(mimeType)).findFirst().orElse(null);
    }

    /**
     * Appends one blank page/sheet/slide - used by standalone signing (V118) when no existing page has
     * room for the Name/Designation/Date block and the signer chooses "Add signature page" rather than
     * dragging onto an existing one. For a PDF, the new page is the same size as the current last page
     * (or A4 if there are none); DOCX/XLSX/PPTX each have their own {@link DocumentFormatStamper}
     * implementation. A still image is always exactly one page and has no notion of "appending a page"
     * to it, so this refuses for {@link #IMAGE_MIME_TYPES}.
     *
     * @throws SignatureException 400 if {@code mimeType} isn't supported, or the document is signed/encrypted/restricted
     */
    public byte[] appendBlankPage(byte[] source, String mimeType) {
        if (!MIME_PDF.equals(mimeType)) {
            DocumentFormatStamper stamper = formatStamperFor(mimeType);
            if (stamper != null) {
                return stamper.appendBlankPage(source);
            }
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_UNSUPPORTED_TYPE",
                    "Adding a page is only supported for PDF documents.");
        }
        try (PDDocument document = Loader.loadPDF(source)) {
            refuseIfSigned(document);
            refuseIfNotModifiable(document);
            PDRectangle size = document.getNumberOfPages() > 0
                    ? cropBox(document.getPage(document.getNumberOfPages() - 1)) : PDRectangle.A4;
            document.addPage(new PDPage(size));

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 8 * 1024);
            document.save(out);
            return out.toByteArray();
        } catch (InvalidPasswordException e) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PDF_ENCRYPTED",
                    "This PDF is password-protected, so a page can't be added.");
        } catch (IOException e) {
            log.debug("Could not append a page to a PDF for signature placement", e);
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PDF_DAMAGED",
                    "This PDF could not be read - it may be damaged or incomplete.");
        }
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
            float width = (float) (widthRatio * box.getWidth());
            // heightRatio is NOT used for the drawn height - see aspectHeight's own doc comment on why
            // stretching to whatever box the placement UI's drag handle happens to be would distort (or,
            // for the composed Name/Designation/Date text block, squash into illegibility) the image.
            float height = aspectHeight(markImage, width);
            // PDF's y axis runs bottom-up; the placement UI's runs top-down like the screen - flip here,
            // once, rather than asking the frontend to reason about PDF coordinate conventions at all.
            float y = box.getLowerLeftY() + box.getHeight() - (float) (yRatio * box.getHeight()) - height;

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

    /**
     * The height to draw {@code image} at for a given target {@code width}, preserving its own
     * width:height ratio - never the caller's independently-chosen heightRatio. {@code signaturePng}
     * here is always {@link SignatureStampComposer}'s output (the mark plus the Name/Designation/Date
     * text block stacked underneath it, all one PNG) - letting the placement UI's drag box dictate the
     * height too would stretch or (for a box sized for just a bare signature line, which is what the
     * default drag box - and most users' first instinct - looks like) squash that text down to
     * illegible or invisible. The box the signer drags still controls width and position; only its
     * height is now derived, not honored literally.
     */
    private static float aspectHeight(BufferedImage image, float width) {
        return width * image.getHeight() / image.getWidth();
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
                // heightRatio ignored - see aspectHeight's own doc comment.
                int height = Math.round(aspectHeight(mark, width));
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
