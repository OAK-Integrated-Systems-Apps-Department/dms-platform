package com.oak.dms.reporting.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * When a schedule fires next. Pure: no clock, no database - the caller passes "after" - so every rule can be tested exactly.
 * <ul>
 *   <li><b>hourly</b>: every hour at the minute of the time of day. <b>daily</b>: every day. <b>weekly</b>: the given ISO days (Monday = 1). <b>monthly</b>: the given day of the month, or
 *       {@code -1} for the last; a day a short month does not have (31 in April) runs on that month's last day.</li>
 *   <li>The time of day is wall-clock time in the schedule's own zone, so it stays at 08:00 through a clock change.</li>
 *   <li><b>Spring forward</b>: a time that does not exist (02:30 on the night the clocks jump 02:00 to 03:00) runs at the first
 *       instant that does exist after it - 03:00. <b>Fall back</b>: a time that happens twice runs once, at its first occurrence.</li>
 *   <li>{@code startDate} is the first day it may fire; after {@code endDate} there is no next run (the schedule has ended).</li>
 * </ul>
 */
public final class NextRunCalculator {

    /** No pattern here needs more than a year to recur (31 February-style gaps are folded to the last day); this is a guard. */
    private static final int MAX_DAYS_AHEAD = 800;

    private NextRunCalculator() {
    }

    /**
     * @param frequency  hourly | daily | weekly | monthly
     * @param daysOfWeek ISO 1..7, for weekly
     * @param dayOfMonth 1..31 or -1 (last), for monthly
     */
    public record Spec(String frequency, Collection<Integer> daysOfWeek, Integer dayOfMonth, LocalTime timeOfDay, ZoneId zone,
                       LocalDate startDate, LocalDate endDate) {
    }

    /** The first firing strictly after {@code after}, or empty when the schedule has no further runs. */
    public static Optional<Instant> next(Spec spec, Instant after) {
        if ("hourly".equals(spec.frequency())) {
            return nextHourly(spec, after);
        }
        Set<Integer> weekdays = spec.daysOfWeek() == null ? Set.of() : spec.daysOfWeek().stream().collect(Collectors.toSet());
        LocalDate day = after.atZone(spec.zone()).toLocalDate();
        if (spec.startDate() != null && spec.startDate().isAfter(day)) {
            day = spec.startDate();
        }
        for (int i = 0; i < MAX_DAYS_AHEAD; i++, day = day.plusDays(1)) {
            if (spec.endDate() != null && day.isAfter(spec.endDate())) {
                return Optional.empty();
            }
            if (!matches(spec, weekdays, day)) {
                continue;
            }
            Instant fires = instantOf(day, spec.timeOfDay(), spec.zone());
            if (fires.isAfter(after)) {
                return Optional.of(fires);
            }
        }
        return Optional.empty();
    }

    /**
     * Every hour at the minute of {@code timeOfDay} (its hour is ignored), counted in real elapsed hours so a clock change never
     * skips or doubles a run. {@code startDate} / {@code endDate} bound the local dates it may fire on.
     */
    private static Optional<Instant> nextHourly(Spec spec, Instant after) {
        int minute = spec.timeOfDay().getMinute();
        ZonedDateTime candidate = after.atZone(spec.zone()).truncatedTo(java.time.temporal.ChronoUnit.HOURS).withMinute(minute);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = candidate.plusHours(1);
        }
        if (spec.startDate() != null && candidate.toLocalDate().isBefore(spec.startDate())) {
            candidate = spec.startDate().atStartOfDay(spec.zone()).withMinute(minute);
        }
        if (spec.endDate() != null && candidate.toLocalDate().isAfter(spec.endDate())) {
            return Optional.empty();
        }
        return Optional.of(candidate.toInstant());
    }

    private static boolean matches(Spec spec, Set<Integer> weekdays, LocalDate day) {
        return switch (spec.frequency()) {
            case "daily" -> true;
            case "weekly" -> weekdays.contains(day.getDayOfWeek().getValue());
            case "monthly" -> {
                int last = YearMonth.from(day).lengthOfMonth();
                Integer wanted = spec.dayOfMonth();
                int target = wanted == null || wanted == -1 ? last : Math.min(wanted, last);
                yield day.getDayOfMonth() == target;
            }
            default -> throw new IllegalArgumentException("Unknown frequency: " + spec.frequency());
        };
    }

    /** The instant a wall-clock time means in a zone, with the two clock-change rules above. */
    static Instant instantOf(LocalDate day, LocalTime time, ZoneId zone) {
        LocalDateTime local = LocalDateTime.of(day, time);
        ZoneOffsetTransition transition = zone.getRules().getTransition(local);
        if (transition != null && transition.isGap()) {
            return transition.getInstant(); // does not exist: the first moment that does
        }
        // An overlap resolves to the earlier offset - the first time the clock shows it - which is what ofLocal does for null.
        return ZonedDateTime.ofLocal(local, zone, null).toInstant();
    }
}
