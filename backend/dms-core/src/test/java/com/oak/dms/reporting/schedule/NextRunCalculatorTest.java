package com.oak.dms.reporting.schedule;

import com.oak.dms.reporting.schedule.NextRunCalculator.Spec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The schedule maths, exactly: every frequency, month lengths, start and end dates, and both clock-change rules. */
class NextRunCalculatorTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo"); // UTC+05:30, no clock changes
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private static Spec daily(String time, ZoneId zone) {
        return new Spec("daily", List.of(), null, LocalTime.parse(time), zone, null, null);
    }

    private static Spec weekly(String time, ZoneId zone, Integer... days) {
        return new Spec("weekly", List.of(days), null, LocalTime.parse(time), zone, null, null);
    }

    private static Spec monthly(int day, String time, ZoneId zone) {
        return new Spec("monthly", List.of(), day, LocalTime.parse(time), zone, null, null);
    }

    private static Instant next(Spec spec, String after) {
        Optional<Instant> next = NextRunCalculator.next(spec, Instant.parse(after));
        assertThat(next).as("a next run").isPresent();
        return next.get();
    }

    // ------------------------------------------------------------------ daily

    @Test
    void dailyFiresAtTheWallClockTimeInTheSchedulesZone() {
        // 08:00 in Colombo is 02:30 UTC. At 06:30 local the day's slot is still ahead.
        assertThat(next(daily("08:00", COLOMBO), "2026-01-10T01:00:00Z")).isEqualTo(Instant.parse("2026-01-10T02:30:00Z"));
        assertThat(next(daily("08:00", COLOMBO), "2026-01-10T03:00:00Z")).isEqualTo(Instant.parse("2026-01-11T02:30:00Z"));
    }

    @Test
    void theNextRunIsStrictlyAfterNow_soTheSlotJustFiredIsNotFiredAgain() {
        Spec spec = daily("08:00", COLOMBO);

        assertThat(next(spec, "2026-01-10T02:30:00Z")).isEqualTo(Instant.parse("2026-01-11T02:30:00Z"));
        assertThat(next(spec, "2026-01-10T02:29:59Z")).isEqualTo(Instant.parse("2026-01-10T02:30:00Z"));
    }

    // ------------------------------------------------------------------ weekly

    @Test
    void weeklyFiresOnTheChosenIsoDays() {
        Spec mondayAndThursday = weekly("09:00", COLOMBO, 1, 4);

        // 2026-01-13 is a Tuesday: the next of Mon/Thu is Thursday the 15th, then Monday the 19th.
        assertThat(next(mondayAndThursday, "2026-01-13T00:00:00Z")).isEqualTo(Instant.parse("2026-01-15T03:30:00Z"));
        assertThat(next(mondayAndThursday, "2026-01-15T03:30:00Z")).isEqualTo(Instant.parse("2026-01-19T03:30:00Z"));
    }

    @Test
    void sundayIsDaySeven() {
        // 2026-01-10 is a Saturday.
        assertThat(next(weekly("09:00", COLOMBO, 7), "2026-01-10T00:00:00Z")).isEqualTo(Instant.parse("2026-01-11T03:30:00Z"));
    }

    @Test
    void aWeeklyReportKeepsItsWallClockTimeAcrossAClockChange() {
        Spec sunday8am = weekly("08:00", NEW_YORK, 7);

        // Before the clocks go forward (2026-03-08) 08:00 is 13:00Z; after, 12:00Z.
        assertThat(next(sunday8am, "2026-03-01T14:00:00Z")).isEqualTo(Instant.parse("2026-03-08T12:00:00Z"));
        assertThat(next(sunday8am, "2026-02-28T00:00:00Z")).isEqualTo(Instant.parse("2026-03-01T13:00:00Z"));
    }

    // ------------------------------------------------------------------ monthly

    @Test
    void monthlyOnTheFifteenth() {
        assertThat(next(monthly(15, "08:00", COLOMBO), "2026-01-15T05:00:00Z")).isEqualTo(Instant.parse("2026-02-15T02:30:00Z"));
    }

    @Test
    void aDayThatDoesNotExistInAShortMonthRunsOnThatMonthsLastDay() {
        Spec thirtyFirst = monthly(31, "08:00", COLOMBO);

        assertThat(next(thirtyFirst, "2026-01-31T05:00:00Z")).as("February 2026").isEqualTo(Instant.parse("2026-02-28T02:30:00Z"));
        assertThat(next(thirtyFirst, "2028-01-31T05:00:00Z")).as("February 2028, a leap year").isEqualTo(Instant.parse("2028-02-29T02:30:00Z"));
        assertThat(next(thirtyFirst, "2026-03-31T05:00:00Z")).as("April has 30 days").isEqualTo(Instant.parse("2026-04-30T02:30:00Z"));
        assertThat(next(monthly(30, "08:00", COLOMBO), "2026-01-30T05:00:00Z")).as("30 in February").isEqualTo(Instant.parse("2026-02-28T02:30:00Z"));
    }

    @Test
    void minusOneMeansTheLastDayWhateverTheMonth() {
        Spec last = monthly(-1, "08:00", COLOMBO);

        assertThat(next(last, "2026-01-01T00:00:00Z")).isEqualTo(Instant.parse("2026-01-31T02:30:00Z"));
        assertThat(next(last, "2026-01-31T05:00:00Z")).isEqualTo(Instant.parse("2026-02-28T02:30:00Z"));
        assertThat(next(last, "2026-04-01T00:00:00Z")).isEqualTo(Instant.parse("2026-04-30T02:30:00Z"));
    }

    @Test
    void afterTheFirstOfThisMonthWasMissedTheNextIsNextMonth() {
        assertThat(next(monthly(1, "08:00", COLOMBO), "2026-01-01T05:00:00Z")).isEqualTo(Instant.parse("2026-02-01T02:30:00Z"));
    }

    // ------------------------------------------------------------------ start and end

    @Test
    void nothingFiresBeforeTheStartDate() {
        Spec spec = new Spec("daily", List.of(), null, LocalTime.of(8, 0), COLOMBO, LocalDate.parse("2026-03-01"), null);

        assertThat(next(spec, "2026-01-05T00:00:00Z")).isEqualTo(Instant.parse("2026-03-01T02:30:00Z"));
    }

    @Test
    void theEndDateIsInclusive_andAfterItThereIsNoNextRun() {
        Spec spec = new Spec("daily", List.of(), null, LocalTime.of(8, 0), COLOMBO, null, LocalDate.parse("2026-01-10"));

        assertThat(next(spec, "2026-01-09T10:00:00Z")).as("the last day itself").isEqualTo(Instant.parse("2026-01-10T02:30:00Z"));
        assertThat(NextRunCalculator.next(spec, Instant.parse("2026-01-10T02:30:00Z"))).as("after the last slot").isEmpty();
        assertThat(NextRunCalculator.next(spec, Instant.parse("2026-02-01T00:00:00Z"))).isEmpty();
    }

    @Test
    void aWeeklyScheduleWithAnEndDateBeforeItsNextDayHasNoNextRun() {
        Spec spec = new Spec("weekly", List.of(1), null, LocalTime.of(8, 0), COLOMBO, null, LocalDate.parse("2026-01-12"));

        // Monday 2026-01-12 is allowed; the Monday after is not.
        assertThat(next(spec, "2026-01-10T00:00:00Z")).isEqualTo(Instant.parse("2026-01-12T02:30:00Z"));
        assertThat(NextRunCalculator.next(spec, Instant.parse("2026-01-12T02:30:00Z"))).isEmpty();
    }

    // ------------------------------------------------------------------ clock changes

    @Test
    void springForward_aTimeThatDoesNotExistRunsAtTheNextInstantThatDoes() {
        // New York, 2026-03-08: at 02:00 EST the clocks jump to 03:00 EDT, so 02:30 never happens. 03:00 EDT is 07:00Z.
        Spec spec = daily("02:30", NEW_YORK);

        assertThat(next(spec, "2026-03-07T12:00:00Z")).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        // The day after is an ordinary 02:30 EDT = 06:30Z.
        assertThat(next(spec, "2026-03-08T07:00:00Z")).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
    }

    @Test
    void springForwardInLondon() {
        // 2026-03-29: 01:00 GMT jumps to 02:00 BST, so 01:30 does not exist; the first instant that does is 01:00Z.
        assertThat(next(daily("01:30", LONDON), "2026-03-28T12:00:00Z")).isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
    }

    @Test
    void fallBack_aTimeThatHappensTwiceRunsOnceAtItsFirstOccurrence() {
        // New York, 2026-11-01: 01:30 happens as EDT (05:30Z) and again as EST (06:30Z).
        Spec spec = daily("01:30", NEW_YORK);

        Instant first = next(spec, "2026-10-31T12:00:00Z");
        assertThat(first).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        // Asked again just after the first firing, the answer is the next DAY - not the second 01:30 of the same night.
        assertThat(next(spec, "2026-11-01T05:30:00Z")).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
        assertThat(next(spec, "2026-11-01T05:45:00Z")).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
    }

    @Test
    void fallBackInTheSouthernHemisphere() {
        // Sydney, 2026-04-05: 03:00 AEDT (UTC+11) falls back to 02:00 AEST (UTC+10); 02:30 happens twice, first at UTC+11.
        Spec spec = daily("02:30", SYDNEY);

        assertThat(next(spec, "2026-04-04T00:00:00Z")).isEqualTo(Instant.parse("2026-04-04T15:30:00Z"));
        assertThat(next(spec, "2026-04-04T15:30:00Z")).isEqualTo(Instant.parse("2026-04-05T16:30:00Z"));
    }

    @Test
    void aMonthlyScheduleLandingInAClockGapStillFiresThatDay() {
        // 2026-03-08 is the second Sunday of March in New York; a monthly slot on the 8th at 02:30 falls in the gap.
        assertThat(next(monthly(8, "02:30", NEW_YORK), "2026-02-08T12:00:00Z")).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
    }

    // ------------------------------------------------------------------ hourly

    private static Spec hourly(String time, ZoneId zone, java.time.LocalDate start, java.time.LocalDate end) {
        return new Spec("hourly", List.of(), null, LocalTime.parse(time), zone, start, end);
    }

    @Test
    void hourlyFiresEveryHourAtTheMinuteOfTheTime_theHourItselfIsIgnored() {
        Spec spec = hourly("08:15", ZoneId.of("UTC"), null, null);

        assertThat(next(spec, "2026-01-10T10:00:00Z")).isEqualTo(Instant.parse("2026-01-10T10:15:00Z"));
        assertThat(next(spec, "2026-01-10T10:15:00Z")).as("strictly after: the slot just fired is not fired again")
                .isEqualTo(Instant.parse("2026-01-10T11:15:00Z"));
        assertThat(next(spec, "2026-01-10T23:40:00Z")).isEqualTo(Instant.parse("2026-01-11T00:15:00Z"));
    }

    @Test
    void hourlyKeepsTheMinuteInAZoneWithAHalfHourOffset() {
        // Colombo is UTC+5:30: minute 00 local is minute 30 UTC.
        assertThat(next(hourly("00:00", COLOMBO, null, null), "2026-01-10T01:00:00Z")).isEqualTo(Instant.parse("2026-01-10T01:30:00Z"));
    }

    @Test
    void hourlyCountsRealHoursThroughAClockChange() {
        // New York springs forward on 2026-03-08 at 02:00 local (07:00 UTC): no hour is skipped or doubled in elapsed time.
        Spec spec = hourly("00:00", NEW_YORK, null, null);
        Instant first = next(spec, "2026-03-08T05:30:00Z");
        Instant second = next(spec, first.toString());
        Instant third = next(spec, second.toString());
        assertThat(java.time.Duration.between(first, second)).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(java.time.Duration.between(second, third)).isEqualTo(java.time.Duration.ofHours(1));
    }

    @Test
    void hourlyRespectsTheStartAndEndDates() {
        java.time.LocalDate start = java.time.LocalDate.parse("2026-02-01");
        java.time.LocalDate end = java.time.LocalDate.parse("2026-02-01");
        Spec spec = hourly("00:00", ZoneId.of("UTC"), start, end);

        assertThat(next(spec, "2026-01-10T10:20:00Z")).as("waits for the start date").isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(next(spec, "2026-02-01T22:10:00Z")).isEqualTo(Instant.parse("2026-02-01T23:00:00Z"));
        assertThat(NextRunCalculator.next(spec, Instant.parse("2026-02-01T23:10:00Z"))).as("the day after the end date is the end").isEmpty();
    }

    // ------------------------------------------------------------------ guards

    @Test
    void anUnknownFrequencyIsRefusedRatherThanGuessed() {
        Spec spec = new Spec("fortnightly", List.of(), null, LocalTime.of(8, 0), COLOMBO, null, null);

        assertThatThrownBy(() -> NextRunCalculator.next(spec, Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWeeklyScheduleWithNoDaysNeverRuns_insteadOfLoopingForever() {
        assertThat(NextRunCalculator.next(weekly("08:00", COLOMBO), Instant.parse("2026-01-01T00:00:00Z"))).isEmpty();
    }
}
