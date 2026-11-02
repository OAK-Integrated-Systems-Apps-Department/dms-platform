package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocxDocumentStamperTest {

    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final OfficePreviewRenderer previewRenderer = mock(OfficePreviewRenderer.class);
    private final DocxDocumentStamper stamper = new DocxDocumentStamper(previewRenderer);

    @Test
    void supportsOnlyDocx() {
        assertThat(stamper.supports(MIME_DOCX)).isTrue();
        assertThat(stamper.supports("application/pdf")).isFalse();
    }

    @Test
    void pageCount_delegatesToTheConvertedPdf() {
        byte[] doc = documentWith(5);
        byte[] fakePdf = {1, 2, 3};
        when(previewRenderer.toPdf(doc, "docx")).thenReturn(fakePdf);
        when(previewRenderer.pageCount(fakePdf)).thenReturn(3);

        assertThat(stamper.pageCount(doc)).isEqualTo(3);
    }

    @Test
    void stamp_insertsOneInlinePictureParagraph_withoutDisturbingExistingOnes() throws IOException {
        byte[] doc = documentWith(6);
        byte[] fakePdf = {1, 2, 3};
        when(previewRenderer.toPdf(doc, "docx")).thenReturn(fakePdf);
        when(previewRenderer.pageCount(fakePdf)).thenReturn(2);

        byte[] stamped = stamper.stamp(doc, 1, 0.1, 0.1, 0.3, 0.1, SignaturePngFixture.bytes());

        try (XWPFDocument result = new XWPFDocument(new ByteArrayInputStream(stamped))) {
            // One new paragraph added for the mark; the original six are untouched.
            assertThat(result.getParagraphs()).hasSize(7);
            long runsWithPictures = result.getParagraphs().stream()
                    .flatMap(p -> p.getRuns().stream())
                    .filter(r -> !r.getEmbeddedPictures().isEmpty())
                    .count();
            assertThat(runsWithPictures).isEqualTo(1);
            // The mark paragraph is spliced in right after the chosen anchor (page 1 of 2 -> paragraph
            // index 3 of 6), so original paragraphs 0-3 keep their index and 4-5 shift down by one.
            List<String> texts = result.getParagraphs().stream().map(XWPFParagraph::getText).toList();
            assertThat(texts).containsSubsequence(
                    "Paragraph 0", "Paragraph 1", "Paragraph 2", "Paragraph 3", "Paragraph 4", "Paragraph 5");
        }
    }

    @Test
    void stamp_picturesAreInlineNotFloating_soEveryRendererLaysThemOutTheSameWay() throws IOException {
        byte[] doc = documentWith(3);
        byte[] fakePdf = {1, 2, 3};
        when(previewRenderer.toPdf(doc, "docx")).thenReturn(fakePdf);
        when(previewRenderer.pageCount(fakePdf)).thenReturn(1);

        byte[] stamped = stamper.stamp(doc, 0, 0.5, 0.5, 0.3, 0.1, SignaturePngFixture.bytes());

        try (XWPFDocument result = new XWPFDocument(new ByteArrayInputStream(stamped))) {
            XWPFRun pictureRun = result.getParagraphs().stream()
                    .flatMap(p -> p.getRuns().stream())
                    .filter(r -> !r.getEmbeddedPictures().isEmpty())
                    .findFirst()
                    .orElseThrow();
            // An anchored (floating) drawing would have no inline array entry at all.
            assertThat(pictureRun.getCTR().getDrawingArray(0).getInlineArray()).hasSize(1);
            assertThat(pictureRun.getCTR().getDrawingArray(0).getAnchorArray()).isEmpty();
        }
    }

    @Test
    void appendBlankPage_addsAPageBreakParagraph() throws IOException {
        byte[] doc = documentWith(1);
        byte[] withPage = stamper.appendBlankPage(doc);
        try (XWPFDocument result = new XWPFDocument(new ByteArrayInputStream(withPage))) {
            assertThat(result.getParagraphs()).hasSize(2);
        }
    }

    @Test
    void refusesACorruptFile() {
        when(previewRenderer.toPdf(any(), any())).thenThrow(SignatureException.unavailable("x", "x"));
        assertThatThrownBy(() -> stamper.pageCount("not a document".getBytes()))
                .isInstanceOf(SignatureException.class);
    }

    private static byte[] documentWith(int paragraphs) {
        try (XWPFDocument document = new XWPFDocument()) {
            for (int i = 0; i < paragraphs; i++) {
                document.createParagraph().createRun().setText("Paragraph " + i);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
