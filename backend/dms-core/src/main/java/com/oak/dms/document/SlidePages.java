package com.oak.dms.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A presentation's slides, drawn from the PDF LibreOffice made of it - one page per slide - so the
 * preview can show them one at a time like a slide show instead of as a PDF. Pure PDFBox: the PDF is
 * read from memory, nothing is written anywhere, and a slide is a JPEG of the requested width.
 */
final class SlidePages {

    /** Slide images are drawn between these widths (in pixels) - thumbnails up to a sharp full-screen slide. */
    static final int MIN_WIDTH = 120;
    static final int MAX_WIDTH = 2400;
    private static final float JPEG_QUALITY = 0.9f;

    /** How many slides and the first slide's shape (points), which the viewer uses to lay itself out. */
    record Deck(int slides, float width, float height) {
    }

    private SlidePages() {
    }

    static Deck describe(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (document.getNumberOfPages() == 0) {
                return new Deck(0, 0, 0);
            }
            PDRectangle size = shape(document.getPage(0));
            return new Deck(document.getNumberOfPages(), size.getWidth(), size.getHeight());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the slides", e);
        }
    }

    /**
     * Slide {@code index} (0-based) as a JPEG {@code width} pixels wide (clamped to
     * {@link #MIN_WIDTH}..{@link #MAX_WIDTH}).
     *
     * @throws IndexOutOfBoundsException when there's no such slide
     */
    static byte[] render(byte[] pdf, int index, int width) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (index < 0 || index >= document.getNumberOfPages()) {
                throw new IndexOutOfBoundsException("No slide " + (index + 1));
            }
            PDRectangle size = shape(document.getPage(index));
            float scale = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width)) / size.getWidth();
            BufferedImage image = new PDFRenderer(document).renderImage(index, scale, ImageType.RGB);
            return jpeg(image);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not draw the slide", e);
        }
    }

    /** The page as it's shown: its crop box, turned when the page is rotated a quarter. */
    private static PDRectangle shape(PDPage page) {
        PDRectangle box = page.getCropBox();
        return page.getRotation() % 180 == 0 ? box : new PDRectangle(box.getHeight(), box.getWidth());
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
