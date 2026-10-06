package com.oak.dms.watermark.service;

import com.oak.dms.watermark.config.WatermarkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Cleans the text that will be drawn onto somebody's document.
 *
 * <h2>What is removed, and why only that</h2>
 * A watermark carries values the requester does not fully control - their own
 * display name, a document title - and it is read by someone deciding whether
 * a copy is genuine. So the characters that can make text claim to be
 * something it is not have to go:
 * <ul>
 *   <li><b>Bidi embeddings, overrides and isolates</b> (U+202A-202E,
 *       U+2066-2069). These reorder the characters AFTER them. A name
 *       containing an override can make "CONFIDENTIAL" render as
 *       "LAITNEDIFNOC", or make a filename appear to end in {@code .pdf}
 *       when it does not. The bidi algorithm handles real right-to-left
 *       text on its own - see {@code WatermarkRenderer} - so nothing
 *       legitimate needs these.</li>
 *   <li><b>Zero-width space</b> (U+200B) and the <b>byte-order mark</b>
 *       (U+FEFF). Invisible, and useful only for breaking up a word so it
 *       slips past a reader or a search.</li>
 *   <li><b>Control characters</b> (C0/C1, soft hyphen). A newline in a
 *       watermark is at best a layout accident.</li>
 * </ul>
 *
 * <h2>What is deliberately kept</h2>
 * <b>ZWJ and ZWNJ</b> (U+200D, U+200C) stay. They are invisible too, but
 * they are not decoration: in Persian they are what separates
 * {@code می‌رود} from {@code میرود}, and in Indic scripts they are what
 * decides whether two consonants form a conjunct. Stripping them does not
 * tidy a name up, it misspells it - and a watermark that misspells the name
 * of the person it is holding responsible is worse than useless.
 * <p>
 * This is the whole reason the sanitiser is a character-by-character
 * allowlist rather than "strip everything in category Cf": ZWJ, ZWNJ and the
 * bidi overrides are all Cf.
 */
@Component
@RequiredArgsConstructor
public class TextSanitizer {

    /** Kept: these change how neighbouring letters are shaped, not what they say. */
    private static final char ZWNJ = '‌';
    private static final char ZWJ = '‍';

    private final WatermarkProperties properties;

    /** Cleans and truncates one resolved placeholder value. */
    public String token(String value) {
        return clean(value, properties.getStyle().getMaxTokenLength());
    }

    /** Cleans and truncates the final watermark text. */
    public String text(String value) {
        return clean(value, properties.getStyle().getMaxTextLength());
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;

        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);

            if (isWhitespace(codePoint)) {
                // Runs of any whitespace collapse to one plain space, so a
                // tab or a newline can't stretch or break the mark.
                pendingSpace = result.length() > 0;
                continue;
            }
            if (isRemoved(codePoint)) {
                continue;
            }
            if (pendingSpace) {
                result.append(' ');
                pendingSpace = false;
            }
            result.appendCodePoint(codePoint);
        }

        String cleaned = result.toString();
        if (maxLength > 0 && cleaned.length() > maxLength) {
            // Cut on a code point boundary, never through a surrogate pair -
            // half an emoji is an unrenderable character.
            int end = maxLength;
            if (Character.isHighSurrogate(cleaned.charAt(end - 1))) {
                end--;
            }
            cleaned = cleaned.substring(0, end);
        }
        return cleaned;
    }

    private static boolean isWhitespace(int codePoint) {
        return codePoint == ' ' || codePoint == '\t' || codePoint == '\n' || codePoint == '\r'
                || Character.isSpaceChar(codePoint);
    }

    private static boolean isRemoved(int codePoint) {
        if (codePoint == ZWJ || codePoint == ZWNJ) {
            return false;   // keep - see the class comment
        }
        return switch (Character.getType(codePoint)) {
            // Cf covers ZWJ/ZWNJ (handled above), the bidi controls, the BOM
            // and the Arabic letter marks. Everything still reaching here is
            // invisible and changes order or appearance, so it goes.
            case Character.FORMAT,
                 Character.CONTROL,
                 Character.PRIVATE_USE,
                 Character.SURROGATE,
                 Character.UNASSIGNED,
                 Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR -> true;
            default -> codePoint == '​' || codePoint == '﻿' || codePoint == '­';
        };
    }
}
