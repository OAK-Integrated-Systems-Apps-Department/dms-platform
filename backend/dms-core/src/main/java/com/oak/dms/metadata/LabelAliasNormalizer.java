package com.oak.dms.metadata;

import com.oak.dms.common.dto.ErrorResponse;
import com.oak.dms.common.exception.FieldValidationException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single authoritative normalization + validation of an OCR label-hint
 * list ({@code labelAliases}) before it is stored in
 * {@code metadata_field_label_aliases} - the frontend mirrors these rules
 * client-side for instant feedback, but this is the one that counts.
 * <p>
 * Each entry first loses any invisible formatting characters picked up by
 * copy-paste (zero-width space, soft hyphen, BOM, bidi marks - see
 * {@link #INVISIBLE_FORMAT_CHARACTER}), then is trimmed and has every
 * internal whitespace run collapsed to one plain space - ocr-worker compares
 * a hint's characters literally (and decides "one word vs. phrase" by
 * whether it contains a plain space), so a doubled, non-breaking or
 * zero-width space pasted in by accident would otherwise make a hint
 * silently match nothing. It must then be 2..100 characters long, contain
 * at least one letter or digit, and contain no control characters.
 * Case-insensitive duplicates are dropped silently, keeping the first
 * occurrence - the list's order IS the match priority, so the earlier
 * position wins - using the same case fold as the database's unique index
 * ({@link #caseFoldKey}). At most {@link #MAX_ALIASES} survive
 * de-duplication.
 * <p>
 * Pure and stateless (no Spring bean) for the same reason as
 * {@link DisplayOrderNormalizer}: it's plain list manipulation, and keeping
 * it free of repositories makes the rules unit-testable on their own. The
 * DB's {@code ck_mfla_alias_text} CHECK (V40) is the backstop for anything
 * that bypasses this class; the two must stay compatible - everything this
 * accepts, the CHECK accepts.
 */
public final class LabelAliasNormalizer {

    /** The request/response JSON property every hint error is reported under. */
    public static final String FIELD_NAME = "labelAliases";

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 100;
    public static final int MAX_ALIASES = 20;

    /**
     * Unicode-aware, so a non-breaking space pasted from a PDF/Word document
     * collapses too - Postgres' btrim (used by the CHECK) only strips plain
     * spaces, and after this collapse every whitespace run, leading and
     * trailing ones included, is exactly one plain space.
     */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Invisible format characters (Unicode category Cf) that ride along when
     * a hint is copied out of a PDF, a Word document or a web page - zero-width
     * space, soft hyphen, byte-order mark / zero-width no-break space, word
     * joiner, left-to-right/right-to-left marks and the other bidi controls.
     * None of them is whitespace to the collapse above, and OCR text never
     * carries them, so a hint that kept one would look exactly like "PO No"
     * on every screen yet never match anything - it wouldn't even count as a
     * duplicate of the real "PO No". Stripped rather than rejected because
     * the admin can't see them to remove them. ZERO WIDTH NON-JOINER (U+200C)
     * and ZERO WIDTH JOINER (U+200D) are kept: in Sinhala (the conjunct in
     * "Sri Lanka" itself), Persian and the Indic scripts they are part of
     * correct spelling, and removing them changes the word.
     */
    private static final Pattern INVISIBLE_FORMAT_CHARACTER = Pattern.compile("[\\p{Cf}&&[^\\u200C\\u200D]]");

    /**
     * Control characters (category Cc) still present after the whitespace
     * collapse - tab, newline and the other whitespace controls are gone by
     * then, so what's left (NUL, ESC, DEL, the C1 range, ...) is nothing a
     * keyboard or a copy-paste produces. Rejected rather than stripped:
     * unlike the invisible format characters above they only arrive from a
     * broken or scripted client, and quietly rewriting that input would hide
     * the bug. Postgres can't store a NUL in text at all (the insert would
     * fail as a generic 409), and the rest could never match OCR text.
     */
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cc}");

    private LabelAliasNormalizer() {
    }

    /**
     * Normalizes and validates {@code rawAliases}, returning the list to
     * store, in priority order. A null list is treated as empty (no hints).
     * Every problem is collected rather than stopping at the first, so the
     * caller can show them all at once.
     *
     * @throws FieldValidationException (400, field {@value #FIELD_NAME}) if any
     *                                  entry is invalid or more than
     *                                  {@link #MAX_ALIASES} remain after de-duplication
     */
    public static List<String> normalize(List<String> rawAliases) {
        if (rawAliases == null || rawAliases.isEmpty()) {
            return List.of();
        }

        List<ErrorResponse.FieldValidationError> errors = new ArrayList<>();
        List<String> normalized = new ArrayList<>();
        Set<String> seenCaseFolded = new HashSet<>();

        for (int index = 0; index < rawAliases.size(); index++) {
            String alias = normalizeOne(rawAliases.get(index));
            String problem = problemWith(alias);
            if (problem != null) {
                String shown = alias.isEmpty() ? "" : " \"" + shown(alias) + "\"";
                errors.add(new ErrorResponse.FieldValidationError(FIELD_NAME,
                        "Label hint #" + (index + 1) + shown + " " + problem));
                continue;
            }
            if (seenCaseFolded.add(caseFoldKey(alias))) {
                normalized.add(alias);
            }
        }

        if (normalized.size() > MAX_ALIASES) {
            errors.add(new ErrorResponse.FieldValidationError(FIELD_NAME,
                    "At most " + MAX_ALIASES + " label hints are allowed (got " + normalized.size() + ")"));
        }

        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        return List.copyOf(normalized);
    }

    /**
     * Strip invisible format characters, then trim + collapse internal
     * whitespace; null becomes the empty string. In that order because
     * removing a zero-width character can leave two spaces side by side (or
     * a leading one), which the collapse then folds away.
     */
    static String normalizeOne(String raw) {
        if (raw == null) {
            return "";
        }
        String visible = INVISIBLE_FORMAT_CHARACTER.matcher(raw).replaceAll("");
        return WHITESPACE_RUN.matcher(visible).replaceAll(" ").strip();
    }

    /**
     * The case-insensitive identity hints are de-duplicated by. It must be
     * the same fold as the {@code lower(alias_text)} in V40's
     * {@code uq_mfla_field_scope_alias}: a pair this class keeps as
     * "different" but the index sees as "equal" fails the whole save on the
     * unique index - a generic 409 instead of the silent keep-the-first
     * de-dup. Postgres' lower() (libc locale provider, as on every database
     * this runs against) lowercases one character at a time through the C
     * library's towlower, i.e. Unicode's simple case mapping, which is
     * exactly {@link Character#toLowerCase(int)} per code point. NOT
     * {@link String#toLowerCase}: that applies the full, context-sensitive
     * mapping, under which the Turkish capital "İ" (U+0130) becomes "i" plus
     * a combining dot and a word-final Greek "Σ" becomes "ς" - where lower()
     * gives plain "i" and "σ" - so "İD"/"id" or "ΟΔΟΣ"/"οδοσ" would slip
     * through as two hints and collide in the database.
     */
    static String caseFoldKey(String alias) {
        return alias.codePoints()
                .map(Character::toLowerCase)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static String problemWith(String alias) {
        if (CONTROL_CHARACTER.matcher(alias).find()) {
            return "must not contain control characters";
        }
        // Code points, not String#length() UTF-16 units, so the limits
        // match Postgres' char_length/VARCHAR(100) exactly.
        int length = alias.codePointCount(0, alias.length());
        if (length < MIN_LENGTH) {
            return "must be at least " + MIN_LENGTH + " characters long";
        }
        if (length > MAX_LENGTH) {
            return "must be at most " + MAX_LENGTH + " characters long";
        }
        if (alias.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return "must contain at least one letter or digit";
        }
        return null;
    }

    /**
     * The rejected entry as quoted back in its error message: control
     * characters shown as U+FFFD so the message stays printable, and long
     * entries cut on a code-point boundary - cutting a UTF-16 surrogate pair
     * in half would leave a lone surrogate that can't be encoded as UTF-8
     * when the error response is written.
     */
    private static String shown(String alias) {
        String printable = CONTROL_CHARACTER.matcher(alias).replaceAll("�");
        if (printable.codePointCount(0, printable.length()) <= 40) {
            return printable;
        }
        return printable.substring(0, printable.offsetByCodePoints(0, 37)) + "...";
    }
}
