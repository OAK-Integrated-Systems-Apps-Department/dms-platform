package com.oak.dms.document;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds the {@code Content-Disposition} header of a file response, the one way, for every download endpoint (DOC-15).
 * <p>
 * <b>Why it is not hand-built or taken from Spring's builder.</b> Tomcat silently <i>drops</i> a header whose value holds a
 * character above U+00FF - every Sinhala and Tamil letter - so a header built as {@code filename="<raw name>"} reached the
 * browser as no header at all: no file name and no {@code attachment}. Spring's {@code ContentDisposition} does emit
 * {@code filename*}, but leaves {@code %} and {@code ;} in its ASCII fallback, which the web client's parser then
 * mis-reads (a {@code %} makes its {@code decodeURIComponent} throw, a {@code ;} truncates the name). So this class builds
 * both parameters itself:
 * <pre>{@code attachment; filename="document.pdf"; filename*=UTF-8''%E0%B6%9C%E0%B7%9C%E0%B6%B1%E0%B7%94%E0%B7%80.pdf}</pre>
 * <ul>
 *   <li>{@code filename} - an ASCII-only fallback for clients that do not read {@code filename*}: letters, digits and a few
 *       safe punctuation marks stay, every other run of characters becomes one {@code _}; it never holds {@code % ; " \}
 *       or a control, and if nothing readable is left of the base name it is {@code document}, keeping the extension.</li>
 *   <li>{@code filename*} (RFC 5987/8187) - the real name, UTF-8 percent-encoded. Sent only when the name is not already
 *       plain safe ASCII (a name like {@code report.pdf} keeps the single {@code filename="report.pdf"} form).</li>
 * </ul>
 * The whole value is ASCII, so Tomcat cannot drop it, and it cannot contain CR, LF or any other control character.
 * <p>
 * <b>What it does to the name</b> (only in the header; the stored name is never changed): the name is treated as an
 * untrusted name, not a path - {@code /} and {@code \} are separators and only the last segment is kept; control
 * characters, line and paragraph separators, unpaired surrogates and invisible format characters (bidirectional overrides,
 * zero-width space, word joiner, byte-order mark, soft hyphen, tag characters, ...) are removed; the zero-width joiner and
 * non-joiner are <b>kept</b>, because Sinhala, Tamil and many other scripts (and emoji sequences) need them to spell
 * correctly. Quotes, semicolons and percent signs are valid file-name content and are
 * kept in {@code filename*}, where percent-encoding makes them safe. The name is limited to {@value #MAX_NAME_BYTES}
 * UTF-8 bytes (the usual file-system limit; at most 3 x that when percent-encoded), cut on a code-point boundary, keeping
 * a short extension. An empty result is {@code document}.
 * <p>
 * It never decides inline versus attachment: the caller passes the disposition it has already chosen
 * ({@link ContentServingPolicy#dispositionType}, DOC-04).
 */
public final class ContentDispositionHeader {

    /** Longest file name, in UTF-8 bytes, that goes into a header (255 is the common file-system limit). */
    public static final int MAX_NAME_BYTES = 255;
    /** What is used when no usable name is left. */
    public static final String FALLBACK_NAME = "document";

    private static final int MAX_EXTENSION_BYTES = 20;
    /** ASCII punctuation kept in the fallback; {@code % ; " \ * = ' } and the rest become {@code _}. */
    private static final String FALLBACK_PUNCTUATION = " ._-()[]+,&@#!~";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private ContentDispositionHeader() {
    }

    public static String attachment(String filename) {
        return of("attachment", filename);
    }

    public static String inline(String filename) {
        return of("inline", filename);
    }

    /**
     * @param dispositionType {@code attachment} or {@code inline}, already decided by the caller
     * @param filename        the file's name as stored (untrusted; may be null)
     */
    public static String of(String dispositionType, String filename) {
        String type = dispositionType == null ? "" : dispositionType.toLowerCase(Locale.ROOT);
        if (!type.equals("attachment") && !type.equals("inline")) {
            throw new IllegalArgumentException("Content-Disposition type must be attachment or inline: " + dispositionType);
        }
        String name = normalise(filename);
        String fallback = asciiFallback(name);
        StringBuilder header = new StringBuilder(type).append("; filename=\"").append(fallback).append('"');
        if (!fallback.equals(name)) {
            header.append("; filename*=UTF-8''").append(percentEncode(name));
        }
        return header.toString();
    }

    /** The name that goes into the header: last path segment, unsafe characters removed, bounded, never empty. */
    static String normalise(String filename) {
        if (filename == null) {
            return FALLBACK_NAME;
        }
        String name = filename.replace('\\', '/');
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '/') {
            end--;
        }
        name = name.substring(name.lastIndexOf('/', end - 1) + 1, end);

        StringBuilder kept = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '‌' || cp == '‍') {
                kept.appendCodePoint(cp);               // needed to spell Sinhala, Tamil, Persian and emoji sequences
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE) {
                continue;
            }
            kept.appendCodePoint(type == Character.SPACE_SEPARATOR ? ' ' : cp);
        }
        String cleaned = trim(kept.toString());
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return FALLBACK_NAME;
        }
        // ".pdf" alone (the rest was removed): give the extension a name rather than a hidden-file look
        if (cleaned.startsWith(".") && cleaned.indexOf('.', 1) < 0) {
            cleaned = FALLBACK_NAME + cleaned;
        }
        return limit(cleaned);
    }

    /** Leading and trailing spaces and joiners go; they hide nothing useful and only make a name look empty. */
    private static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == ' ' || s.charAt(start) == '‌' || s.charAt(start) == '‍')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '‌' || s.charAt(end - 1) == '‍')) {
            end--;
        }
        return s.substring(start, end);
    }

    /** At most {@link #MAX_NAME_BYTES} UTF-8 bytes, cut between code points, keeping a short extension. */
    private static String limit(String name) {
        if (utf8Length(name) <= MAX_NAME_BYTES) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot) : "";
        if (utf8Length(ext) > MAX_EXTENSION_BYTES) {
            ext = "";
        }
        String base = ext.isEmpty() ? name : name.substring(0, dot);
        int room = MAX_NAME_BYTES - utf8Length(ext);
        StringBuilder cut = new StringBuilder();
        int used = 0;
        for (int i = 0; i < base.length(); ) {
            int cp = base.codePointAt(i);
            int bytes = utf8Length(cp);
            if (used + bytes > room) {
                break;
            }
            cut.appendCodePoint(cp);
            used += bytes;
            i += Character.charCount(cp);
        }
        String result = trim(cut.toString());
        return (result.isEmpty() ? FALLBACK_NAME : result) + ext;
    }

    /** ASCII-only and free of {@code % ; " \} and controls: safe for a naive parser and for decodeURIComponent. */
    static String asciiFallback(String name) {
        int dot = name.lastIndexOf('.');
        boolean hasExt = dot > 0 && dot < name.length() - 1;
        String stem = hasExt ? name.substring(0, dot) : name;
        String ext = hasExt ? name.substring(dot) : "";
        String safeStem = safeAscii(stem);
        if (!hasLetterOrDigit(safeStem)) {
            safeStem = FALLBACK_NAME;                  // e.g. a Sinhala name: nothing readable is left of it
        }
        String safeExt = safeAscii(ext);
        return trim(safeStem) + (safeExt.equals(".") ? "" : safeExt);
    }

    private static String safeAscii(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean underscoreRun = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            boolean ok = cp < 0x80 && (Character.isLetterOrDigit(cp) || FALLBACK_PUNCTUATION.indexOf(cp) >= 0);
            if (ok) {
                out.append((char) cp);
                underscoreRun = cp == '_';
            } else if (!underscoreRun) {
                out.append('_');                      // one underscore per run of unsupported characters
                underscoreRun = true;
            }
        }
        return out.toString();
    }

    private static boolean hasLetterOrDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80 && Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }

    /** RFC 5987 attr-char stays; every other byte of the UTF-8 form becomes %XX. */
    static String percentEncode(String name) {
        StringBuilder out = new StringBuilder(name.length() * 3);
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0f]);
            }
        }
        return out.toString();
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int utf8Length(int codePoint) {
        return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    }
}
