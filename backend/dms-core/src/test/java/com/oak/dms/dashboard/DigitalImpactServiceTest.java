package com.oak.dms.dashboard;

import com.oak.dms.folder.FolderException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The Digital Impact maths and periods - transparent, and only as large as the assumptions allow. */
class DigitalImpactServiceTest {

    @Test
    void aPage_isTheConfiguredPapersShareOfASheet() {
        ImpactProperties defaults = new ImpactProperties();
        // A4 at 80 gsm is 0.06237 m2 x 80 g = 4.99 g a sheet; printed double-sided, 2.49 g a page.
        assertThat(defaults.gramsPerPage()).isCloseTo(2.4948, within(0.0001));

        defaults.setPagesPerSheet(1);
        assertThat(defaults.gramsPerPage()).isCloseTo(4.9896, within(0.0001));
    }

    @Test
    void pagesBecomeSheetsPaperCo2eAndWater_withTheConfiguredFactors() {
        ImpactProperties p = new ImpactProperties();
        p.setPagesPerSheet(1);
        p.setCo2eKgPerKgPaper(2.0);
        p.setWaterLitresPerKgPaper(100);

        DigitalImpactResponse.Totals totals = DigitalImpactService.totals(1000, p);

        assertThat(totals.pages()).isEqualTo(1000);
        assertThat(totals.sheets()).isEqualTo(1000);
        assertThat(totals.paperKg()).isCloseTo(4.9896, within(0.0001));
        assertThat(totals.co2eKg()).isCloseTo(9.9792, within(0.0001));
        assertThat(totals.waterLitres()).isCloseTo(498.96, within(0.001));
        assertThat(DigitalImpactService.totals(0, p).paperKg()).isZero();
    }

    @Test
    void theTrend_hasEveryDayOfThePeriod_withZeros() {
        LocalDate to = LocalDate.of(2026, 10, 6);
        DigitalImpactService.Period period = new DigitalImpactService.Period(to.minusDays(6), to);

        List<DigitalImpactResponse.Day> days = DigitalImpactService.daily(period, Map.of(to, 40L),
                new ImpactProperties());

        assertThat(days).hasSize(7);
        assertThat(days.get(0).pages()).isZero();
        assertThat(days.get(6).date()).isEqualTo(to);
        assertThat(days.get(6).pages()).isEqualTo(40);
        assertThat(days.get(6).paperKg()).isGreaterThan(0);
    }

    @Test
    void pagedFormats_areGroupedByKind() {
        assertThat(DigitalImpactService.kindOf("PDF")).isEqualTo("pdf");
        assertThat(DigitalImpactService.kindOf("docx")).isEqualTo("word");
        assertThat(DigitalImpactService.kindOf("odt")).isEqualTo("word");
        assertThat(DigitalImpactService.kindOf("pptx")).isEqualTo("slides");
        assertThat(DigitalImpactService.kindOf("tiff")).isEqualTo("scans");
        assertThat(DigitalImpactService.kindOf("xlsx")).isEqualTo("other");
        assertThat(DigitalImpactService.kindOf(null)).isEqualTo("other");
    }

    @Test
    void aCustomPeriod_mustBeComplete_inOrder_notInTheFuture_andAtMostAYear() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThat(DigitalImpactService.period(ZoneOffset.UTC, 7, null, null).days()).isEqualTo(7);
        assertThat(DigitalImpactService.period(ZoneOffset.UTC, 5, null, null).days())
                .isEqualTo(DashboardService.DEFAULT_DAYS);
        assertThat(DigitalImpactService.period(ZoneOffset.UTC, 30, today.minusDays(9), today).days()).isEqualTo(10);

        assertThatThrownBy(() -> DigitalImpactService.period(ZoneOffset.UTC, 30, today, null))
                .isInstanceOf(FolderException.class);
        assertThatThrownBy(() -> DigitalImpactService.period(ZoneOffset.UTC, 30, today, today.minusDays(1)))
                .isInstanceOf(FolderException.class);
        assertThatThrownBy(() -> DigitalImpactService.period(ZoneOffset.UTC, 30, today, today.plusDays(1)))
                .isInstanceOf(FolderException.class);
        assertThatThrownBy(() -> DigitalImpactService.period(ZoneOffset.UTC, 30, today.minusDays(400), today))
                .isInstanceOf(FolderException.class);
    }
}
