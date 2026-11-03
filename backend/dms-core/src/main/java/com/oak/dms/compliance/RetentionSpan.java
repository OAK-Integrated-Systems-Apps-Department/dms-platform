package com.oak.dms.compliance;

import java.time.OffsetDateTime;
import java.util.Set;

/** A length of time as an administrator enters it: an amount of days, months or years. */
public record RetentionSpan(int amount, String unit) {

    public static final String DAYS = "days";
    public static final String MONTHS = "months";
    public static final String YEARS = "years";
    public static final Set<String> UNITS = Set.of(DAYS, MONTHS, YEARS);

    /** {@code from} minus this span (the cutoff before which something counts as old). */
    public OffsetDateTime subtractFrom(OffsetDateTime from) {
        return switch (unit) {
            case DAYS -> from.minusDays(amount);
            case MONTHS -> from.minusMonths(amount);
            case YEARS -> from.minusYears(amount);
            default -> throw new IllegalStateException("Unknown retention unit: " + unit);
        };
    }

    /** {@code from} plus this span (calendar arithmetic: one month from 31 Jan is the last day of February). */
    public OffsetDateTime addTo(OffsetDateTime from) {
        return switch (unit) {
            case DAYS -> from.plusDays(amount);
            case MONTHS -> from.plusMonths(amount);
            case YEARS -> from.plusYears(amount);
            default -> throw new IllegalStateException("Unknown retention unit: " + unit);
        };
    }
}
