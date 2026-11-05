package com.oak.dms.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which uploads are turned into a PDF for the preview - the rest the browser shows itself. */
class DocumentRenditionServiceTest {

    @Test
    void officeFilesTheBrowserCantRead_areConverted_byTheirExtension() {
        for (String name : new String[]{"a.doc", "Deck.PPTX", "b.ppt", "c.odt", "d.odp", "plan.odg", "e.rtf",
                "flow.vsd", "flow.vsdx"}) {
            assertThat(DocumentRenditionService.converted(name)).as(name).isTrue();
        }
    }

    @Test
    void presentations_areShownAsSlides_andOtherConvertedFilesAreNot() {
        for (String name : new String[]{"Deck.PPTX", "old.ppt", "talk.odp"}) {
            assertThat(DocumentRenditionService.slides(name)).as(name).isTrue();
        }
        for (String name : new String[]{"a.doc", "c.odt", "plan.odg", "e.rtf", "flow.vsdx", "a.pdf", null}) {
            assertThat(DocumentRenditionService.slides(name)).as(String.valueOf(name)).isFalse();
        }
    }

    @Test
    void theProtectedViewAndThumbnails_coverPdfsPicturesAndWhatConvertsToAPdf() {
        for (String name : new String[]{"a.pdf", "A.PDF", "b.docx", "c.xlsx", "d.csv", "e.txt", "f.png", "g.JPG", "h.tiff",
                "i.pptx", "j.rtf", "k.vsdx"}) {
            assertThat(DocumentRenditionService.protectable(name)).as(name).isTrue();
        }
        for (String name : new String[]{"a.zip", "b.dwg", "c.eml", "d.mp4", "noextension", null}) {
            assertThat(DocumentRenditionService.protectable(name)).as(String.valueOf(name)).isFalse();
        }
    }

    @Test
    void onlyOfficeStyleFilesNeedConverting_pdfsAndPicturesAreDrawnAsTheyAre() {
        assertThat(DocumentRenditionService.needsConversion("a.docx")).isTrue();
        assertThat(DocumentRenditionService.needsConversion("a.doc")).isTrue();
        assertThat(DocumentRenditionService.needsConversion("a.csv")).isTrue();
        assertThat(DocumentRenditionService.needsConversion("a.pdf")).isFalse();
        assertThat(DocumentRenditionService.needsConversion("a.png")).isFalse();
    }

    @Test
    void filesTheBrowserShowsItself_orCantBeConverted_areNot() {
        for (String name : new String[]{"a.pdf", "b.docx", "c.xlsx", "d.csv", "e.eml", "f.png", "g.tiff", "h.dwg",
                "noextension", null}) {
            assertThat(DocumentRenditionService.converted(name)).as(String.valueOf(name)).isFalse();
        }
    }
}
