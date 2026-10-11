package com.oak.dms.importexport.export;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hands out the names of a bulk export's ZIP entries: each document under
 * its original filename, flattened to a plain name (no folders, no
 * control characters), and numbered when taken - {@code report.pdf},
 * {@code report (2).pdf}, {@code report (3).pdf}. Names compare
 * case-insensitively, as on Windows and macOS, so {@code Report.pdf} and
 * {@code report.pdf} never land on top of each other when unzipped.
 * Not thread-safe; one instance per ZIP.
 */
public class ZipEntryNames {

    static final String FALLBACK_NAME = "document";
    /** Keeps a name well inside common filesystem limits (255 bytes) after numbering. */
    static final int MAX_LENGTH = 200;

    private final Set<String> taken = new HashSet<>();

    /** Takes {@code name} as-is for a fixed entry (the metadata files), so no document is given it. */
    public void reserve(String name) {
        taken.add(key(name));
    }

    /** A free entry name for a file called {@code desired}; it is taken from now on. */
    public String next(String desired) {
        String name = sanitise(desired);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        String candidate = name;
        for (int n = 2; !taken.add(key(candidate)); n++) {
            candidate = base + " (" + n + ")" + extension;
        }
        return candidate;
    }

    static String sanitise(String desired) {
        if (desired == null) {
            return FALLBACK_NAME;
        }
        // Keep only the last path segment - an entry must never point outside the ZIP's root.
        String name = desired.substring(Math.max(desired.lastIndexOf('/'), desired.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder(name.length());
        name.codePoints()
                .filter(c -> !Character.isISOControl(c))
                .forEach(clean::appendCodePoint);
        String result = clean.toString().strip();
        // Leading dots would hide the file, or make it "." / "..".
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        if (result.length() > MAX_LENGTH) {
            int dot = result.lastIndexOf('.');
            String extension = dot > 0 && result.length() - dot <= 16 ? result.substring(dot) : "";
            result = result.substring(0, MAX_LENGTH - extension.length()) + extension;
        }
        return result.isBlank() ? FALLBACK_NAME : result;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
