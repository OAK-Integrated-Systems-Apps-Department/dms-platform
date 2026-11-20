package com.oak.dms.document;

import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DOC-15: the header is complete, ASCII, correctly quoted and encoded for every name, hostile or international. */
class ContentDispositionHeaderTest {

    private static final String SINHALA = "ගොනුව";
    private static final String TAMIL = "கோப்பு";

    /** The only shapes the header may take. */
    private static final Pattern SHAPE = Pattern.compile(
            "^(attachment|inline); filename=\"[A-Za-z0-9 ._\\-()\\[\\]+,&@#!~]+\"(; filename\\*=UTF-8''[A-Za-z0-9!#$&+\\-.^_`|~%]+)?$");
    /** The web client's own pattern (frontend/lib/documents/api.ts), to see what it would pick out of the header. */
    private static final Pattern FRONTEND = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private static String decodeStar(String header) {
        Matcher m = Pattern.compile("filename\\*=UTF-8''([^;]+)$").matcher(header);
        if (!m.find()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String s = m.group(1);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String fallbackOf(String header) {
        Matcher m = Pattern.compile("filename=\"([^\"]*)\"").matcher(header);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** Everything that must hold for any input. */
    private static void assertWellFormed(String type, String input, String header) {
        assertThat(header).as("header for %s", input).isNotNull();
        for (char c : header.toCharArray()) {
            assertThat((int) c).as("character in the header for %s", input).isBetween(0x20, 0x7e);   // ASCII, no CR/LF/NUL/controls
        }
        assertThat(header).matches(SHAPE);
        assertThat(header).startsWith(type + "; filename=\"");
        String fallback = fallbackOf(header);
        assertThat(fallback).doesNotContain("%", ";", "\"", "\\");
        assertThat(header.length()).as("header length").isLessThanOrEqualTo(1100);
        // what the web client's regex picks out: the fallback, with nothing that decodeURIComponent could choke on or cut short
        Matcher fe = FRONTEND.matcher(header);
        assertThat(fe.find()).isTrue();
        assertThat(fe.group(1)).isEqualTo(fallback).doesNotContain("%");
        assertThat(fallback).as("fallback keeps a readable extension when the name has one").isNotBlank();
    }

    @Test
    void plainAsciiNamesKeepTheSingleFilenameForm() {
        assertThat(ContentDispositionHeader.attachment("report.pdf")).isEqualTo("attachment; filename=\"report.pdf\"");
        assertThat(ContentDispositionHeader.inline("My Report (final).pdf")).isEqualTo("inline; filename=\"My Report (final).pdf\"");
        assertThat(ContentDispositionHeader.of("ATTACHMENT", "a.txt")).isEqualTo("attachment; filename=\"a.txt\"");
    }

    @Test
    void tableOfInternationalAndHostileNames() {
        Map<String, String[]> cases = new LinkedHashMap<>();       // input -> {normalised name, ASCII fallback}
        cases.put("report.pdf", new String[]{"report.pdf", "report.pdf"});
        cases.put(SINHALA + ".pdf", new String[]{SINHALA + ".pdf", "document.pdf"});
        cases.put(TAMIL + ".pdf", new String[]{TAMIL + ".pdf", "document.pdf"});
        cases.put("📄report.pdf", new String[]{"📄report.pdf", "_report.pdf"});
        cases.put("café.pdf", new String[]{"café.pdf", "caf_.pdf"});
        cases.put("Report 100%.pdf", new String[]{"Report 100%.pdf", "Report 100_.pdf"});
        cases.put("50%20off.pdf", new String[]{"50%20off.pdf", "50_20off.pdf"});
        cases.put("a;b.pdf", new String[]{"a;b.pdf", "a_b.pdf"});
        cases.put("a\"b.pdf", new String[]{"a\"b.pdf", "a_b.pdf"});
        cases.put("a\\b.pdf", new String[]{"b.pdf", "b.pdf"});                         // backslash is a path separator
        cases.put("../report.pdf", new String[]{"report.pdf", "report.pdf"});
        cases.put("..\\..\\evil.pdf", new String[]{"evil.pdf", "evil.pdf"});
        cases.put("/etc/passwd.txt", new String[]{"passwd.txt", "passwd.txt"});
        cases.put("dir/", new String[]{"dir", "dir"});
        cases.put("a\r\nb.pdf", new String[]{"ab.pdf", "ab.pdf"});                    // CR/LF removed
        cases.put("Set-Cookie: x\r\n\r\nHTTP 200 OK.pdf", new String[]{"Set-Cookie: xHTTP 200 OK.pdf", "Set-Cookie_ xHTTP 200 OK.pdf"});
        cases.put("a\u0000b.pdf", new String[]{"ab.pdf", "ab.pdf"});                  // NUL
        cases.put("a\u0001\u007f\u0085b.pdf", new String[]{"ab.pdf", "ab.pdf"});      // C0, DEL, C1 controls
        cases.put("invoice‮txt.pdf", new String[]{"invoicetxt.pdf", "invoicetxt.pdf"});   // right-to-left override
        cases.put("a​b﻿c⁠d.pdf", new String[]{"abcd.pdf", "abcd.pdf"});          // zero-width space, BOM, word joiner
        cases.put("a b c.pdf", new String[]{"abc.pdf", "abc.pdf"});                   // line / paragraph separators
        cases.put("a\uD800b.pdf", new String[]{"ab.pdf", "ab.pdf"});                            // unpaired surrogate
        cases.put("a b.pdf", new String[]{"a b.pdf", "a b.pdf"});                          // no-break space -> space
        cases.put("   .pdf", new String[]{"document.pdf", "document.pdf"});
        cases.put(".pdf", new String[]{"document.pdf", "document.pdf"});
        cases.put("", new String[]{"document", "document"});
        cases.put("  ", new String[]{"document", "document"});
        cases.put("..", new String[]{"document", "document"});
        cases.put("/", new String[]{"document", "document"});
        cases.put("‮​﻿", new String[]{"document", "document"});                  // only unsupported characters
        cases.put("🙂🙂", new String[]{"🙂🙂", "document"});   // emoji only, no extension
        cases.put(SINHALA, new String[]{SINHALA, "document"});

        for (Map.Entry<String, String[]> e : cases.entrySet()) {
            String input = e.getKey();
            String expectedName = e.getValue()[0];
            String expectedFallback = e.getValue()[1];
            for (String type : new String[]{"attachment", "inline"}) {
                String header = ContentDispositionHeader.of(type, input);
                assertWellFormed(type, input, header);
                String star = decodeStar(header);
                assertThat(star == null ? fallbackOf(header) : star)
                        .as("name carried by the header for %s", input).isEqualTo(expectedName);
                if (expectedFallback != null) {
                    assertThat(fallbackOf(header)).as("fallback for %s", input).isEqualTo(expectedFallback);
                }
                // filename* only when the name is not already plain safe ASCII
                assertThat(star == null).isEqualTo(fallbackOf(header).equals(expectedName));
            }
        }
    }

    @Test
    void theRealNameIsPercentEncodedInUtf8() {
        assertThat(ContentDispositionHeader.attachment(SINHALA + ".pdf")).isEqualTo(
                "attachment; filename=\"document.pdf\"; filename*=UTF-8''%E0%B6%9C%E0%B7%9C%E0%B6%B1%E0%B7%94%E0%B7%80.pdf");
        assertThat(ContentDispositionHeader.attachment(TAMIL + ".pdf")).endsWith(
                "filename*=UTF-8''%E0%AE%95%E0%AF%8B%E0%AE%AA%E0%AF%8D%E0%AE%AA%E0%AF%81.pdf");
        assertThat(ContentDispositionHeader.attachment("📄report.pdf")).endsWith("filename*=UTF-8''%F0%9F%93%84report.pdf");
        assertThat(ContentDispositionHeader.attachment("café.pdf")).endsWith("filename*=UTF-8''caf%C3%A9.pdf");
        // quotes, semicolons, percent signs and spaces are valid name content: encoded, not removed
        assertThat(ContentDispositionHeader.attachment("a\"b;c 100%.pdf")).endsWith("filename*=UTF-8''a%22b%3Bc%20100%25.pdf");
        assertThat(ContentDispositionHeader.attachment("it's (a) *test*.pdf")).endsWith("filename*=UTF-8''it%27s%20%28a%29%20%2Atest%2A.pdf");
    }

    @Test
    void theZeroWidthJoinersThatSpellSinhalaAreKept() {
        String conjunct = "ක්‍ය.pdf";                 // a Sinhala conjunct needs U+200D
        String header = ContentDispositionHeader.attachment(conjunct);

        assertThat(header).contains("%E2%80%8D");
        assertThat(decodeStar(header)).isEqualTo(conjunct);
    }

    @Test
    void theHeaderNeverContainsLineBreaksSoNothingCanBeInjected() {
        String header = ContentDispositionHeader.attachment("x\r\nSet-Cookie: a=b\r\nX-Evil: 1\r\n\r\n<html>.pdf");

        assertThat(header).doesNotContain("\r", "\n");
        assertThat(header.split("\r\n|\n")).hasSize(1);
    }

    @Test
    void aVeryLongNameIsBoundedOnCodePointBoundariesAndKeepsItsExtension() {
        String ascii = "a".repeat(400) + ".pdf";
        String h1 = ContentDispositionHeader.attachment(ascii);
        assertWellFormed("attachment", "400 a", h1);
        assertThat(fallbackOf(h1).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ContentDispositionHeader.MAX_NAME_BYTES);
        assertThat(fallbackOf(h1)).endsWith(".pdf");

        String sinhalaLong = SINHALA.repeat(60) + ".pdf";             // 300 code points, 900 bytes
        String h2 = ContentDispositionHeader.attachment(sinhalaLong);
        assertWellFormed("attachment", "long sinhala", h2);
        String decoded = decodeStar(h2);
        assertThat(decoded.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ContentDispositionHeader.MAX_NAME_BYTES);
        assertThat(decoded).endsWith(".pdf").doesNotContain("�");           // no code point was split
        assertThat(SINHALA.repeat(60)).startsWith(decoded.substring(0, decoded.length() - 4));

        String emojiLong = "📄".repeat(200) + ".pdf";          // 4-byte code points
        String decodedEmoji = decodeStar(ContentDispositionHeader.attachment(emojiLong));
        assertThat(decodedEmoji.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255);
        assertThat(decodedEmoji).endsWith(".pdf").doesNotContain("�");

        // an absurd "extension" is not kept; the name is still bounded and never empty
        String noExt = "x".repeat(300) + "." + "y".repeat(100);
        assertThat(fallbackOf(ContentDispositionHeader.attachment(noExt)).length()).isLessThanOrEqualTo(255);
        assertThat(ContentDispositionHeader.attachment("." + "z".repeat(400))).isNotBlank();
    }

    @Test
    void theDispositionIsTheCallersAndAnythingElseIsRefused() {
        assertThat(ContentDispositionHeader.of("inline", "a.pdf")).startsWith("inline;");
        assertThat(ContentDispositionHeader.of("attachment", "a.pdf")).startsWith("attachment;");
        assertThatThrownBy(() -> ContentDispositionHeader.of("form-data", "a.pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContentDispositionHeader.of(null, "a.pdf")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContentDispositionHeader.of("attachment; x=y", "a.pdf")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springParsesTheHeaderBackToTheSanitisedName() {
        for (String name : new String[]{"report.pdf", SINHALA + ".pdf", TAMIL + ".pdf", "📄r.pdf", "café.pdf",
                "Report 100%.pdf", "50%20off.pdf", "a;b.pdf", "a\"b.pdf"}) {
            ContentDisposition parsed = ContentDisposition.parse(ContentDispositionHeader.attachment(name));
            assertThat(parsed.getFilename()).as(name).isEqualTo(name);
            assertThat(parsed.isAttachment()).isTrue();
        }
    }

    @Test
    void nullAndBlankNamesGetTheFallbackName() {
        assertThat(ContentDispositionHeader.attachment(null)).isEqualTo("attachment; filename=\"document\"");
        assertThat(ContentDispositionHeader.inline("")).isEqualTo("inline; filename=\"document\"");
    }
}
