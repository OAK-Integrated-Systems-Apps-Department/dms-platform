package com.oak.dms.reporting.catalog;

import java.util.Arrays;
import java.util.Optional;

/** A filter operator. {@link #key()} is the wire name used by the API and in saved definitions. */
public enum Operator {
    CONTAINS("contains", 1),
    EQUALS("equals", 1),
    STARTS_WITH("starts_with", 1),
    IS_EMPTY("is_empty", 0),
    IS_ANY_OF("is_any_of", -1),
    IS_NONE_OF("is_none_of", -1),
    EQ("eq", 1),
    NE("ne", 1),
    LT("lt", 1),
    LTE("lte", 1),
    GT("gt", 1),
    GTE("gte", 1),
    BETWEEN("between", 2),
    ON("on", 1),
    BEFORE("before", 1),
    AFTER("after", 1),
    /** today, last_N_days, this/last week|month|quarter|year - resolved when the report runs. */
    RELATIVE("relative", 1),
    IS_TRUE("is_true", 0),
    IS_FALSE("is_false", 0);

    private final String key;
    private final int arity;

    Operator(String key, int arity) {
        this.key = key;
        this.arity = arity;
    }

    public String key() {
        return key;
    }

    /** How many values it takes: 0, 1, 2, or -1 for "one or more". */
    public int arity() {
        return arity;
    }

    public static Optional<Operator> fromKey(String key) {
        return Arrays.stream(values()).filter(o -> o.key.equals(key)).findFirst();
    }
}
