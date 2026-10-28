package com.oak.dms.comment.helper;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads @mentions out of a comment body (A5). The autocomplete inserts
 * {@code @[Display Name](user:<uuid>)}; the name is cosmetic - only the id
 * counts, so a rename never breaks a mention. Anything that isn't a
 * well-formed token is plain text.
 */
public final class MentionParser {

    private static final Pattern TOKEN = Pattern.compile(
            "@\\[([^\\]\\n]{1,150})]\\(user:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\)");

    private MentionParser() {
    }

    /** Distinct mentioned user ids, in order of first appearance. */
    public static Set<UUID> mentionedUserIds(String body) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (body == null) {
            return ids;
        }
        Matcher m = TOKEN.matcher(body);
        while (m.find()) {
            ids.add(UUID.fromString(m.group(2)));
        }
        return ids;
    }

    /** The body as people read it in a mail: each token becomes "@Display Name". */
    public static String toPlainText(String body) {
        return body == null ? "" : TOKEN.matcher(body).replaceAll(m -> Matcher.quoteReplacement("@" + m.group(1)));
    }
}
