package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Slides map 1:1 to pages, the cleanest of the three office formats to stamp.
 * Unlike {@code PptxWatermarkService}, the mark goes on ONE slide (never the
 * master/layouts) and sits IN FRONT of content (a signature should be seen,
 * not hidden behind whatever the slide already has) - {@code createPicture}
 * already appends on top, so none of that service's z-order juggling applies
 * here.
 */
@Slf4j
@Component
public class PptxDocumentStamper implements DocumentFormatStamper {

    private static final String MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Override
    public boolean supports(String mimeType) {
        return MIME_PPTX.equals(mimeType);
    }

    @Override
    public int pageCount(byte[] source) {
        try (XMLSlideShow show = open(source)) {
            return show.getSlides().size();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read a presentation for signature placement", e);
        }
    }

    /** Rendered natively with POI's own AWT drawing, same as every other page preview here - no external converter. */
    @Override
    public byte[] renderPagePreview(byte[] source, int page, int width) {
        try (XMLSlideShow show = open(source)) {
            requirePage(show.getSlides().size(), page);
            Dimension size = show.getPageSize();
            float scale = Math.max(1, width) / (float) size.getWidth();
            BufferedImage image = new BufferedImage(Math.round(size.width * scale), Math.round(size.height * scale),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.scale(scale, scale);
                show.getSlides().get(page).draw(g);
            } finally {
                g.dispose();
            }
            return jpeg(image);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not render a slide for signature placement", e);
        }
    }

    @Override
    public byte[] stamp(byte[] source, int page, double xRatio, double yRatio, double widthRatio, double heightRatio,
                        byte[] signaturePng) {
        try (XMLSlideShow show = open(source)) {
            requirePage(show.getSlides().size(), page);
            XSLFSlide slide = show.getSlides().get(page);
            Dimension size = show.getPageSize();

            BufferedImage markImage = ImageIO.read(new ByteArrayInputStream(signaturePng));
            if (markImage == null) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_IMAGE_DAMAGED",
                        "The signature image could not be read.");
            }
            XSLFPictureData picture = show.addPicture(signaturePng, PictureData.PictureType.PNG);

            double x = xRatio * size.getWidth();
            double width = widthRatio * size.getWidth();
            double height = width * markImage.getHeight() / markImage.getWidth();
            double y = yRatio * size.getHeight();

            XSLFPictureShape shape = slide.createPicture(picture);
            shape.setAnchor(new Rectangle2D.Double(x, y, width, height));

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 64 * 1024);
            show.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.debug("Could not stamp a presentation with a signature", e);
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PPTX_DAMAGED",
                    "This presentation could not be read - it may be damaged or incomplete.");
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    @Override
    public byte[] appendBlankPage(byte[] source) {
        try (XMLSlideShow show = open(source)) {
            show.createSlide();
            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 8 * 1024);
            show.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PPTX_DAMAGED",
                    "This presentation could not be read - it may be damaged or incomplete.");
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    private static XMLSlideShow open(byte[] source) throws IOException {
        OoxmlSanityCheck.check(source, "presentation");
        try {
            return new XMLSlideShow(new ByteArrayInputStream(source));
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    private static void requirePage(int pageCount, int page) {
        if (page < 0 || page >= pageCount) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PAGE_OUT_OF_RANGE",
                    "That page doesn't exist in this document.");
        }
    }

    private static SignatureException unreadable(RuntimeException cause) {
        log.warn("Could not process a presentation for signature placement - refusing it", cause);
        return SignatureException.badRequest("SIGNATURE_PLACEMENT_PPTX_DAMAGED",
                "This presentation could not be read - it may be damaged or in a format this server doesn't recognise.");
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
}
