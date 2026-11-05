package com.oak.dms.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A presentation's PDF (one page per slide) becomes slide images of the width asked for. */
class SlidePagesTest {

    /** A 16:9 deck of {@code slides} blank pages, as LibreOffice exports a widescreen presentation. */
    private static byte[] deck(int slides) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < slides; i++) {
                document.addPage(new PDPage(new PDRectangle(960, 540)));
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void describe_countsTheSlidesAndTheirShape() throws IOException {
        SlidePages.Deck deck = SlidePages.describe(deck(3));

        assertThat(deck.slides()).isEqualTo(3);
        assertThat(deck.width()).isEqualTo(960f);
        assertThat(deck.height()).isEqualTo(540f);
    }

    @Test
    void render_drawsTheSlideAsAJpegOfTheWidthAskedFor_withinTheLimits() throws IOException {
        byte[] pdf = deck(2);

        BufferedImage slide = ImageIO.read(new ByteArrayInputStream(SlidePages.render(pdf, 1, 640)));
        assertThat(slide.getWidth()).isEqualTo(640);
        assertThat(slide.getHeight()).isEqualTo(360);

        BufferedImage tiny = ImageIO.read(new ByteArrayInputStream(SlidePages.render(pdf, 0, 10)));
        assertThat(tiny.getWidth()).isEqualTo(SlidePages.MIN_WIDTH);
        BufferedImage huge = ImageIO.read(new ByteArrayInputStream(SlidePages.render(pdf, 0, 99_999)));
        assertThat(huge.getWidth()).isEqualTo(SlidePages.MAX_WIDTH);
    }

    @Test
    void aSlideThatIsntThere_isOutOfBounds() throws IOException {
        byte[] pdf = deck(2);

        assertThatThrownBy(() -> SlidePages.render(pdf, 2, 640)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> SlidePages.render(pdf, -1, 640)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, 0xFFCCDDEE);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void thumbnail_isASmallJpegOfTheFirstPage() throws IOException {
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(SlidePages.thumbnail(deck(5))));

        assertThat(thumb.getWidth()).isEqualTo(SlidePages.THUMBNAIL_WIDTH);
        assertThat(thumb.getHeight()).isEqualTo(180);
    }

    @Test
    void aWatermark_isBurnedIntoThePicture_andNoWatermarkLeavesItUntouched() throws IOException {
        byte[] pdf = deck(1);

        byte[] plain = SlidePages.render(pdf, 0, 640, null);
        byte[] blank = SlidePages.render(pdf, 0, 640, "  ");
        byte[] stamped = SlidePages.render(pdf, 0, 640, "Ada Lovelace · 2026-11-05 10:00 UTC");

        assertThat(blank).isEqualTo(plain);
        assertThat(stamped).isNotEqualTo(plain);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(stamped));
        // A blank white slide now has dark text pixels somewhere on it.
        boolean marked = false;
        for (int x = 0; x < image.getWidth() && !marked; x += 3) {
            for (int y = 0; y < image.getHeight(); y += 3) {
                if ((image.getRGB(x, y) & 0xFF) < 235) {
                    marked = true;
                    break;
                }
            }
        }
        assertThat(marked).isTrue();
    }

    @Test
    void anImageFile_isAOnePageDocument_scaledButNeverBlownUp() throws IOException {
        byte[] image = png(400, 200);

        SlidePages.Deck deck = SlidePages.describeImage(image);
        assertThat(deck.slides()).isEqualTo(1);
        assertThat(deck.width()).isEqualTo(400f);
        assertThat(deck.height()).isEqualTo(200f);

        BufferedImage small = ImageIO.read(new ByteArrayInputStream(SlidePages.renderImage(image, 200, null)));
        assertThat(small.getWidth()).isEqualTo(200);
        assertThat(small.getHeight()).isEqualTo(100);
        BufferedImage capped = ImageIO.read(new ByteArrayInputStream(SlidePages.renderImage(image, 2000, null)));
        assertThat(capped.getWidth()).isEqualTo(400);
    }

    @Test
    void somethingThatIsNotAnImage_isNotDescribed() {
        assertThat(SlidePages.describeImage("not an image".getBytes())).isNull();
    }

    @Test
    void aFlattenedPdf_hasThePagesAsPicturesOnly_andIsCutAtTheLimit() throws IOException {
        byte[] flat = SlidePages.flattenedPdf(deck(4), "Ada · now", 3);

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(flat)) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(960f);
            // Nothing to copy: the pages are pictures, with no text layer.
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document).trim()).isEmpty();
        }
    }

    @Test
    void aWatermarkedImage_becomesAOnePagePdf() throws IOException {
        byte[] pdf = SlidePages.imagePdf(png(300, 150), "Ada · now");

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }
}
