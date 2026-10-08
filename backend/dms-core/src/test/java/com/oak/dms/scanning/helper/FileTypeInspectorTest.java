package com.oak.dms.scanning.helper;

import com.oak.dms.scanning.exception.FileTypeNotAllowedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The allowed-type gate: extension on the list AND content that really is that format. */
class FileTypeInspectorTest {

    private final FileTypeInspector inspector = new FileTypeInspector(new IngestionProperties());

    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    @ParameterizedTest
    @CsvSource({
            "scan.gif,  GIF89a........, image/gif",
            "mail.eml,  'Received: from mx.example.com\nFrom: a@b.c\n\nHi', message/rfc822",
            "data.json, '  {\"a\": 1}', application/json",
            "data.xml,  '<?xml version=\"1.0\"?><a/>', application/xml",
            "notes.md,  '# Title', text/markdown",
            "rows.tsv,  'a\tb', text/tab-separated-values",
    })
    void textAndSimpleBinaryFormats_areRecognisedByContent(String name, String content, String mime) {
        byte[] bytes = content.getBytes(StandardCharsets.ISO_8859_1);
        assertThat(inspector.inspect(name, bytes, bytes.length).mimeType()).isEqualTo(mime);
    }

    @Test
    void newBinaryFormats_matchTheirSignatures() {
        byte[] webp = pad("RIFF\0\0\0\0WEBPVP8 ");
        byte[] heic = pad("\0\0\0\u0018ftypheic\0\0\0\0");
        byte[] bmp = pad("BM");
        byte[] dwg = pad("AC1032");
        byte[] msg = Arrays.copyOf(OLE2, 64);

        assertThat(inspector.inspect("p.webp", webp, webp.length).mimeType()).isEqualTo("image/webp");
        assertThat(inspector.inspect("IMG_1.HEIC", heic, heic.length).mimeType()).isEqualTo("image/heic");
        assertThat(inspector.inspect("p.bmp", bmp, bmp.length).mimeType()).isEqualTo("image/bmp");
        assertThat(inspector.inspect("plan.dwg", dwg, dwg.length).mimeType()).isEqualTo("image/vnd.dwg");
        assertThat(inspector.inspect("mail.msg", msg, msg.length).mimeType()).isEqualTo("application/vnd.ms-outlook");
    }

    @Test
    void contentThatDoesNotMatchTheExtension_isRefused() {
        byte[] exe = pad("MZ");
        byte[] mp4 = pad("\0\0\0\u0018ftypisom");
        byte[] json = "not json".getBytes(StandardCharsets.US_ASCII);
        byte[] eml = "just some text".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> inspector.inspect("photo.gif", exe, exe.length)).isInstanceOf(FileTypeNotAllowedException.class);
        assertThatThrownBy(() -> inspector.inspect("video.heic", mp4, mp4.length)).isInstanceOf(FileTypeNotAllowedException.class);
        assertThatThrownBy(() -> inspector.inspect("data.json", json, json.length)).isInstanceOf(FileTypeNotAllowedException.class);
        assertThatThrownBy(() -> inspector.inspect("mail.eml", eml, eml.length)).isInstanceOf(FileTypeNotAllowedException.class);
        assertThatThrownBy(() -> inspector.inspect("notes.md", exe, exe.length)).isInstanceOf(FileTypeNotAllowedException.class);
    }

    @Test
    void activeContentAndArchives_stayRefused() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.US_ASCII);
        for (String name : new String[]{"page.html", "page.htm", "logo.svg", "macro.docm", "run.exe", "a.zip", "x.js"}) {
            assertThatThrownBy(() -> inspector.inspect(name, html, html.length))
                    .as(name).isInstanceOf(FileTypeNotAllowedException.class).hasMessageContaining("is not allowed");
        }
    }

    private static byte[] pad(String head) {
        byte[] start = head.getBytes(StandardCharsets.ISO_8859_1);
        return Arrays.copyOf(start, Math.max(64, start.length));
    }
}
