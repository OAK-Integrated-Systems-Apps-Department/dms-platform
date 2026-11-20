package com.oak.dms.reporting.settings;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.ReportProperties;
import com.oak.dms.reporting.dto.ReportSettingsUpdate;
import com.oak.dms.reporting.entity.TenantReportSettings;
import com.oak.dms.reporting.repository.TenantReportSettingsRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Settings resolution and validation (CRG-FR-008): no row = the defaults, a tenant can only go below the platform
 * ceiling, enums are validated, every real change is audited once and an identical save is a silent no-op.
 */
class ReportSettingsServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private ReportProperties props;
    private TenantReportSettingsRepository repository;
    private UserRepository users;
    private AuditService audit;
    private ReportSettingsService service;

    @BeforeEach
    void setUp() {
        props = new ReportProperties();
        repository = mock(TenantReportSettingsRepository.class);
        users = mock(UserRepository.class);
        audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
        when(repository.findById(tenantId)).thenReturn(Optional.empty());
        when(repository.save(any(TenantReportSettings.class))).thenAnswer(i -> i.getArgument(0));
        service = new ReportSettingsService(repository, new ReportSettingsDefaults(props), props, audit, users);
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A settings update with every field null except the named ones: {@code upd("maxRows", 10, "csvDelimiter", ";")}. */
    private static ReportSettingsUpdate upd(Object... keyValues) {
        try {
            var components = ReportSettingsUpdate.class.getRecordComponents();
            Object[] args = new Object[components.length];
            for (int i = 0; i < keyValues.length; i += 2) {
                boolean found = false;
                for (int c = 0; c < components.length; c++) {
                    if (components[c].getName().equals(keyValues[i])) {
                        args[c] = keyValues[i + 1];
                        found = true;
                    }
                }
                assertThat(found).as("no such setting " + keyValues[i]).isTrue();
            }
            return (ReportSettingsUpdate) ReportSettingsUpdate.class.getDeclaredConstructors()[0].newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fieldErrors(ReportException e) {
        return (Map<String, String>) e.getDetails();
    }

    // ------------------------------------------------------------------ defaults: no row

    @Test
    void noRow_meansTheCodeDefaultsAndPlatformValues() {
        EffectiveReportSettings s = service.forTenant(tenantId);

        assertThat(s.enabled()).isTrue();
        assertThat(s.schedulingEnabled()).isTrue();
        assertThat(s.allowedFormats()).containsExactly("pdf", "xlsx", "csv");
        assertThat(s.defaultFormat()).isEqualTo("xlsx");
        assertThat(s.csvDelimiter()).isEqualTo(",");
        assertThat(s.csvIncludeBom()).isTrue();
        assertThat(s.pdfPageSize()).isEqualTo("A4");
        assertThat(s.pdfOrientation()).isEqualTo("landscape");
        assertThat(s.maxRows()).isEqualTo(200_000);
        assertThat(s.maxPdfRows()).isEqualTo(5_000);
        assertThat(s.overLimitBehaviour()).isEqualTo("fail");
        assertThat(s.maxConcurrentRuns()).isEqualTo(3);
        assertThat(s.maxQueuedPerUser()).isEqualTo(5);
        assertThat(s.fileRetentionDays()).isEqualTo(7);
        assertThat(s.minScheduleInterval()).isEqualTo("daily");
        assertThat(s.maxRecipientsPerSchedule()).isEqualTo(25);
        assertThat(s.allowedDeliveryChannels()).containsExactly("in_app", "email");
        assertThat(s.deliveryModeDefault()).isEqualTo("link");
        assertThat(s.allowAttachments()).isFalse();
        assertThat(s.maxAttachmentMb()).isEqualTo(10);
        assertThat(s.allowExternalRecipients()).isFalse();
        assertThat(s.externalDomainAllowlist()).isEmpty();
        assertThat(s.pauseAfterConsecutiveFailures()).isEqualTo(5);
        assertThat(s.catchUpWindowHours()).isEqualTo(24);
        assertThat(s.skipEmptyScheduledReports()).isFalse();
        assertThat(s.includeInactiveByDefault()).isFalse();
        assertThat(s.defaultColumns()).isEmpty();
        assertThat(s.defaultSort()).isNull();
    }

    // ------------------------------------------------------------------ ceilings

    @Test
    void tenantValueBelowTheCeiling_wins() {
        TenantReportSettings row = TenantReportSettings.builder().tenantId(tenantId)
                .maxRows(1_000).maxPdfRows(50).maxConcurrentRuns(1).fileRetentionDays(30).build();
        when(repository.findById(tenantId)).thenReturn(Optional.of(row));

        EffectiveReportSettings s = service.forTenant(tenantId);

        assertThat(s.maxRows()).isEqualTo(1_000);
        assertThat(s.maxPdfRows()).isEqualTo(50);
        assertThat(s.maxConcurrentRuns()).isEqualTo(1);
        assertThat(s.fileRetentionDays()).isEqualTo(30);
    }

    @Test
    void aStoredValueAboveACeilingTheOperatorLoweredLater_isCappedNotTrusted() {
        TenantReportSettings row = TenantReportSettings.builder().tenantId(tenantId)
                .maxRows(150_000).fileRetentionDays(60).maxAttachmentMb(20).build();
        when(repository.findById(tenantId)).thenReturn(Optional.of(row));
        props.setMaxRows(100_000);
        props.setMaxFileRetentionDays(14);
        props.setMaxAttachmentMb(12);

        EffectiveReportSettings s = service.forTenant(tenantId);

        assertThat(s.maxRows()).isEqualTo(100_000);
        assertThat(s.fileRetentionDays()).isEqualTo(14);
        assertThat(s.maxAttachmentMb()).isEqualTo(12);
    }

    @Test
    void aNullTenantValue_takesThePlatformDefault_stillCappedAtTheCeiling() {
        props.setDefaultFileRetentionDays(30);
        props.setMaxFileRetentionDays(10);

        assertThat(service.forTenant(tenantId).fileRetentionDays()).isEqualTo(10);
    }

    @Test
    void thePlatformSwitchBeatsTheTenantSwitch() {
        props.setEnabled(false);

        EffectiveReportSettings s = service.forTenant(tenantId);

        assertThat(s.enabled()).isFalse();
        assertThat(s.schedulingEnabled()).isFalse();
        assertThatThrownBy(() -> service.requireEnabled(tenantId))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getCode()).isEqualTo("REPORTS_DISABLED"));
    }

    @Test
    void tenantSwitchOff_disablesReportsAndScheduling() {
        when(repository.findById(tenantId)).thenReturn(Optional.of(TenantReportSettings.builder().tenantId(tenantId).enabled(false).build()));

        assertThatThrownBy(() -> service.requireEnabled(tenantId))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getCode()).isEqualTo("REPORTS_DISABLED"));
        assertThat(service.forTenant(tenantId).schedulingEnabled()).isFalse();
    }

    @Test
    void schedulingOff_keepsReportsOn() {
        when(repository.findById(tenantId)).thenReturn(Optional.of(TenantReportSettings.builder().tenantId(tenantId).schedulingEnabled(false).build()));

        EffectiveReportSettings s = service.forTenant(tenantId);

        assertThat(s.enabled()).isTrue();
        assertThat(s.schedulingEnabled()).isFalse();
    }

    @Test
    void hourlyIsOnlyHonouredWhileThePlatformFlagPermitsIt() {
        when(repository.findById(tenantId)).thenReturn(Optional.of(TenantReportSettings.builder().tenantId(tenantId).minScheduleInterval("hourly").build()));

        assertThat(service.forTenant(tenantId).minScheduleInterval()).isEqualTo("daily");
        props.setAllowHourlySchedules(true);
        assertThat(service.forTenant(tenantId).minScheduleInterval()).isEqualTo("hourly");
    }

    @Test
    void theRowLimitDependsOnTheFormat() {
        EffectiveReportSettings s = service.forTenant(tenantId);

        assertThat(s.rowLimitFor("pdf")).isEqualTo(5_000);
        assertThat(s.rowLimitFor("xlsx")).isEqualTo(200_000);
        assertThat(s.rowLimitFor("csv")).isEqualTo(200_000);
    }

    // ------------------------------------------------------------------ save: ceilings and enums

    @Test
    void save_rejectsAnythingAboveACeiling_namingEachField_andStoresNothing() {
        assertThatThrownBy(() -> service.save(upd("maxRows", 200_001, "maxPdfRows", 5_001, "fileRetentionDays", 91,
                "maxConcurrentRuns", 11, "maxAttachmentMb", 26, "maxRecipientsPerSchedule", 101)))
                .isInstanceOfSatisfying(ReportException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REPORT_SETTINGS_INVALID");
                    assertThat(fieldErrors(e)).containsKeys("maxRows", "maxPdfRows", "fileRetentionDays",
                            "maxConcurrentRuns", "maxAttachmentMb", "maxRecipientsPerSchedule");
                    assertThat(fieldErrors(e).get("maxRows")).contains("200000");
                });

        verify(repository, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void save_acceptsTheCeilingItself_andRejectsZeroAndNegatives() {
        assertThat(service.save(upd("maxRows", 200_000)).maxRows()).isEqualTo(200_000);

        assertThatThrownBy(() -> service.save(upd("maxRows", 0, "maxPdfRows", -4)))
                .isInstanceOfSatisfying(ReportException.class,
                        e -> assertThat(fieldErrors(e)).containsKeys("maxRows", "maxPdfRows"));
    }

    @Test
    void save_validatesEveryEnum_andReportsThemAllAtOnce() {
        assertThatThrownBy(() -> service.save(upd("allowedFormats", List.of("pdf", "docx"), "csvDelimiter", "|",
                "pdfPageSize", "A3", "pdfOrientation", "diagonal", "overLimitBehaviour", "ignore",
                "deliveryModeDefault", "carrier-pigeon", "allowedDeliveryChannels", List.of("sms"),
                "minScheduleInterval", "weekly")))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(fieldErrors(e)).containsKeys(
                        "allowedFormats", "csvDelimiter", "pdfPageSize", "pdfOrientation", "overLimitBehaviour",
                        "deliveryModeDefault", "allowedDeliveryChannels", "minScheduleInterval"));
    }

    @Test
    void save_needsAtLeastOneFormat_andTheDefaultMustBeAllowed() {
        assertThatThrownBy(() -> service.save(upd("allowedFormats", List.of())))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(fieldErrors(e)).containsKey("allowedFormats"));

        assertThatThrownBy(() -> service.save(upd("allowedFormats", List.of("csv"), "defaultFormat", "pdf")))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(fieldErrors(e)).containsKey("defaultFormat"));
    }

    @Test
    void save_withOnlyCsvAllowedAndNoDefaultGiven_fallsBackToCsv() {
        EffectiveReportSettings s = service.save(upd("allowedFormats", List.of("csv")));

        assertThat(s.allowedFormats()).containsExactly("csv");
        assertThat(s.defaultFormat()).isEqualTo("csv");
    }

    @Test
    void save_hourlyIsRefusedWhileThePlatformFlagIsOff() {
        assertThatThrownBy(() -> service.save(upd("minScheduleInterval", "hourly")))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(fieldErrors(e)).containsKey("minScheduleInterval"));

        props.setAllowHourlySchedules(true);
        assertThat(service.save(upd("minScheduleInterval", "hourly")).minScheduleInterval()).isEqualTo("hourly");
    }

    @Test
    void save_anAttachmentDefaultNeedsAttachmentsToBeAllowed() {
        assertThatThrownBy(() -> service.save(upd("deliveryModeDefault", "attachment")))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(fieldErrors(e)).containsKey("deliveryModeDefault"));

        assertThat(service.save(upd("deliveryModeDefault", "attachment", "allowAttachments", true)).deliveryModeDefault())
                .isEqualTo("attachment");
    }

    @Test
    void save_validatesTimeZoneLocalePatternsAndDomains() {
        assertThatThrownBy(() -> service.save(upd("defaultTimeZone", "Mars/Olympus", "defaultLocale", "!!",
                "dateFormat", "qqqq-bad", "numberFormat", "#,##0.0.0", "externalDomainAllowlist", List.of("not a domain"))))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(fieldErrors(e)).containsKeys(
                        "defaultTimeZone", "defaultLocale", "dateFormat", "numberFormat", "externalDomainAllowlist"));

        EffectiveReportSettings s = service.save(upd("defaultTimeZone", "Asia/Colombo", "defaultLocale", "si-LK",
                "dateFormat", "dd/MM/yyyy", "numberFormat", "#,##0.00",
                "externalDomainAllowlist", List.of(" Partner.COM ", "partner.com", "audit.example.org")));
        assertThat(s.defaultTimeZone()).isEqualTo("Asia/Colombo");
        assertThat(s.externalDomainAllowlist()).containsExactly("partner.com", "audit.example.org");
    }

    @Test
    void save_failureAdminsMustBeUsersOfThisOrganisation() {
        UUID stranger = UUID.randomUUID();
        when(users.findAllByUserIdInAndTenantIdAndDeletedFalse(anyCollection(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.save(upd("failureAdminUserIds", List.of(stranger))))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(fieldErrors(e)).containsKey("failureAdminUserIds"));

        User admin = mock(User.class);
        when(users.findAllByUserIdInAndTenantIdAndDeletedFalse(anyCollection(), any())).thenReturn(List.of(admin));
        assertThat(service.save(upd("failureAdminUserIds", List.of(UUID.randomUUID()))).failureAdminUserIds()).hasSize(1);
    }

    // ------------------------------------------------------------------ save: audit and idempotence

    @Test
    void save_persistsAndAuditsOnce_withOldAndNewValues() {
        EffectiveReportSettings s = service.save(upd("maxRows", 50_000, "csvDelimiter", ";", "allowExternalRecipients", true,
                "confidentialityNotice", "Internal only"));

        assertThat(s.maxRows()).isEqualTo(50_000);
        assertThat(s.csvDelimiter()).isEqualTo(";");
        assertThat(s.allowExternalRecipients()).isTrue();
        assertThat(s.confidentialityNotice()).isEqualTo("Internal only");

        ArgumentCaptor<TenantReportSettings> saved = ArgumentCaptor.forClass(TenantReportSettings.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo(userId);
        verify(audit, times(1)).event(AuditActionCatalog.REPORT_SETTINGS_UPDATED);
    }

    @Test
    void save_savingTheSameSettingsAgain_isANoOp_notSavedNotAudited() {
        ArgumentCaptor<TenantReportSettings> saved = ArgumentCaptor.forClass(TenantReportSettings.class);
        ReportSettingsUpdate same = upd("maxRows", 50_000, "csvDelimiter", ";");
        service.save(same);
        verify(repository).save(saved.capture());
        when(repository.findById(tenantId)).thenReturn(Optional.of(saved.getValue()));

        service.save(same);

        verify(repository, times(1)).save(any());
        verify(audit, times(1)).event(AuditActionCatalog.REPORT_SETTINGS_UPDATED);
    }

    @Test
    void save_aTenantWithNoRowSavingTheDefaults_isANoOp() {
        service.save(upd());

        verify(repository, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void ceilings_areExposedForTheAdminScreens() {
        ReportSettingsService.Ceilings c = service.ceilings();

        assertThat(c.maxRows()).isEqualTo(200_000);
        assertThat(c.maxPdfRows()).isEqualTo(5_000);
        assertThat(c.fileRetentionDays()).isEqualTo(90);
        assertThat(c.maxMetadataFilters()).isEqualTo(5);
        assertThat(c.allowHourlySchedules()).isFalse();
    }
}
