package com.oak.dms.audit;

/**
 * The reason field's own capture rule (docs/audit_events_schema_spec.md):
 * free text, so it must have a max length and be sanitized before
 * storage. Whether it's mandatory is decided per action by
 * {@link AuditActionCatalog}, not here.
 */
final class AuditReasonSanitizer {

    /** Generous enough for a real justification, short enough to never bloat a row or carry a payload. */
    static final int MAX_LENGTH = 1000;

    private AuditReasonSanitizer() {
    }

    static String sanitize(String rawReason) {
        if (rawReason == null) {
            return null;
        }
        String trimmed = rawReason.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // Strip control characters (which would otherwise let a
            // caller smuggle terminal escape sequences or similar into a
            // field that gets displayed verbatim in an audit viewer),
            // keeping plain whitespace (space, tab, newline).
            if (c == '\t' || c == '\n' || c == '\r' || !Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        String result = cleaned.toString();
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) : result;
    }
}
