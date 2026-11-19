package com.oak.dms.document;

import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.watermark.service.WatermarkDefaultsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DOC-16: the shared default-download watermark. The decisions - when to stream, when to buffer, when a signed file is
 * left alone, which version's details are used - with the watermark engine mocked. The same behaviour against real
 * PostgreSQL, storage and PDFs is in {@code DefaultWatermarkVersionDownloadIT}.
 */
class DefaultDownloadWatermarkTest {

    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final WatermarkDefaultsService defaults = mock(WatermarkDefaultsService.class);
    private final DefaultDownloadWatermark component = new DefaultDownloadWatermark(defaults);

    private static DocumentContentReader.VersionDetails details(String filename, String mime, int versionNumber, long size) {
        return new DocumentContentReader.VersionDetails(UUID.randomUUID(), UUID.randomUUID(), versionNumber, "Title",
                filename, mime, size);
    }

    private static DownloadedDocument stored(byte[] bytes, String mime, String filename) {
        return new DownloadedDocument(new ByteArrayInputStream(bytes), bytes.length, mime, filename);
    }

    private static byte[] drain(DownloadedDocument d) throws IOException {
        try (InputStream in = d.content()) {
            return in.readAllBytes();
        }
    }

    private static byte[] signedPdf() {
        return ("%PDF-1.7\n1 0 obj\n<< /Type /Sig /Filter /Adobe.PPKLite /ByteRange [0 100 200 50] /Contents <00> >>\nendobj\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] docx(boolean signed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (signed) {
                zip.putNextEntry(new ZipEntry("_xmlsignatures/sig1.xml"));
                zip.write("<Signature/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** A stream that fails the test if anything reads it: proof that a download which should stream is not buffered. */
    private static DownloadedDocument unreadable(String mime, String filename, long size) {
        InputStream tripwire = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("the stored stream must not be read when the watermark does not apply");
            }
        };
        return new DownloadedDocument(tripwire, size, mime, filename);
    }

    // ------------------------------------------------------------------ streaming first (DOC-02)

    @Test
    void whenTheWatermarkDoesNotApply_theStoredStreamIsReturnedUntouchedAndNeverRead() {
        var d = details("big.pdf", PDF, 3, 24_000_000);
        when(defaults.isDownloadWatermarkApplicable(d)).thenReturn(false);
        DownloadedDocument stream = unreadable(PDF, "big.pdf", 24_000_000);

        DefaultDownloadWatermark.Outcome outcome = component.apply(stream, d);

        assertThat(outcome.document()).isSameAs(stream);
        assertThat(outcome.exemption()).isNull();
        verify(defaults, never()).applyIfConfigured(any(), any());
    }

    // ------------------------------------------------------------------ the watermark is applied, with this version's details

    @Test
    void anEligibleFile_isWatermarked_withTheRequestedVersionsOwnDetails() throws Exception {
        byte[] original = "%PDF-1.4 plain, unsigned".getBytes(StandardCharsets.ISO_8859_1);
        byte[] stamped = "%PDF-1.4 stamped".getBytes(StandardCharsets.ISO_8859_1);
        var d = details("memo.pdf", PDF, 2, original.length);
        when(defaults.isDownloadWatermarkApplicable(d)).thenReturn(true);
        when(defaults.applyIfConfigured(any(), any())).thenReturn(stamped);

        DefaultDownloadWatermark.Outcome outcome = component.apply(stored(original, PDF, "memo.pdf"), d);

        ArgumentCaptor<DocumentContentReader.VersionDetails> used = ArgumentCaptor.forClass(DocumentContentReader.VersionDetails.class);
        verify(defaults).applyIfConfigured(any(), used.capture());
        assertThat(used.getValue()).as("the details of the version asked for, unchanged").isEqualTo(d);
        assertThat(used.getValue().versionNumber()).isEqualTo(2);
        assertThat(outcome.exemption()).isNull();
        assertThat(outcome.document().sizeBytes()).as("Content-Length is the watermarked length").isEqualTo(stamped.length);
        assertThat(outcome.document().contentType()).isEqualTo(PDF);
        assertThat(outcome.document().filename()).isEqualTo("memo.pdf");
        assertThat(drain(outcome.document())).isEqualTo(stamped);
    }

    @Test
    void whenTheEngineServesTheOriginalBecauseItCouldNotWatermark_thatIsWhatIsSent() throws Exception {
        byte[] original = "%PDF-1.4 damaged".getBytes(StandardCharsets.ISO_8859_1);
        var d = details("memo.pdf", PDF, 1, original.length);
        when(defaults.isDownloadWatermarkApplicable(d)).thenReturn(true);
        when(defaults.applyIfConfigured(any(), any())).thenAnswer(call -> call.getArgument(0));

        DefaultDownloadWatermark.Outcome outcome = component.apply(stored(original, PDF, "memo.pdf"), d);

        assertThat(drain(outcome.document())).isEqualTo(original);
        assertThat(outcome.exemption()).isNull();
    }

    // ------------------------------------------------------------------ signed files: exempt, byte for byte

    @Test
    void aSignedPdf_isReturnedByteForByte_theEngineIsNeverCalled_andTheExemptionIsNamed() throws Exception {
        byte[] signed = signedPdf();
        var d = details("contract.pdf", PDF, 4, signed.length);
        when(defaults.isDownloadWatermarkApplicable(d)).thenReturn(true);

        DefaultDownloadWatermark.Outcome outcome = component.apply(stored(signed, PDF, "contract.pdf"), d);

        assertThat(outcome.exemption()).isEqualTo(DefaultDownloadWatermark.SIGNED_CONTENT_EXEMPT).isEqualTo("SIGNED_CONTENT_EXEMPT");
        assertThat(outcome.document().sizeBytes()).isEqualTo(signed.length);
        assertThat(drain(outcome.document())).isEqualTo(signed);
        verify(defaults, never()).applyIfConfigured(any(), any());
    }

    @Test
    void aSignedOfficeFile_isExemptToo_andAnUnsignedOneIsWatermarked() throws Exception {
        byte[] signed = docx(true);
        var ds = details("memo.docx", DOCX, 1, signed.length);
        when(defaults.isDownloadWatermarkApplicable(ds)).thenReturn(true);

        DefaultDownloadWatermark.Outcome exempt = component.apply(stored(signed, DOCX, "memo.docx"), ds);

        assertThat(exempt.exemption()).isEqualTo("SIGNED_CONTENT_EXEMPT");
        assertThat(drain(exempt.document())).isEqualTo(signed);
        verify(defaults, never()).applyIfConfigured(any(), any());

        byte[] unsigned = docx(false);
        byte[] stamped = "stamped docx".getBytes(StandardCharsets.UTF_8);
        var du = details("memo.docx", DOCX, 1, unsigned.length);
        when(defaults.isDownloadWatermarkApplicable(du)).thenReturn(true);
        when(defaults.applyIfConfigured(any(), any())).thenReturn(stamped);

        DefaultDownloadWatermark.Outcome watermarked = component.apply(stored(unsigned, DOCX, "memo.docx"), du);

        assertThat(watermarked.exemption()).isNull();
        assertThat(drain(watermarked.document())).isEqualTo(stamped);
    }

    @Test
    void aTypeThatCannotHoldASignature_isNeverTreatedAsSigned_whateverItsBytesSay() throws Exception {
        byte[] png = "\u0089PNG pretend /ByteRange [0 1 2 3]".getBytes(StandardCharsets.ISO_8859_1);
        byte[] stamped = "stamped png".getBytes(StandardCharsets.UTF_8);
        var d = details("scan.png", "image/png", 1, png.length);
        when(defaults.isDownloadWatermarkApplicable(d)).thenReturn(true);
        when(defaults.applyIfConfigured(any(), any())).thenReturn(stamped);

        DefaultDownloadWatermark.Outcome outcome = component.apply(stored(png, "image/png", "scan.png"), d);

        assertThat(outcome.exemption()).isNull();
        assertThat(drain(outcome.document())).isEqualTo(stamped);
    }

    @Test
    void ifTheDetectorCannotTell_theEnginesOwnRefusalOfASignedFileStillProtectsIt() throws Exception {
        byte[] notAZip = "not a zip at all".getBytes(StandardCharsets.UTF_8);   // a .docx the detector cannot read
        var d = details("memo.docx", DOCX, 1, notAZip.length);
        when(defaults.isDownloadWatermarkApplicable(d)).thenReturn(true);
        when(defaults.applyIfConfigured(any(), any())).thenAnswer(call -> call.getArgument(0));   // the engine's "serve the original"

        DefaultDownloadWatermark.Outcome outcome = component.apply(stored(notAZip, DOCX, "memo.docx"), d);

        assertThat(drain(outcome.document())).isEqualTo(notAZip);
        assertThat(outcome.exemption()).isNull();
    }

    // ------------------------------------------------------------------ failure

    @Test
    void aStoredStreamThatCannotBeRead_failsTheDownloadWithAClearMessage() {
        var d = details("memo.pdf", PDF, 1, 10);
        when(defaults.isDownloadWatermarkApplicable(d)).thenReturn(true);
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("storage read failed");
            }
        };

        assertThatThrownBy(() -> component.apply(new DownloadedDocument(broken, 10, PDF, "memo.pdf"), d))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not read document");
    }
}
