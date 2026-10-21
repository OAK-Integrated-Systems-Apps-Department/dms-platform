package com.oak.dms.relationship.helper;

import java.util.UUID;

/**
 * The one ordering used for "lower id first": a symmetric pair is stored in it and documents are
 * locked in it. Postgres orders {@code uuid} bytewise unsigned, which is the order of the
 * lowercase canonical text - {@link UUID#compareTo} is signed and would disagree.
 */
public final class RelationshipKeys {

    private RelationshipKeys() {
    }

    public static int compare(UUID a, UUID b) {
        return a.toString().compareTo(b.toString());
    }

    public static UUID lower(UUID a, UUID b) {
        return compare(a, b) <= 0 ? a : b;
    }

    public static UUID higher(UUID a, UUID b) {
        return compare(a, b) <= 0 ? b : a;
    }
}
