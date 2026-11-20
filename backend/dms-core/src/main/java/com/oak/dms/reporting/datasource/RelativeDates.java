package com.oak.dms.reporting.datasource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a relative date expression to absolute days. Resolved when a report RUNS, in the report's own time zone (the caller
 * supplies "today" for that zone), so a saved template keeps the expression and a scheduled report moves with the calendar.
 * <p>
 * Expressions: {@code today}, {@code last_N_days} (the N days ending today, today included),
 * {@code this_week|last_week} (Monday to Sunday), {@code this_month|last_month}, {@code this_quarter|last_quarter}
 * (calendar quarters) and {@code this_year|last_year}.
 */
public final class RelativeDates {

    public static final int MAX_DAYS = 3650;

    /** What the builder offers; "last N days" takes any N from 1 to {@link #MAX_DAYS}. */
    public static final java.util.List<String> OPTIONS = java.util.List.of("today", "last_7_days", "last_30_days",
            "last_90_days", "this_week", "last_week", "this_month", "last_month", "this_quarter", "last_quarter",
            "this_year", "last_year");
    private static final Pattern LAST_DAYS = Pattern.compile("last_(\\d{1,4})_days");

    private RelativeDates() {
    }

    /** @param fromInclusive first day; @param toExclusive the day after the last day */
    public record Range(LocalDate fromInclusive, LocalDate toExclusive) {

        public LocalDate lastDay() {
            return toExclusive.minusDays(1);
        }

        /** "2024-03-01 to 2024-03-31", or one date when it is a single day. */
        public String describe() {
            return fromInclusive.equals(lastDay()) ? fromInclusive.toString() : fromInclusive + " to " + lastDay();
        }
    }

    public static Optional<Range> resolve(String expression, LocalDate today) {
        if (expression == null) {
            return Optional.empty();
        }
        String e = expression.strip().toLowerCase(java.util.Locale.ROOT);
        Matcher days = LAST_DAYS.matcher(e);
        if (days.matches()) {
            int n = Integer.parseInt(days.group(1));
            return n < 1 || n > MAX_DAYS ? Optional.empty()
                    : Optional.of(new Range(today.minusDays(n - 1L), today.plusDays(1)));
        }
        return switch (e) {
            case "today" -> Optional.of(new Range(today, today.plusDays(1)));
            case "this_week" -> Optional.of(week(today));
            case "last_week" -> Optional.of(week(today.minusWeeks(1)));
            case "this_month" -> Optional.of(month(today));
            case "last_month" -> Optional.of(month(today.minusMonths(1)));
            case "this_quarter" -> Optional.of(quarter(today));
            case "last_quarter" -> Optional.of(quarter(today.minusMonths(3)));
            case "this_year" -> Optional.of(year(today));
            case "last_year" -> Optional.of(year(today.minusYears(1)));
            default -> Optional.empty();
        };
    }

    private static Range week(LocalDate day) {
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new Range(monday, monday.plusWeeks(1));
    }

    private static Range month(LocalDate day) {
        LocalDate first = day.withDayOfMonth(1);
        return new Range(first, first.plusMonths(1));
    }

    private static Range quarter(LocalDate day) {
        LocalDate first = day.withMonth(((day.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
        return new Range(first, first.plusMonths(3));
    }

    private static Range year(LocalDate day) {
        LocalDate first = day.withDayOfYear(1);
        return new Range(first, first.plusYears(1));
    }
}
