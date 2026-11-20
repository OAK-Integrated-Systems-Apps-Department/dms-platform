package com.oak.dms.reporting;

import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderAccessView;
import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.catalog.FieldDescriptor.ValueKind;
import com.oak.dms.reporting.catalog.FieldType;
import com.oak.dms.reporting.datasource.DocumentReportDataSource;
import com.oak.dms.reporting.datasource.ReportContext;
import com.oak.dms.reporting.datasource.ReportDataSource.Preview;
import com.oak.dms.reporting.datasource.ReportQuery;
import com.oak.dms.reporting.datasource.ReportQuery.Column;
import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.reporting.dto.PreviewResponse;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.format.RowResolver;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsDefaults;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportPreviewServiceTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final EffectiveReportSettings settings = new ReportSettingsDefaults(new ReportProperties()).resolve(null);

    private ReportSettingsService settingsService;
    private DocumentAccessService documentAccess;
    private AuthorizationService authorization;
    private DocumentReportDataSource dataSource;
    private RowResolver rowResolver;
    private SimpleMeterRegistry meters;
    private ReportPreviewService service;

    private final ReportDefinition definition = new ReportDefinition("documents", List.of(), List.of("title", "legalHold"),
            List.of(), null, null, null);
    private final List<Column> columns = List.of(
            new Column("title", "Title", FieldType.TEXT, ValueKind.TEXT, false, Special.NONE, "c0"),
            new Column("legalHold", "Legal hold", FieldType.BOOLEAN, ValueKind.BOOLEAN, false, Special.NONE, "c1"));
    private final ReportQuery query = new ReportQuery("select 1", "select 2", null, Map.of(), columns, null, null,
            List.of("Title: contains \"x\""), true, false);

    @BeforeEach
    void setUp() {
        settingsService = mock(ReportSettingsService.class);
        documentAccess = mock(DocumentAccessService.class);
        authorization = mock(AuthorizationService.class);
        dataSource = mock(DocumentReportDataSource.class);
        rowResolver = mock(RowResolver.class);
        meters = new SimpleMeterRegistry();
        service = new ReportPreviewService(settingsService, documentAccess, authorization, dataSource, rowResolver,
                new ReportMetrics(meters));

        when(settingsService.requireEnabled(tenant)).thenReturn(settings);
        when(settingsService.previewTimeout()).thenReturn(Duration.ofSeconds(10));
        when(authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)).thenReturn(true);
        when(documentAccess.forCaller()).thenReturn(new DocumentAccessView(
                new FolderAccessView(List.of(), Map.of(), true), Map.of(), true, false));
        when(dataSource.plan(any(), any())).thenReturn(query);
        TenantContext.set(tenant, user);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private long timed(String outcome) {
        var timer = meters.find("dms.reports.preview").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void returnsTheVisibleTotalAndTheSampleAsText_withTheNoticeAndTheResolvedFilters() {
        ReportRow row = new ReportRow(UUID.randomUUID(), new ArrayList<>(List.of("Annual report", true)));
        when(dataSource.preview(any(), anyInt(), any())).thenReturn(new Preview(1234, List.of(row)));

        PreviewResponse response = service.preview(definition);

        assertThat(response.total()).isEqualTo(1234L);
        assertThat(response.sample()).containsExactly(List.of("Annual report", "Yes"));
        assertThat(response.columns()).extracting(PreviewResponse.ColumnInfo::key).containsExactly("title", "legalHold");
        assertThat(response.limitedToAccess()).isTrue();
        assertThat(response.resolvedFilters()).containsExactly("Title: contains \"x\"");
        assertThat(response.timedOut()).isFalse();
        assertThat(response.maxRows()).isEqualTo(settings.maxRows());
        assertThat(response.maxPdfRows()).isEqualTo(settings.maxPdfRows());
        verify(rowResolver).resolve(any(), any(), any(), any());
        assertThat(timed("ok")).isEqualTo(1);
    }

    @Test
    void theSampleIsAtMostFiftyRows_andUsesThePreviewTimeoutFromTheSettingsService() {
        when(dataSource.preview(any(), anyInt(), any())).thenReturn(new Preview(0, List.of()));

        service.preview(definition);

        verify(dataSource).preview(any(), org.mockito.ArgumentMatchers.eq(50), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(10)));
    }

    @Test
    void aTurnedOffFeatureIsRefused_beforeAnythingIsQueried() {
        when(settingsService.requireEnabled(tenant)).thenThrow(ReportException.disabled());

        assertThatThrownBy(() -> service.preview(definition)).isInstanceOfSatisfying(ReportException.class,
                e -> assertThat(e.getCode()).isEqualTo("REPORTS_DISABLED"));
        verify(dataSource, never()).plan(any(), any());
        assertThat(timed("refused")).isEqualTo(1);
    }

    @Test
    void theRunGrantAloneIsNotAWayToSeeDocuments() {
        when(authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)).thenReturn(false);

        assertThatThrownBy(() -> service.preview(definition)).isInstanceOfSatisfying(ReportException.class, e -> {
            assertThat(e.getCode()).isEqualTo("REPORT_DOCUMENT_ACCESS_REQUIRED");
            assertThat(e.getStatus().value()).isEqualTo(403);
        });
        verify(dataSource, never()).plan(any(), any());
        verify(dataSource, never()).preview(any(), anyInt(), any());
    }

    @Test
    void aSlowQueryIsAnsweredWithTimedOut_notAnError_andNoTotalIsGuessed() {
        when(dataSource.preview(any(), anyInt(), any())).thenThrow(
                new QueryTimeoutException("canceled", new SQLException("canceling statement due to statement timeout", "57014")));

        PreviewResponse response = service.preview(definition);

        assertThat(response.timedOut()).isTrue();
        assertThat(response.total()).isNull();
        assertThat(response.sample()).isEmpty();
        assertThat(response.columns()).hasSize(2);
        assertThat(response.resolvedFilters()).isNotEmpty();
        verify(rowResolver, never()).resolve(any(), any(), any(), any());
        assertThat(timed("timeout")).isEqualTo(1);
    }

    @Test
    void aRealDatabaseFailureIsNotDisguisedAsATimeout() {
        when(dataSource.preview(any(), anyInt(), any())).thenThrow(
                new DataAccessResourceFailureException("down", new SQLException("connection refused", "08006")));

        assertThatThrownBy(() -> service.preview(definition)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(timed("error")).isEqualTo(1);
    }

    @Test
    void theDefinitionsOwnZoneIsUsed_elseTheOrganisationsDefault_andABadOneIsRefused() {
        when(dataSource.preview(any(), anyInt(), any())).thenReturn(new Preview(0, List.of()));
        ArgumentCaptor<ReportContext> captor = ArgumentCaptor.forClass(ReportContext.class);

        PreviewResponse colombo = service.preview(new ReportDefinition("documents", List.of(), List.of("title"), List.of(),
                null, null, "Asia/Colombo"));
        service.preview(definition);
        verify(dataSource, org.mockito.Mockito.times(2)).plan(any(), captor.capture());

        assertThat(colombo.timeZone()).isEqualTo("Asia/Colombo");
        assertThat(captor.getAllValues().get(0).zone().getId()).isEqualTo("Asia/Colombo");
        // no zone configured anywhere: UTC, never a failure
        assertThat(settings.defaultTimeZone()).isNull();
        assertThat(captor.getAllValues().get(1).zone().getId()).isEqualTo("UTC");

        assertThatThrownBy(() -> service.preview(new ReportDefinition("documents", List.of(), List.of("title"), List.of(),
                null, null, "Mars/Olympus"))).isInstanceOfSatisfying(ReportException.class,
                e -> assertThat(e.getCode()).isEqualTo("REPORT_TIMEZONE_INVALID"));
    }

    @Test
    void anInvalidDefinitionFromThePlannerIsPassedStraightThrough() {
        when(dataSource.plan(any(), any())).thenThrow(
                ReportException.invalid("REPORT_FIELD_UNAVAILABLE", "This field isn't available for reports.", Map.of("columns[0]", "x")));

        assertThatThrownBy(() -> service.preview(definition)).isInstanceOfSatisfying(ReportException.class,
                e -> assertThat(e.getCode()).isEqualTo("REPORT_FIELD_UNAVAILABLE"));
        assertThat(timed("refused")).isEqualTo(1);
    }
}
