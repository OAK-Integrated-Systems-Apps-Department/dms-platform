/**
 * Client-side mirror of dms-core's OCR label-hint rules ("label aliases" —
 * see V40__create_metadata_field_label_aliases.sql and the REST contract on
 * MetadataFieldResponse / the document-type `.../label-aliases` endpoints).
 * dms-core stays authoritative — it re-normalizes and re-validates every
 * list it receives — but mirroring the rules here lets both editing
 * surfaces (the field's own global "Label hints" and a document type's
 * "Hints for this type") reject a bad entry the moment it's typed, instead
 * of only after a round-trip that comes back as a generic "Validation
 * failed" 400.
 *
 * The list is ORDERED: position is match priority (ocr-worker tries each
 * hint in turn and stops at the first one it finds on the page), which is
 * why de-duplication keeps the FIRST occurrence rather than the last.
 */

export const LABEL_HINT_MIN_LENGTH = 2
export const LABEL_HINT_MAX_LENGTH = 100
/** Per (field, scope) — i.e. 20 global hints, plus up to 20 more per document type. */
export const MAX_LABEL_HINTS = 20

// Every character class below copies dms-core's LabelAliasNormalizer exactly
// rather than approximating it with JS's nearest built-in, because a mirror
// that accepts something the server refuses brings back exactly the opaque
// "Validation failed" this file exists to avoid (the per-hint reason in the
// 400's fieldErrors isn't surfaced by apiFetch), and one that refuses
// something the server accepts blocks a legitimate hint outright.
//
// Letters of any script plus DECIMAL digits (\p{Nd}) — Java's
// Character.isLetterOrDigit, which the server uses. Not \p{N}: that also
// takes circled/superscript/fraction digits (①, ², ½) and Roman numerals (Ⅻ),
// which isLetterOrDigit rejects, so "①②" would pass here and 400 there.
// Unicode-aware on purpose, so "Número" or "請求書番号" pass on both sides.
const HAS_LETTER_OR_DIGIT = /[\p{L}\p{Nd}]/u

// The server collapses Unicode White_Space runs (Java's \s under
// UNICODE_CHARACTER_CLASS) — which, unlike JS's \s, includes NEL (U+0085)
// and excludes the BOM/zero-width no-break space (U+FEFF). Its String#strip
// then removes, beyond the plain spaces the collapse leaves, only the
// information-separator controls U+001C–U+001F (Java whitespace, but not
// White_Space); JS's trim() would differ on both U+FEFF and those.
const WHITESPACE_RUN = /\p{White_Space}+/gu
const EDGE_WHITESPACE = /^[ \u001C-\u001F]+|[ \u001C-\u001F]+$/g

// Invisible format characters (category Cf: zero-width space, soft hyphen,
// BOM, bidi marks, ...) are STRIPPED before the collapse, same as the
// server's INVISIBLE_FORMAT_CHARACTER — they arrive via copy-paste, OCR text
// never carries them, and a hint keeping one would look right yet never
// match. ZWNJ/ZWJ (U+200C/U+200D) are kept: they're part of correct spelling
// in Sinhala, Persian and the Indic scripts.
const INVISIBLE_FORMAT_CHARACTER = /(?![\u200C\u200D])\p{Cf}/gu
// Control characters left after the collapse (NUL, ESC, DEL, C1, ...) are
// REJECTED, not stripped — mirrors the server's CONTROL_CHARACTER check.
const CONTROL_CHARACTER = /\p{Cc}/u

/**
 * Strips invisible format characters, then trims and collapses every
 * internal whitespace run to a single space — "  PO \t Number " → "PO Number".
 * In that order for the same reason as the server: removing a zero-width
 * character can leave two spaces side by side, which the collapse folds away.
 */
export function normalizeLabelHint(raw: string): string {
  return raw.replace(INVISIBLE_FORMAT_CHARACTER, "").replace(WHITESPACE_RUN, " ").replace(EDGE_WHITESPACE, "")
}

/**
 * The case-insensitive identity duplicates are detected by — the same fold as
 * the server's caseFoldKey and Postgres's lower() in the unique index: one
 * code point at a time (Unicode SIMPLE case mapping), never the whole string.
 * JS's toLowerCase applies the full, context-sensitive mapping, under which a
 * word-final "Σ" becomes "ς"; lowercasing each code point on its own avoids
 * that. The one code point whose full mapping is still multi-character on its
 * own is the Turkish capital "İ" (U+0130 → "i" + combining dot), whose simple
 * mapping is plain "i".
 */
export function labelHintCaseFoldKey(hint: string): string {
  let key = ""
  for (const ch of hint) {
    key += ch === "\u0130" ? "i" : ch.toLowerCase()
  }
  return key
}

/**
 * Length in Unicode code points, as the server counts it (and as Postgres's
 * char_length/VARCHAR(100) do) — not `.length`, which counts UTF-16 units
 * and so makes a single astral character such as "𠮷" or "𝐀" look 2 long.
 */
function codePointLength(text: string): number {
  return [...text].length
}

/**
 * Why one (already-normalized) hint would be rejected by dms-core, or `null`
 * if it's acceptable. Callers pass the output of `normalizeLabelHint` — the
 * length limits apply after normalization, same as on the server.
 */
export function labelHintError(hint: string): string | null {
  if (CONTROL_CHARACTER.test(hint)) {
    return "Hints must not contain control characters."
  }
  const length = codePointLength(hint)
  if (length < LABEL_HINT_MIN_LENGTH) {
    return `Hints must be at least ${LABEL_HINT_MIN_LENGTH} characters.`
  }
  if (length > LABEL_HINT_MAX_LENGTH) {
    return `Hints must be ${LABEL_HINT_MAX_LENGTH} characters or fewer.`
  }
  if (!HAS_LETTER_OR_DIGIT.test(hint)) {
    return "Hints must contain at least one letter or digit."
  }
  return null
}

/**
 * Normalizes a whole hint list the way dms-core will before storing it:
 * each entry trimmed/whitespace-collapsed, blank entries dropped, and
 * case-insensitive duplicates removed keeping the FIRST occurrence (so
 * "PO Number", "po number" keeps "PO Number" in its original, higher-priority
 * slot). De-duplication is silent on the server too — it's never an error —
 * so this never reports it; validity (length, letter/digit, count) is a
 * separate question for `labelHintsError`.
 */
export function normalizeLabelHints(list: readonly string[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const raw of list) {
    const hint = normalizeLabelHint(raw)
    if (!hint) continue
    const key = labelHintCaseFoldKey(hint)
    if (seen.has(key)) continue
    seen.add(key)
    result.push(hint)
  }
  return result
}

/**
 * The first reason dms-core would reject this list with a 400 (reported
 * there as a `labelAliases` field error), or `null` if it would be accepted.
 * Checks the list AFTER `normalizeLabelHints`, so duplicates that are about
 * to be merged away don't count toward the 20-hint limit.
 */
export function labelHintsError(list: readonly string[]): string | null {
  const normalized = normalizeLabelHints(list)
  for (const hint of normalized) {
    const error = labelHintError(hint)
    if (error) return `"${hint}": ${error}`
  }
  if (normalized.length > MAX_LABEL_HINTS) {
    return `A field can have at most ${MAX_LABEL_HINTS} hints here.`
  }
  return null
}
