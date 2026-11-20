package com.oak.dms.reporting.datasource;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderAccessView;
import com.oak.dms.reporting.ReportProperties;
import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.ReportFieldCatalog;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ReportDefinition.Sort;
import com.oak.dms.reporting.entity.ReportFieldSetting;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsDefaults;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What a definition gets from the organisation's defaults - and that a default pointing at a switched-off field is skipped, not fatal. */
class DocumentReportDataSourceDefaultsTest {

    private final UUID tenant = UUID.randomUUID();
    private final EffectiveReportSettings base = new ReportSettingsDefaults(new ReportProperties()).resolve(null);

    private ReportContext context(List<String> defaultColumns, List<Map<String, Object>> defaultSort) {
        // records are immutable: rebuild the settings with the defaults under test via the tenant row semantics
        EffectiveReportSettings s = new EffectiveReportSettings(base.enabled(), base.schedulingEnabled(), base.allowedFormats(),
                base.defaultFormat(), base.csvDelimiter(), base.csvIncludeBom(), base.dateFormat(), base.numberFormat(),
                base.defaultTimeZone(), base.defaultLocale(), base.headerText(), base.footerText(), base.confidentialityNotice(),
                base.showLogo(), base.showFilterSummary(), base.showGeneratedBy(), base.pdfPageSize(), base.pdfOrientation(),
                base.maxRows(), base.maxPdfRows(), base.overLimitBehaviour(), base.maxConcurrentRuns(), base.maxQueuedPerUser(),
                base.fileRetentionDays(), base.maxTemplatesPerUser(), base.maxSchedulesPerUser(), base.maxSchedulesPerTenant(),
                base.minScheduleInterval(), base.maxRecipientsPerSchedule(), base.allowedDeliveryChannels(),
                base.deliveryModeDefault(), base.allowAttachments(), base.maxAttachmentMb(), base.allowExternalRecipients(),
                base.externalDomainAllowlist(), base.failureNotifyAdmins(), base.failureAdminUserIds(),
                base.pauseAfterConsecutiveFailures(), base.catchUpWindowHours(), base.skipEmptyScheduledReports(),
                base.includeInactiveByDefault(), defaultColumns, defaultSort);
        return new ReportContext(tenant, UUID.randomUUID(), new DocumentAccessView(
                new FolderAccessView(List.of(), Map.of(), true), Map.of(), true, false), s, ZoneId.of("UTC"), Instant.now());
    }

    private static ReportDefinition empty() {
        return new ReportDefinition("documents", List.of(), List.of(), List.of(), null, null, null);
    }

    private static EffectiveCatalogue catalogue(ReportFieldSetting... settings) {
        return ReportFieldCatalog.merge(List.of(), List.of(settings));
    }

    @Test
    void theOrganisationsDefaultColumnsAndSortFillWhatTheUserLeftOut() {
        ReportContext ctx = context(List.of("title", "owner"), List.of(Map.of("field", "createdAt", "direction", "desc")));

        ReportDefinition d = DocumentReportDataSource.withDefaults(empty(), ctx, catalogue());

        assertThat(d.columns()).containsExactly("title", "owner");
        assertThat(d.sort()).containsExactly(new Sort("createdAt", "desc"));
    }

    @Test
    void whatTheUserChoseIsNeverReplaced_evenIfItIsNotAvailable_soTheBuilderCanRefuseIt() {
        ReportContext ctx = context(List.of("title"), List.of(Map.of("field", "createdAt", "direction", "desc")));
        ReportDefinition chosen = new ReportDefinition("documents", List.of(), List.of("nonsense"),
                List.of(new Sort("title", "asc")), null, null, null);

        ReportDefinition d = DocumentReportDataSource.withDefaults(chosen, ctx, catalogue());

        assertThat(d.columns()).containsExactly("nonsense");
        assertThat(d.sort()).containsExactly(new Sort("title", "asc"));
    }

    @Test
    void aDefaultThatPointsAtAFieldTheAdministratorSwitchedOffIsSkipped() {
        ReportFieldSetting off = ReportFieldSetting.builder().tenantId(tenant).fieldKey("owner").enabled(false).build();
        ReportFieldSetting unsortable = ReportFieldSetting.builder().tenantId(tenant).fieldKey("title").sortable(false).build();
        ReportContext ctx = context(List.of("title", "owner"), List.of(Map.of("field", "title", "direction", "asc"),
                Map.of("field", "createdAt", "direction", "desc")));

        ReportDefinition d = DocumentReportDataSource.withDefaults(empty(), ctx, catalogue(off, unsortable));

        assertThat(d.columns()).containsExactly("title");
        assertThat(d.sort()).containsExactly(new Sort("createdAt", "desc"));
    }

    @Test
    void withNoDefaultsAtAllItStillHasAColumn_soANewBuilderPreviewsSomething() {
        ReportContext ctx = context(List.of(), List.of());

        ReportDefinition d = DocumentReportDataSource.withDefaults(empty(), ctx, catalogue());

        assertThat(d.columns()).containsExactly("title"); // the built-in default-selected field
        assertThat(d.sort()).isEmpty();
    }

    @Test
    void ifEvenTheDefaultFieldIsOff_itFallsBackToTheFirstAvailableColumn() {
        ReportFieldSetting titleOff = ReportFieldSetting.builder().tenantId(tenant).fieldKey("title").enabled(false).build();

        ReportDefinition d = DocumentReportDataSource.withDefaults(empty(), context(List.of(), List.of()), catalogue(titleOff));

        assertThat(d.columns()).hasSize(1).doesNotContain("title");
    }

    @Test
    void atMostThreeDefaultSortKeysAreUsed() {
        ReportContext ctx = context(List.of("title"), List.of(Map.of("field", "title"), Map.of("field", "status"),
                Map.of("field", "owner"), Map.of("field", "createdAt")));

        ReportDefinition d = DocumentReportDataSource.withDefaults(empty(), ctx, catalogue());

        assertThat(d.sort()).hasSize(3);
    }
}
