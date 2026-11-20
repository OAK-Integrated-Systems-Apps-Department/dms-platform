package com.oak.dms.reporting.catalog;

import java.util.List;

import static com.oak.dms.reporting.catalog.Operator.AFTER;
import static com.oak.dms.reporting.catalog.Operator.BEFORE;
import static com.oak.dms.reporting.catalog.Operator.BETWEEN;
import static com.oak.dms.reporting.catalog.Operator.CONTAINS;
import static com.oak.dms.reporting.catalog.Operator.EQ;
import static com.oak.dms.reporting.catalog.Operator.EQUALS;
import static com.oak.dms.reporting.catalog.Operator.GT;
import static com.oak.dms.reporting.catalog.Operator.GTE;
import static com.oak.dms.reporting.catalog.Operator.IS_ANY_OF;
import static com.oak.dms.reporting.catalog.Operator.IS_EMPTY;
import static com.oak.dms.reporting.catalog.Operator.IS_FALSE;
import static com.oak.dms.reporting.catalog.Operator.IS_NONE_OF;
import static com.oak.dms.reporting.catalog.Operator.IS_TRUE;
import static com.oak.dms.reporting.catalog.Operator.LT;
import static com.oak.dms.reporting.catalog.Operator.LTE;
import static com.oak.dms.reporting.catalog.Operator.NE;
import static com.oak.dms.reporting.catalog.Operator.ON;
import static com.oak.dms.reporting.catalog.Operator.RELATIVE;
import static com.oak.dms.reporting.catalog.Operator.STARTS_WITH;

/** What a field holds - decides its filter operators (brief 2.1) and how its cells are formatted. */
public enum FieldType {
    TEXT(List.of(CONTAINS, EQUALS, STARTS_WITH, IS_EMPTY)),
    /** One of a fixed list: status, document type, lifecycle state, a dropdown. */
    CHOICE(List.of(IS_ANY_OF, IS_NONE_OF, IS_EMPTY)),
    /** Points at a user, department or similar: filtered by id, shown by name. */
    REFERENCE(List.of(IS_ANY_OF, IS_NONE_OF, IS_EMPTY)),
    NUMBER(List.of(EQ, NE, LT, LTE, GT, GTE, BETWEEN)),
    DATE(List.of(ON, BEFORE, AFTER, BETWEEN, IS_EMPTY, RELATIVE)),
    BOOLEAN(List.of(IS_TRUE, IS_FALSE));

    private final List<Operator> operators;

    FieldType(List<Operator> operators) {
        this.operators = operators;
    }

    public List<Operator> operators() {
        return operators;
    }

    public boolean allows(Operator operator) {
        return operators.contains(operator);
    }
}
