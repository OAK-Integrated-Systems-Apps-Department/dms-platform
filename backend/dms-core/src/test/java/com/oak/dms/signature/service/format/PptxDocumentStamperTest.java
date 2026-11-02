package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PptxDocumentStamperTest {

    private static final String MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private final PptxDocumentStamper stamper = new PptxDocumentStamper();

    @Test
    void supportsOnlyPptx() {
        assertThat(stamper.supports(MIME_PPTX)).isTrue();
        assertThat(stamper.supports("application/pdf")).isFalse();
    }

    @Test
    void pageCount_matchesSlideCount() {
        byte[] deck = deckWith(3);
        assertThat(stamper.pageCount(deck)).isEqualTo(3);
    }

    @Test
    void renderPagePreview_rendersEachSlideAsAJpeg() {
        byte[] deck = deckWith(2);
        byte[] jpeg = stamper.renderPagePreview(deck, 0, 400);
        assertThat(jpeg).isNotEmpty();
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8); // JPEG SOI marker
    }

    @Test
    void renderPagePreview_refusesAnOutOfRangePage() {
        byte[] deck = deckWith(1);
        assertThatThrownBy(() -> stamper.renderPagePreview(deck, 5, 400))
                .isInstanceOf(SignatureException.class)
                .hasMessageContaining("page");
    }

    @Test
    void stamp_embedsAPictureOnTheChosenSlideOnly() throws IOException {
        byte[] deck = deckWith(2);
        byte[] stamped = stamper.stamp(deck, 1, 0.1, 0.1, 0.3, 0.1, SignaturePngFixture.bytes());

        try (XMLSlideShow show = new XMLSlideShow(new java.io.ByteArrayInputStream(stamped))) {
            assertThat(show.getSlides()).hasSize(2);
            assertThat(show.getSlides().get(0).getShapes()).isEmpty();
            assertThat(show.getSlides().get(1).getShapes()).hasSize(1);
        }
    }

    @Test
    void appendBlankPage_addsOneMoreSlide() throws IOException {
        byte[] deck = deckWith(1);
        byte[] withPage = stamper.appendBlankPage(deck);
        try (XMLSlideShow show = new XMLSlideShow(new java.io.ByteArrayInputStream(withPage))) {
            assertThat(show.getSlides()).hasSize(2);
        }
    }

    @Test
    void refusesACorruptFile() {
        assertThatThrownBy(() -> stamper.pageCount("not a pptx".getBytes()))
                .isInstanceOf(SignatureException.class);
    }

    private static byte[] deckWith(int slides) {
        try (XMLSlideShow show = new XMLSlideShow()) {
            for (int i = 0; i < slides; i++) {
                show.createSlide();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            show.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
