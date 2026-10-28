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
}
