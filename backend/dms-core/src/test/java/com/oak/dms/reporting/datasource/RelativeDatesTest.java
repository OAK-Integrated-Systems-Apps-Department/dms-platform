package com.oak.dms.reporting.datasource;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RelativeDatesTest {

    // Sunday 10 March 2024: the last day of an ISO week, the month's 10th, in Q1.
    private static final LocalDate TODAY = LocalDate.of(2024, 3, 10);

    private static RelativeDates.Range range(String expression) {
        return RelativeDates.resolve(expression, TODAY).orElseThrow();
    }

    @Test
    void today_andTheLastNDaysEndingToday() {
        assertThat(range("today")).isEqualTo(new RelativeDates.Range(TODAY, TODAY.plusDays(1)));
        assertThat(range("last_7_days").fromInclusive()).isEqualTo(LocalDate.of(2024, 3, 4));
        assertThat(range("last_7_days").lastDay()).isEqualTo(TODAY);
        assertThat(range("last_1_days").describe()).isEqualTo("2024-03-10");
    }

    @Test
    void weeksRunMondayToSunday() {
        assertThat(range("this_week").fromInclusive()).isEqualTo(LocalDate.of(2024, 3, 4));
        assertThat(range("this_week").lastDay()).isEqualTo(LocalDate.of(2024, 3, 10));
        assertThat(range("last_week").fromInclusive()).isEqualTo(LocalDate.of(2024, 2, 26));
        assertThat(range("last_week").lastDay()).isEqualTo(LocalDate.of(2024, 3, 3));
    }

    @Test
    void monthsQuartersAndYears() {
        assertThat(range("this_month").describe()).isEqualTo("2024-03-01 to 2024-03-31");
        assertThat(range("last_month").describe()).isEqualTo("2024-02-01 to 2024-02-29"); // leap year
        assertThat(range("this_quarter").describe()).isEqualTo("2024-01-01 to 2024-03-31");
        assertThat(range("last_quarter").describe()).isEqualTo("2023-10-01 to 2023-12-31");
        assertThat(range("this_year").describe()).isEqualTo("2024-01-01 to 2024-12-31");
        assertThat(range("last_year").describe()).isEqualTo("2023-01-01 to 2023-12-31");
    }

    @Test
    void lastMonthFromTheThirtyFirst_doesNotSkipAMonth() {
        assertThat(RelativeDates.resolve("last_month", LocalDate.of(2024, 3, 31)).orElseThrow().describe())
                .isEqualTo("2024-02-01 to 2024-02-29");
    }

    @Test
    void lastQuarterFromTheFirstQuarter_isTheLastQuarterOfThePreviousYear() {
        assertThat(RelativeDates.resolve("last_quarter", LocalDate.of(2024, 1, 1)).orElseThrow().describe())
                .isEqualTo("2023-10-01 to 2023-12-31");
        assertThat(RelativeDates.resolve("this_quarter", LocalDate.of(2024, 12, 31)).orElseThrow().describe())
                .isEqualTo("2024-10-01 to 2024-12-31");
    }

    @Test
    void expressionsAreCaseAndWhitespaceTolerant_andAnythingElseIsEmpty() {
        assertThat(RelativeDates.resolve("  This_Month ", TODAY)).isPresent();
        assertThat(RelativeDates.resolve("last_0_days", TODAY)).isEmpty();
        assertThat(RelativeDates.resolve("last_99999_days", TODAY)).isEmpty();
        assertThat(RelativeDates.resolve("tomorrow", TODAY)).isEmpty();
        assertThat(RelativeDates.resolve(null, TODAY)).isEmpty();
    }
}
