package com.oak.dms.dashboard;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The dashboard's periods, grouping, day series and comparison, and the caller's time zone. */
class DashboardServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    @Test
    void onlyTheOfferedPeriodsAreUsed_anythingElseIsTheDefault() {
        assertThat(DashboardService.period(7)).isEqualTo(7);
        assertThat(DashboardService.period(90)).isEqualTo(90);
        assertThat(DashboardService.period(365)).isEqualTo(DashboardService.DEFAULT_DAYS);
        assertThat(DashboardService.period(-1)).isEqualTo(DashboardService.DEFAULT_DAYS);
    }

    @Test
    void filesAreGroupedByKind_byTheirExtension() {
        assertThat(DashboardService.formatOf("PDF")).isEqualTo("pdf");
        assertThat(DashboardService.formatOf("docx")).isEqualTo("word");
        assertThat(DashboardService.formatOf("csv")).isEqualTo("sheet");
        assertThat(DashboardService.formatOf("pptx")).isEqualTo("slides");
        assertThat(DashboardService.formatOf("heic")).isEqualTo("image");
        assertThat(DashboardService.formatOf("zip")).isEqualTo("archive");
        assertThat(DashboardService.formatOf("msg")).isEqualTo("text");
        assertThat(DashboardService.formatOf("dwg")).isEqualTo("other");
        assertThat(DashboardService.formatOf(null)).isEqualTo("other");
    }

    @Test
    void theSeries_coversEveryDay_withARunningTotalAndThatDaysUsage() {
        LocalDate start = TODAY.minusDays(6);

        List<DashboardResponse.Day> days = DashboardService.series(start, TODAY, 10,
                Map.of(start, 2L, TODAY, 3L), Map.of(TODAY.minusDays(1), new long[]{5, 1, 0}));

        assertThat(days).hasSize(7);
        assertThat(days.get(0)).isEqualTo(new DashboardResponse.Day(start, 2, 12, 0, 0, 0));
        assertThat(days.get(5)).isEqualTo(new DashboardResponse.Day(TODAY.minusDays(1), 0, 12, 5, 1, 0));
        assertThat(days.get(6)).isEqualTo(new DashboardResponse.Day(TODAY, 3, 15, 0, 0, 0));
    }

    @Test
    void usage_splitsThisPeriodFromTheOneBefore() {
        LocalDate start = TODAY.minusDays(6);

        DashboardResponse.Usage usage = DashboardService.usage(Map.of(
                TODAY, new long[]{4, 2, 1},
                start, new long[]{1, 0, 0},
                start.minusDays(1), new long[]{3, 3, 3}), start);

        assertThat(usage).isEqualTo(new DashboardResponse.Usage(5, 2, 1, 3, 3, 3));
    }

    @Test
    void anUnknownOrMissingTimeZone_fallsBackToUtc() {
        assertThat(DashboardController.zone("Asia/Colombo")).isEqualTo(ZoneId.of("Asia/Colombo"));
        assertThat(DashboardController.zone("Not/AZone")).isEqualTo(ZoneOffset.UTC);
        assertThat(DashboardController.zone(" ")).isEqualTo(ZoneOffset.UTC);
        assertThat(DashboardController.zone(null)).isEqualTo(ZoneOffset.UTC);
    }
}
