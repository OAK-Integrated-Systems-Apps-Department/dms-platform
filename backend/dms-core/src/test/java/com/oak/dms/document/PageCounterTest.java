package com.oak.dms.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Pages are read from the files - counted, or as the saving program recorded them - never guessed. */
class PageCounterTest {

    @TempDir
    Path dir;

    private File zip(String name, String... entriesAndContents) throws IOException {
        File file = dir.resolve(name).toFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < entriesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entriesAndContents[i]));
                zip.write(entriesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void aPdfsPages_areCounted() throws IOException {
        File file = dir.resolve("report.pdf").toFile();
        try (PDDocument pdf = new PDDocument()) {
            for (int i = 0; i < 7; i++) {
                pdf.addPage(new PDPage());
            }
            pdf.save(file);
        }

        assertThat(PageCounter.count("report.pdf", file))
                .contains(new PageCounter.Pages(7, PageCounter.Source.COUNTED));
    }

    @Test
    void aDecksSlides_areCountedFromItsSlideParts() throws IOException {
        File file = zip("deck.pptx", "[Content_Types].xml", "<Types/>", "ppt/presentation.xml", "<p/>",
                "ppt/slides/slide1.xml", "<s/>", "ppt/slides/slide2.xml", "<s/>", "ppt/slides/slide3.xml", "<s/>",
                "ppt/slides/_rels/slide1.xml.rels", "<r/>", "ppt/slideLayouts/slideLayout1.xml", "<l/>");

        assertThat(PageCounter.count("deck.pptx", file))
                .contains(new PageCounter.Pages(3, PageCounter.Source.COUNTED));
    }

    @Test
    void wordAndOpenDocument_giveThePagesTheirAppRecorded() throws IOException {
        File docx = zip("letter.docx", "[Content_Types].xml", "<Types/>", "docProps/app.xml",
                "<Properties><Pages>12</Pages><Words>3000</Words></Properties>");
        File odt = zip("letter.odt", "mimetype", "application/vnd.oasis.opendocument.text", "meta.xml",
                "<office:meta><meta:document-statistic meta:page-count=\"4\" meta:word-count=\"900\"/></office:meta>");

        assertThat(PageCounter.count("letter.docx", docx))
                .contains(new PageCounter.Pages(12, PageCounter.Source.REPORTED));
        assertThat(PageCounter.count("letter.odt", odt))
                .contains(new PageCounter.Pages(4, PageCounter.Source.REPORTED));
    }

    @Test
    void aWordFileWithoutStatistics_isNotGuessed() throws IOException {
        File docx = zip("bare.docx", "[Content_Types].xml", "<Types/>", "word/document.xml", "<w/>");

        assertThat(PageCounter.count("bare.docx", docx)).isEmpty();
    }

    @Test
    void aTiffsFrames_areCounted() throws IOException {
        File file = dir.resolve("scan.tif").toFile();
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "tiff", file);

        assertThat(PageCounter.count("scan.tif", file))
                .contains(new PageCounter.Pages(1, PageCounter.Source.COUNTED));
    }

    @Test
    void formatsWithoutPages_orDamagedFiles_giveNothing() throws IOException {
        File junk = dir.resolve("broken.pdf").toFile();
        Files.writeString(junk.toPath(), "not a pdf");

        assertThat(PageCounter.paged("sheet.xlsx")).isFalse();
        assertThat(PageCounter.paged("photo.png")).isFalse();
        assertThat(PageCounter.paged("notes.txt")).isFalse();
        assertThat(PageCounter.paged("Deck.PPTX")).isTrue();
        assertThat(PageCounter.count("sheet.xlsx", junk)).isEmpty();
        assertThat(PageCounter.count("broken.pdf", junk)).isEmpty();
    }
}
