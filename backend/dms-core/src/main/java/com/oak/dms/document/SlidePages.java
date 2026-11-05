package com.oak.dms.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
        return render(pdf, index, width, null);
    }

    /** As {@link #render(byte[], int, int)}, with {@code watermark} (if any) burned diagonally into the picture. */
    static byte[] render(byte[] pdf, int index, int width, String watermark) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (index < 0 || index >= document.getNumberOfPages()) {
                throw new IndexOutOfBoundsException("No slide " + (index + 1));
            }
            PDRectangle size = shape(document.getPage(index));
            float scale = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width)) / size.getWidth();
            BufferedImage image = new PDFRenderer(document).renderImage(index, scale, ImageType.RGB);
            return jpeg(stamp(image, watermark));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not draw the slide", e);
        }
    }

    /** Thumbnails are this wide (pixels) - big enough for a card, small enough that it is no copy of the page. */
    static final int THUMBNAIL_WIDTH = 320;

    /** The first page as a small JPEG, for lists and cards. */
    static byte[] thumbnail(byte[] pdf) {
        return render(pdf, 0, THUMBNAIL_WIDTH, null);
    }

    /** An image file is a one-page document: its shape (pixels), or null when it can't be read as an image. */
    static Deck describeImage(byte[] image) {
        try {
            BufferedImage read = ImageIO.read(new java.io.ByteArrayInputStream(image));
            return read == null ? null : new Deck(1, read.getWidth(), read.getHeight());
        } catch (IOException e) {
            return null;
        }
    }

    /** An image file as a JPEG {@code width} pixels wide (clamped like a slide, never enlarged past its own size). */
    static byte[] renderImage(byte[] image, int width, String watermark) {
        try {
            BufferedImage read = ImageIO.read(new java.io.ByteArrayInputStream(image));
            if (read == null) {
                throw new UncheckedIOException(new IOException("Not an image"));
            }
            int target = Math.max(MIN_WIDTH, Math.min(Math.min(MAX_WIDTH, width), Math.max(MIN_WIDTH, read.getWidth())));
            int height = Math.max(1, Math.round(read.getHeight() * (target / (float) read.getWidth())));
            BufferedImage rgb = new BufferedImage(target, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, target, height);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(read, 0, 0, target, height, null);
            } finally {
                g.dispose();
            }
            return jpeg(stamp(rgb, watermark));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not draw the image", e);
        }
    }

    /**
     * The first {@code maxPages} pages of {@code pdf} as a new PDF made of their pictures, each with
     * {@code watermark} burned in. It has no text layer and nothing to select - what a watermarked download is.
     */
    static byte[] flattenedPdf(byte[] pdf, String watermark, int maxPages) {
        try (PDDocument source = Loader.loadPDF(pdf); PDDocument target = new PDDocument()) {
            PDFRenderer renderer = new PDFRenderer(source);
            int pages = Math.min(source.getNumberOfPages(), maxPages);
            for (int i = 0; i < pages; i++) {
                PDRectangle size = shape(source.getPage(i));
                float scale = Math.min(2f, FLATTEN_WIDTH / size.getWidth());
                BufferedImage image = stamp(renderer.renderImage(i, scale, ImageType.RGB), watermark);
                PDPage page = new PDPage(new PDRectangle(size.getWidth(), size.getHeight()));
                target.addPage(page);
                PDImageXObject picture = PDImageXObject.createFromByteArray(target, jpeg(image), "page" + (i + 1));
                try (PDPageContentStream content = new PDPageContentStream(target, page)) {
                    content.drawImage(picture, 0, 0, size.getWidth(), size.getHeight());
                }
            }
            return save(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make the watermarked copy", e);
        }
    }

    /** A flattened page is drawn at most this many pixels wide. */
    private static final int FLATTEN_WIDTH = 1500;

    /** A single image file as a one-page watermarked PDF. */
    static byte[] imagePdf(byte[] image, String watermark) {
        byte[] jpeg = renderImage(image, FLATTEN_WIDTH, watermark);
        try (PDDocument target = new PDDocument()) {
            BufferedImage read = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
            PDPage page = new PDPage(new PDRectangle(read.getWidth(), read.getHeight()));
            target.addPage(page);
            PDImageXObject picture = PDImageXObject.createFromByteArray(target, jpeg, "image");
            try (PDPageContentStream content = new PDPageContentStream(target, page)) {
                content.drawImage(picture, 0, 0, read.getWidth(), read.getHeight());
            }
            return save(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make the watermarked copy", e);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    /**
     * {@code text} repeated diagonally over the whole picture, dark and light at once so it reads on white paper and
     * on a dark photograph, and faint enough that the page is still easy to read. Returns the same image.
     */
    static BufferedImage stamp(BufferedImage image, String text) {
        if (text == null || text.isBlank()) {
            return image;
        }
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int size = Math.max(12, image.getWidth() / 34);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            int textWidth = metrics.stringWidth(text);
            int stepX = textWidth + size * 3;
            int stepY = size * 5;
            g.rotate(Math.toRadians(-30), image.getWidth() / 2.0, image.getHeight() / 2.0);
            int span = Math.max(image.getWidth(), image.getHeight()) * 2;
            int row = 0;
            for (int y = -span / 2; y < span; y += stepY, row++) {
                int offset = (row % 2) * (stepX / 2);
                for (int x = -span / 2 - offset; x < span; x += stepX) {
                    g.setColor(new Color(255, 255, 255, 70));
                    g.drawString(text, x + 1, y + 1);
                    g.setColor(new Color(0, 0, 0, 55));
                    g.drawString(text, x, y);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
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
