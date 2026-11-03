package com.oak.dms.checkout;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signed file must never be opened for editing, whatever its type. These pin down, per format, what counts as
 * "already carries a signature" - and that a look-alike in the wrong kind of file does not.
 */
class EmbeddedSignatureDetectorTest {

    private static boolean has(String filename, byte[] bytes) throws IOException {
        return EmbeddedSignatureDetector.hasSignature(filename, new ByteArrayInputStream(bytes));
    }

    private static byte[] zip(String... entryNames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : entryNames) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ PDF

    @Test
    void aPdfWithASignatureDictionary_isSigned() throws IOException {
        byte[] signed = ascii("%PDF-1.7\n1 0 obj<</Type/Sig/Filter/Adobe.PPKLite/ByteRange[0 840 960 240]"
                + "/Contents<00>>>endobj");
        // The key is /ByteRange - written with or without spaces around it.
        byte[] spaced = ascii("%PDF-1.4\n<< /Type /Sig /ByteRange [ 0 1 2 3 ] >>");
        assertThat(has("contract.pdf", ascii("%PDF-1.7\n<</ByteRange[0 1 2 3]>>"))).isTrue();
        assertThat(has("contract.pdf", signed)).isTrue();
        assertThat(has("Contract.PDF", spaced)).isTrue();
    }

    @Test
    void aPlainPdf_isNotSigned() throws IOException {
        assertThat(has("scan.pdf", ascii("%PDF-1.7\n1 0 obj<</Type/Page>>endobj\n%%EOF"))).isFalse();
    }

    @Test
    void aMarkerThatStraddlesTwoReads_isStillFound() throws IOException {
        // The scanner reads 64 KiB at a time; put the marker across that boundary.
        byte[] padding = new byte[64 * 1024 - 4];
        java.util.Arrays.fill(padding, (byte) 'a');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(padding);
        out.write(ascii("/ByteRange"));
        out.write(ascii(" tail"));
        assertThat(has("big.pdf", out.toByteArray())).isTrue();
    }

    // ------------------------------------------------------------------ Word / Excel / PowerPoint

    @Test
    void officeFilesWithAnXmlSignaturePart_areSigned_forWordExcelAndPowerPoint() throws IOException {
        byte[] signed = zip("[Content_Types].xml", "word/document.xml", "_xmlsignatures/sig1.xml");
        assertThat(has("letter.docx", signed)).isTrue();
        assertThat(has("budget.xlsx", signed)).isTrue();
        assertThat(has("deck.pptx", signed)).isTrue();
        assertThat(has("macro.docm", signed)).isTrue();
    }

    @Test
    void officeFilesWithoutOne_areNotSigned() throws IOException {
        byte[] plain = zip("[Content_Types].xml", "word/document.xml", "docProps/core.xml");
        assertThat(has("letter.docx", plain)).isFalse();
        assertThat(has("deck.pptx", plain)).isFalse();
    }

    // ------------------------------------------------------------------ OpenDocument

    @Test
    void openDocumentFilesWithASignaturesFile_areSigned() throws IOException {
        byte[] signed = zip("mimetype", "content.xml", "META-INF/manifest.xml", "META-INF/documentsignatures.xml");
        assertThat(has("memo.odt", signed)).isTrue();
        assertThat(has("sheet.ods", signed)).isTrue();
        assertThat(has("slides.odp", signed)).isTrue();
        assertThat(has("memo.odt", zip("mimetype", "content.xml", "META-INF/manifest.xml"))).isFalse();
    }

    // ------------------------------------------------------------------ the type decides - and unknowns are not guessed at

    @Test
    void aTextFileThatMerelyMentionsTheMarker_isNotSigned() throws IOException {
        // Plain text cannot carry a signature; a note ABOUT one must not lock the file.
        assertThat(has("notes.txt", ascii("the PDF key /ByteRange marks a signature"))).isFalse();
        assertThat(has("data.csv", ascii("/ByteRange,_xmlsignatures/"))).isFalse();
        assertThat(has("letter.rtf", ascii("{\\rtf1 /ByteRange}"))).isFalse();
    }

    @Test
    void aFileThatIsNotReallyAPackage_isNotReportedSigned_andDoesNotBlowUp() throws IOException {
        assertThat(has("fake.docx", ascii("this is not a zip at all"))).isFalse();
        assertThat(has("empty.pptx", new byte[0])).isFalse();
    }

    @Test
    void onlyTypesThatCanCarryOneAreEverOpened() {
        for (String name : new String[]{"a.pdf", "a.PDF", "a.docx", "a.xlsx", "a.pptx", "a.odt", "a.ods", "a.odp"}) {
            assertThat(EmbeddedSignatureDetector.canHoldSignature(name)).as(name).isTrue();
        }
        for (String name : new String[]{"a.txt", "a.csv", "a.rtf", "a.png", "noextension", "trailing.", null}) {
            assertThat(EmbeddedSignatureDetector.canHoldSignature(name)).as(String.valueOf(name)).isFalse();
        }
    }
}
