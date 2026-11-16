package com.oak.dms.search.query;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Did you mean": proposes a corrected query from words that appear in documents the caller is allowed to see. The
 * vocabulary passed in is built only from <em>verified</em> results (title and file name of documents that passed the
 * PostgreSQL visibility check), so a suggestion can never leak a word that exists only in a restricted or hidden document.
 * Pure string logic: no engine, no database.
 */
public final class DidYouMean {

    private static final int MAX_DISTANCE = 2;
    private static final int MIN_WORD = 3;

    private DidYouMean() {
    }

    /** A corrected copy of {@code query}, or null when nothing in it can be improved from {@code vocabulary}. */
    public static String suggest(String query, List<String> vocabulary) {
        Set<String> words = new TreeSet<>();
        for (String text : vocabulary) {
            for (String w : words(text)) {
                words.add(w);
            }
        }
        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (String token : query.strip().split("\\s+")) {
            String folded = fold(token);
            if (folded.length() < MIN_WORD || words.contains(folded)) {
                out.add(token);
                continue;
            }
            String best = null;
            int bestDistance = MAX_DISTANCE + 1;
            for (String w : words) {
                if (Math.abs(w.length() - folded.length()) > MAX_DISTANCE) {
                    continue;
                }
                int d = distance(folded, w);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = w;
                }
            }
            if (best != null && bestDistance <= MAX_DISTANCE && bestDistance < folded.length() / 2 + 1) {
                out.add(best);
                changed = true;
            } else {
                out.add(token);
            }
        }
        return changed ? String.join(" ", out) : null;
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String w : fold(text).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= MIN_WORD) {
                out.add(w);
            }
        }
        return out;
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
