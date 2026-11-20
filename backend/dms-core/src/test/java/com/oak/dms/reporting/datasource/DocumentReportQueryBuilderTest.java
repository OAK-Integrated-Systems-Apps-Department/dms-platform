package com.oak.dms.reporting.datasource;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.Folder;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderAccessView;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.ReportFieldCatalog;
import com.oak.dms.reporting.datasource.DocumentReportQueryBuilder.Limits;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ReportDefinition.Filter;
import com.oak.dms.reporting.dto.ReportDefinition.Sort;
import com.oak.dms.reporting.entity.ReportFieldSetting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one place SQL for documents is built (brief section 5): every operator on every type, the access predicate, relative dates
 * in different zones, parameterisation, the caps, and fields the administrator switched off.
 */
class DocumentReportQueryBuilderTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant NOW = Instant.parse("2024-03-10T12:00:00Z");
    private static final Limits LIMITS = new Limits(25, 40, 5);

    private final DocumentReportQueryBuilder builder = new DocumentReportQueryBuilder();

    private final MetadataField contractRef = meta("contractRef", "text", true);
    private final MetadataField contractValue = meta("contractValue", "decimal", true);
    private final MetadataField signedOn = meta("signedOn", "date", true);
    private final MetadataField isCritical = meta("isCritical", "boolean", true);
    private final MetadataField region = meta("region", "dropdown", true);
    private final MetadataField tags = meta("tags", "multi_select", true);
    private final MetadataField notes = meta("notes", "long_text", false);

    private final EffectiveCatalogue catalogue = ReportFieldCatalog.merge(
            List.of(contractRef, contractValue, signedOn, isCritical, region, tags, notes), List.of());

    private final DocumentAccessView admin = new DocumentAccessView(
            new FolderAccessView(List.of(), Map.of(), true), Map.of(), true, false);

    // ---- helpers

    private static MetadataField meta(String key, String type, boolean searchable) {
        return MetadataField.builder().fieldId(UUID.randomUUID()).tenantId(TENANT).fieldKey(key).label(key)
                .dataType(type).searchable(searchable).build();
    }

    private static Filter f(String field, String op, String... values) {
        return new Filter(field, op, List.of(values));
    }

    private static ReportDefinition def(Filter... filters) {
        return new ReportDefinition("documents", List.of(filters), List.of("title"), List.of(), null, null, null);
    }

    private ReportQuery build(ReportDefinition d) {
        return build(d, admin, catalogue, ZoneId.of("UTC"), false);
    }

    private ReportQuery build(ReportDefinition d, DocumentAccessView access, EffectiveCatalogue cat, ZoneId zone,
                              boolean includeInactiveDefault) {
        return builder.build(TENANT, access, d, cat, LIMITS, zone, NOW, includeInactiveDefault);
    }

    private static String norm(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static void assertSql(ReportQuery q, String fragment) {
        assertThat(norm(q.sql())).contains(fragment);
        assertThat(norm(q.countSql())).contains(fragment);
    }

    private static Object param(ReportQuery q, String name) {
        assertThat(q.params()).containsKey(name);
        return q.params().get(name);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Collection<Object> coll(ReportQuery q, String name) {
        return (java.util.Collection<Object>) param(q, name);
    }

    private static void assertRefused(ReportDefinition d, DocumentReportQueryBuilder b, String code) {
        assertThatThrownBy(() -> b.build(TENANT,
                new DocumentAccessView(new FolderAccessView(List.of(), Map.of(), true), Map.of(), true, false), d,
                ReportFieldCatalog.merge(List.of(meta("contractRef", "text", true), meta("notes", "long_text", false)),
                        List.of()), LIMITS, ZoneId.of("UTC"), NOW, false))
                .isInstanceOfSatisfying(ReportException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(code);
                    assertThat(e.getStatus().value()).isEqualTo(422);
                });
    }

    // ---- access predicate

    @Test
    void everyQueryIsScopedToTheTenant_andNeverIncludesTemplatesBinnedOrPurgedDocuments() {
        ReportQuery q = build(def());

        assertSql(q, "d.tenant_id = :tenant");
        assertSql(q, "not d.is_deleted");
        assertSql(q, "not d.is_template");
        assertSql(q, "d.purged_at is null");
        assertThat(param(q, "tenant")).isEqualTo(TENANT);
    }

    @Test
    void theAccessPredicateIsPushedIntoSql_forTheCountToo_soTotalsOnlyCountVisibleRows() {
        UUID hiddenFolder = UUID.randomUUID();
        UUID granted = UUID.randomUUID();
        UUID approvalReadable = UUID.randomUUID();
        Folder hidden = Folder.builder().folderId(hiddenFolder).tenantId(TENANT).folderName("HR")
                .accessMode(FolderAccessMode.restricted).build();
        DocumentAccessView limited = new DocumentAccessView(new FolderAccessView(List.of(hidden), Map.of(), false),
                Map.of(granted, FolderAccessLevel.view), false, true, Set.of(approvalReadable));

        ReportQuery q = build(def(), limited, catalogue, ZoneId.of("UTC"), false);

        assertSql(q, "(:everything or (d.access_mode = 'inherit' and (d.folder_id is null or d.folder_id not in (:hidden)))"
                + " or (d.access_mode = 'restricted' and d.document_id in (:granted))"
                + " or d.document_id in (:wfReadable))");
        assertThat(param(q, "everything")).isEqualTo(false);
        assertThat(coll(q, "hidden")).containsExactly(hiddenFolder);
        assertThat(coll(q, "granted")).containsExactly(granted);
        assertThat(coll(q, "wfReadable")).containsExactly(approvalReadable);
        assertThat(q.limitedToAccess()).isTrue();
    }

    @Test
    void emptyAccessCollectionsAreNeverBoundEmpty_soTheInClauseStaysValid() {
        DocumentAccessView limited = new DocumentAccessView(
                new FolderAccessView(List.of(), Map.of(), false), Map.of(), false, true);

        ReportQuery q = build(def(), limited, catalogue, ZoneId.of("UTC"), false);

        assertThat(coll(q, "hidden")).hasSize(1);
        assertThat(coll(q, "granted")).hasSize(1);
        assertThat(coll(q, "wfReadable")).hasSize(1);
    }

    @Test
    void anAdministratorSeesEverything_andIsNotToldTheyAreLimited() {
        ReportQuery q = build(def());

        assertThat(param(q, "everything")).isEqualTo(true);
        assertThat(q.limitedToAccess()).isFalse();
    }

    // ---- lifecycle

    @Test
    void archivedAndInactiveDocumentsAreLeftOutByDefault() {
        assertSql(build(def()), "d.lifecycle_state = 'active'");
    }

    @Test
    void includeInactiveInTheDefinitionOrTheTenantDefaultLiftsTheRestriction() {
        ReportDefinition on = new ReportDefinition("documents", List.of(), List.of("title"), List.of(), null, true, null);
        assertThat(norm(build(on).sql())).doesNotContain("lifecycle_state = 'active'");

        assertThat(norm(build(def(), admin, catalogue, ZoneId.of("UTC"), true).sql()))
                .doesNotContain("lifecycle_state = 'active'");
    }

    @Test
    void anExplicitDefinitionBeatsTheTenantDefault() {
        ReportDefinition off = new ReportDefinition("documents", List.of(), List.of("title"), List.of(), null, false, null);

        assertThat(norm(build(off, admin, catalogue, ZoneId.of("UTC"), true).sql())).contains("lifecycle_state = 'active'");
    }

    @Test
    void filteringOnLifecycleStateAlsoLiftsTheRestriction() {
        ReportQuery q = build(def(f("lifecycleState", "is_any_of", "archived")));

        assertThat(norm(q.sql())).doesNotContain("lifecycle_state = 'active'");
        assertSql(q, "d.lifecycle_state in (:p0)");
    }

    // ---- operators, by data type

    @Test
    void textOperators() {
        ReportQuery contains = build(def(f("title", "contains", "Annual Report")));
        assertSql(contains, "lower(d.title) like :p0");
        assertThat(param(contains, "p0")).isEqualTo("%annual report%");

        ReportQuery equals = build(def(f("title", "equals", "Annual Report")));
        assertSql(equals, "lower(d.title) = :p0");
        assertThat(param(equals, "p0")).isEqualTo("annual report");

        ReportQuery starts = build(def(f("title", "starts_with", "Inv")));
        assertSql(starts, "lower(d.title) like :p0");
        assertThat(param(starts, "p0")).isEqualTo("inv%");

        assertSql(build(def(f("title", "is_empty"))), "(d.title is null or d.title = '')");
    }

    @Test
    void likeWildcardsInUserTextAreMadeLiteral() {
        ReportQuery q = build(def(f("title", "contains", "50%_off\\")));

        assertThat(param(q, "p0")).isEqualTo("%50\\%\\_off\\\\%");
    }

    @Test
    void choiceAndReferenceOperators() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        ReportQuery any = build(def(f("owner", "is_any_of", a.toString(), b.toString())));
        assertSql(any, "d.owner_user_id in (:p0)");
        assertThat(coll(any, "p0")).containsExactlyInAnyOrder(a, b);

        ReportQuery none = build(def(f("owner", "is_none_of", a.toString())));
        assertSql(none, "(d.owner_user_id is null or d.owner_user_id not in (:p0))");

        assertSql(build(def(f("department", "is_empty"))), "d.owner_department_id is null");

        ReportQuery status = build(def(f("status", "is_any_of", "draft", "published")));
        assertSql(status, "d.status in (:p0)");
        assertThat(coll(status, "p0")).containsExactlyInAnyOrder("draft", "published");
    }

    @Test
    void numberOperators() {
        String[][] cases = {{"eq", "="}, {"ne", "<>"}, {"lt", "<"}, {"lte", "<="}, {"gt", ">"}, {"gte", ">="}};
        for (String[] c : cases) {
            ReportQuery q = build(def(f("pageCount", c[0], "12.5")));
            assertSql(q, "v.page_count " + c[1] + " :p0");
            assertThat(param(q, "p0")).isEqualTo(new BigDecimal("12.5"));
        }
        ReportQuery between = build(def(f("fileSize", "between", "10", "20")));
        assertSql(between, "v.size_bytes between :p0 and :p1");
        assertThat(param(between, "p0")).isEqualTo(new BigDecimal("10"));
        assertThat(param(between, "p1")).isEqualTo(new BigDecimal("20"));
    }

    @Test
    void dateOperators_areHalfOpenRangesInTheReportsZone() {
        ZoneId colombo = ZoneId.of("Asia/Colombo"); // +05:30
        OffsetDateTime dayStart = LocalDate.parse("2024-02-01").atStartOfDay(colombo).toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime nextDay = LocalDate.parse("2024-02-02").atStartOfDay(colombo).toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);

        ReportQuery on = build(def(f("createdAt", "on", "2024-02-01")), admin, catalogue, colombo, false);
        assertSql(on, "(d.created_at >= :p0 and d.created_at < :p1)");
        assertThat(param(on, "p0")).isEqualTo(dayStart);
        assertThat(param(on, "p1")).isEqualTo(nextDay);

        ReportQuery before = build(def(f("createdAt", "before", "2024-02-01")), admin, catalogue, colombo, false);
        assertSql(before, "d.created_at < :p0");
        assertThat(param(before, "p0")).isEqualTo(dayStart);

        ReportQuery after = build(def(f("createdAt", "after", "2024-02-01")), admin, catalogue, colombo, false);
        assertSql(after, "d.created_at >= :p0");
        assertThat(param(after, "p0")).isEqualTo(nextDay);

        // between is inclusive of the last day
        ReportQuery between = build(def(f("createdAt", "between", "2024-02-01", "2024-02-01")), admin, catalogue, colombo, false);
        assertSql(between, "(d.created_at >= :p0 and d.created_at < :p1)");
        assertThat(param(between, "p0")).isEqualTo(dayStart);
        assertThat(param(between, "p1")).isEqualTo(nextDay);

        assertSql(build(def(f("retentionDue", "is_empty"))), "d.review_due_at is null");
    }

    @Test
    void booleanOperators() {
        assertSql(build(def(f("legalHold", "is_true"))), "d.legal_hold is true");
        assertSql(build(def(f("legalHold", "is_false"))), "d.legal_hold is false");
    }

    @Test
    void severalValuesInOneFilterAreOred_andSeveralFiltersAreAnded() {
        ReportQuery q = build(def(f("status", "is_any_of", "draft", "published"), f("legalHold", "is_true")));

        String sql = norm(q.sql());
        assertThat(sql).contains("d.status in (:p0) and d.legal_hold is true");
    }

    // ---- relative dates

    @Test
    void relativeDatesResolveAtRunTime_inTheReportsZone_soTheSameInstantIsADifferentDayInDifferentZones() {
        // 2024-03-10 12:00 UTC is already the 11th at +14:00 and still the 10th at -11:00.
        ReportQuery kiritimati = build(def(f("createdAt", "relative", "today")), admin, catalogue,
                ZoneId.of("Pacific/Kiritimati"), false);
        ReportQuery pagoPago = build(def(f("createdAt", "relative", "today")), admin, catalogue,
                ZoneId.of("Pacific/Pago_Pago"), false);

        assertThat(param(kiritimati, "p0")).isEqualTo(OffsetDateTime.parse("2024-03-10T10:00:00Z"));
        assertThat(param(pagoPago, "p0")).isEqualTo(OffsetDateTime.parse("2024-03-10T11:00:00Z"));
        assertThat(kiritimati.resolvedFilters().get(0)).contains("2024-03-11");
        assertThat(pagoPago.resolvedFilters().get(0)).contains("2024-03-10");
    }

    @Test
    void theResolvedAbsoluteRangeIsPrintedForTheReportHeader() {
        ReportQuery q = build(def(f("createdAt", "relative", "last_7_days")));

        assertThat(q.resolvedFilters()).containsExactly("Created at: last 7 days (2024-03-04 to 2024-03-10)");
    }

    @Test
    void anUnknownRelativeExpressionIsRefused() {
        assertRefused(def(f("createdAt", "relative", "next_century")), builder, "REPORT_VALUE_INVALID");
        assertRefused(def(f("createdAt", "relative", "last_0_days")), builder, "REPORT_VALUE_INVALID");
    }

    // ---- derived fields

    @Test
    void approvalStatusIsDerivedFromTheLatestWorkflowInstance_notDocumentStatus() {
        ReportQuery q = build(def(f("approvalStatus", "is_any_of", "in_progress", "not_submitted")));

        assertSql(q, "coalesce(wf.status, 'not_submitted') in (:p0)");
        assertSql(q, "left join lateral (select i.status, i.current_stage_instance_id from workflow_instances i"
                + " where i.tenant_id = d.tenant_id and i.document_id = d.document_id and not i.is_deleted"
                + " order by i.started_at desc limit 1) wf on true");
    }

    @Test
    void workflowStageMatchesByNameOrId_onlyWhileTheLatestInstanceIsRunning() {
        UUID stageId = UUID.randomUUID();

        ReportQuery q = build(def(f("workflowStage", "is_any_of", "Legal Review", stageId.toString())));

        assertSql(q, "(wf.status = 'in_progress' and (si.stage_id in (:p0) or lower(stg.name) in (:p1)))");
        assertThat(coll(q, "p0")).containsExactly(stageId);
        assertThat(coll(q, "p1")).containsExactly("legal review");
    }

    @Test
    void documentsWithNoTypeDepartmentOrOwnerStayInTheResult_becauseEveryLookupIsALeftJoin() {
        ReportDefinition d = new ReportDefinition("documents", List.of(),
                List.of("documentType", "department", "owner", "createdBy", "modifiedBy", "versionNumber"),
                List.of(), null, null, null);

        String sql = norm(build(d).sql());

        assertThat(sql).contains("left join document_types t").contains("left join departments dep")
                .contains("left join users own").contains("left join users cre").contains("left join users upd")
                .contains("left join document_versions v");
        // no plain (inner) join anywhere: that would silently drop a document that has no type, department or owner
        assertThat(sql).doesNotContainPattern("(?<!left) join ");
    }

    // ---- custom metadata fields

    @Test
    void metadataFieldsAreOfferedAsMetaKeys_andTheFieldIdIsBoundNeverWrittenIntoSql() {
        ReportQuery q = build(def(f("meta.contractRef", "contains", "abc")));

        assertSql(q, "lower(dm.field_values #>> array[cast(:mk0 as text), 'v']) like :p0");
        assertThat(param(q, "mk0")).isEqualTo(contractRef.getFieldId().toString());
        assertThat(q.sql()).doesNotContain(contractRef.getFieldId().toString());
        assertSql(q, "left join document_metadata dm on dm.tenant_id = d.tenant_id and dm.document_id = d.document_id");
    }

    @Test
    void metadataNumbersDatesAndBooleansAreGuardedSoDirtyJsonCannotFailTheReport() {
        ReportQuery number = build(def(f("meta.contractValue", "gt", "100")));
        assertSql(number, "case when jsonb_typeof(dm.field_values -> cast(:mk0 as text) -> 'v') = 'number'"
                + " then (dm.field_values #>> array[cast(:mk0 as text), 'v'])::numeric end > :p0");

        ReportQuery date = build(def(f("meta.signedOn", "before", "2024-01-01")));
        assertSql(date, "case when (dm.field_values #>> array[cast(:mk0 as text), 'v']) ~ '^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])'"
                + " then left(dm.field_values #>> array[cast(:mk0 as text), 'v'], 10)::date end < :p0");
        assertThat(param(date, "p0")).isEqualTo(LocalDate.parse("2024-01-01"));

        ReportQuery bool = build(def(f("meta.isCritical", "is_true")));
        assertSql(bool, "case when jsonb_typeof(dm.field_values -> cast(:mk0 as text) -> 'v') = 'boolean'"
                + " then (dm.field_values #>> array[cast(:mk0 as text), 'v'])::boolean end is true");
    }

    @Test
    void aMultiSelectMatchesWhenAnyChosenValueIsInTheStoredList() {
        ReportQuery q = build(def(f("meta.tags", "is_any_of", "red", "blue")));

        assertSql(q, "((dm.field_values -> cast(:mk0 as text) -> 'v') @> to_jsonb(cast(:p0 as text))"
                + " or (dm.field_values -> cast(:mk0 as text) -> 'v') @> to_jsonb(cast(:p1 as text)))");
        assertThat(param(q, "p0")).isEqualTo("red");
        assertThat(param(q, "p1")).isEqualTo("blue");
    }

    @Test
    void aMetadataFieldIsBoundOncePerQuery_howeverOftenItIsUsed() {
        ReportDefinition d = new ReportDefinition("documents",
                List.of(f("meta.contractRef", "contains", "a"), f("meta.contractRef", "is_empty")),
                List.of("meta.contractRef"), List.of(new Sort("meta.contractRef", "asc")), null, null, null);

        ReportQuery q = build(d);

        assertThat(q.params().keySet()).filteredOn(k -> k.startsWith("mk")).containsExactly("mk0");
    }

    @Test
    void aMetadataFieldThatIsNotSearchableIsColumnOnly() {
        ReportDefinition show = new ReportDefinition("documents", List.of(), List.of("meta.notes"), List.of(), null, null, null);
        assertThat(build(show).columns()).extracting(ReportQuery.Column::key).containsExactly("meta.notes");

        assertRefused(def(f("meta.notes", "contains", "x")), builder, "REPORT_FIELD_UNAVAILABLE");
    }

    @Test
    void anInactiveOrDeprecatedMetadataFieldIsNotOffered() {
        MetadataField retired = meta("retired", "text", true);
        retired.setStatus(MetadataField.FieldStatus.inactive);
        EffectiveCatalogue cat = ReportFieldCatalog.merge(List.of(retired), List.of());

        assertThat(cat.contains("meta.retired")).isFalse();
    }

    // ---- caps

    @Test
    void atMostFiveMetadataFiltersByDefault_andTheCapIsConfigurable() {
        Filter[] six = new Filter[6];
        for (int i = 0; i < six.length; i++) {
            six[i] = f("meta.contractRef", "contains", "v" + i);
        }
        assertRefused(def(six), builder, "REPORT_TOO_MANY_METADATA_FILTERS");

        Filter[] five = java.util.Arrays.copyOf(six, 5);
        assertThat(build(def(five)).sql()).isNotBlank();

        ReportQuery relaxed = builder.build(TENANT, admin, def(six), catalogue, new Limits(25, 40, 6), ZoneId.of("UTC"),
                NOW, false);
        assertThat(relaxed.sql()).isNotBlank();
    }

    @Test
    void filterAndColumnCapsApply() {
        List<Filter> many = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            many.add(f("title", "contains", "v" + i));
        }
        assertRefused(new ReportDefinition("documents", many, List.of("title"), List.of(), null, null, null), builder,
                "REPORT_TOO_MANY_FILTERS");

        List<String> columns = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            columns.add("title");
        }
        assertRefused(new ReportDefinition("documents", List.of(), columns, List.of(), null, null, null), builder,
                "REPORT_TOO_MANY_COLUMNS");
    }

    @Test
    void atMostThreeSortKeys_andOneGroupBy_andAtLeastOneColumn() {
        List<Sort> four = List.of(new Sort("title", "asc"), new Sort("status", "asc"), new Sort("owner", "asc"),
                new Sort("createdAt", "desc"));
        assertRefused(new ReportDefinition("documents", List.of(), List.of("title"), four, null, null, null), builder,
                "REPORT_SORT_INVALID");

        assertRefused(new ReportDefinition("documents", List.of(), List.of(), List.of(), null, null, null), builder,
                "REPORT_COLUMNS_REQUIRED");
    }

    // ---- refusals

    @Test
    void aFieldTheCatalogueDoesNotOfferIsRefused_whetherUnknownOrSwitchedOffByTheAdministrator() {
        assertRefused(def(f("password_hash", "equals", "x")), builder, "REPORT_FIELD_UNAVAILABLE");

        ReportFieldSetting off = ReportFieldSetting.builder().tenantId(TENANT).fieldKey("owner").enabled(false).build();
        EffectiveCatalogue cat = ReportFieldCatalog.merge(List.of(), List.of(off));

        assertThat(cat.contains("owner")).isFalse();
        assertThatThrownBy(() -> build(def(f("owner", "is_empty")), admin, cat, ZoneId.of("UTC"), false))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getCode()).isEqualTo("REPORT_FIELD_UNAVAILABLE"));
        assertThatThrownBy(() -> build(new ReportDefinition("documents", List.of(), List.of("owner"), List.of(), null, null, null),
                admin, cat, ZoneId.of("UTC"), false))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getCode()).isEqualTo("REPORT_FIELD_UNAVAILABLE"));
    }

    @Test
    void anAdminCanTurnOffFilteringSortingOrGroupingOfAField_withoutHidingIt() {
        ReportFieldSetting s = ReportFieldSetting.builder().tenantId(TENANT).fieldKey("status").filterable(false)
                .sortable(false).groupable(false).build();
        EffectiveCatalogue cat = ReportFieldCatalog.merge(List.of(), List.of(s));

        assertThat(cat.contains("status")).isTrue();
        assertThatThrownBy(() -> build(def(f("status", "is_any_of", "draft")), admin, cat, ZoneId.of("UTC"), false))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getCode()).isEqualTo("REPORT_FIELD_UNAVAILABLE"));
        assertThatThrownBy(() -> build(new ReportDefinition("documents", List.of(), List.of("status"),
                List.of(new Sort("status", "asc")), null, null, null), admin, cat, ZoneId.of("UTC"), false))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getCode()).isEqualTo("REPORT_SORT_INVALID"));
        assertThatThrownBy(() -> build(new ReportDefinition("documents", List.of(), List.of("status"), List.of(), "status",
                null, null), admin, cat, ZoneId.of("UTC"), false))
                .isInstanceOfSatisfying(ReportException.class, e -> assertThat(e.getCode()).isEqualTo("REPORT_GROUP_INVALID"));
    }

    @Test
    void anOperatorTheTypeDoesNotHaveIsRefused() {
        assertRefused(def(f("pageCount", "contains", "1")), builder, "REPORT_OPERATOR_INVALID");
        assertRefused(def(f("title", "gt", "1")), builder, "REPORT_OPERATOR_INVALID");
        assertRefused(def(f("legalHold", "equals", "true")), builder, "REPORT_OPERATOR_INVALID");
        assertRefused(def(f("title", "no_such_operator", "1")), builder, "REPORT_OPERATOR_INVALID");
    }

    @Test
    void valuesMustFitTheOperatorAndType() {
        assertRefused(def(f("pageCount", "between", "1")), builder, "REPORT_VALUE_INVALID");
        assertRefused(def(f("pageCount", "eq", "abc")), builder, "REPORT_VALUE_INVALID");
        assertRefused(def(f("title", "contains")), builder, "REPORT_VALUE_INVALID");
        assertRefused(def(f("createdAt", "on", "03/10/2024")), builder, "REPORT_VALUE_INVALID");
        assertRefused(def(f("owner", "is_any_of", "not-a-uuid")), builder, "REPORT_VALUE_INVALID");
        assertRefused(def(f("legalHold", "is_true", "yes")), builder, "REPORT_VALUE_INVALID");
        assertRefused(def(f("createdAt", "between", "2024-02-02", "2024-02-01")), builder, "REPORT_VALUE_INVALID");
    }

    @Test
    void onlyTheDocumentsSourceExists() {
        assertRefused(new ReportDefinition("invoices", List.of(), List.of("title"), List.of(), null, null, null), builder,
                "REPORT_SOURCE_UNSUPPORTED");
    }

    // ---- parameterisation

    @Test
    void userInputNeverAppearsInTheSql_onlyAsBoundParameters() {
        String attack = "x'; drop table documents; --";
        ReportDefinition d = new ReportDefinition("documents",
                List.of(f("title", "contains", attack), f("title", "equals", attack), f("fileType", "starts_with", attack),
                        f("meta.contractRef", "contains", attack), f("status", "is_any_of", attack),
                        f("workflowStage", "is_any_of", attack)),
                List.of("title", "meta.contractRef"), List.of(new Sort("title", "desc")), null, null, null);

        ReportQuery q = build(d);

        assertThat(q.sql()).doesNotContain("drop table").doesNotContain("x'");
        assertThat(q.countSql()).doesNotContain("drop table").doesNotContain("x'");
        assertThat(q.params().values().toString()).contains("drop table");
    }

    @Test
    void sortKeysAndColumnsAreResolvedThroughTheCatalogue_neverInterpolatedFromTheRequest() {
        ReportDefinition d = new ReportDefinition("documents", List.of(), List.of("title"),
                List.of(new Sort("title; drop table documents", "asc")), null, null, null);

        assertRefused(d, builder, "REPORT_FIELD_UNAVAILABLE");
        assertRefused(new ReportDefinition("documents", List.of(), List.of("title"),
                List.of(new Sort("title", "asc; drop table documents")), null, null, null), builder, "REPORT_SORT_INVALID");
    }

    // ---- columns, sort, group

    @Test
    void columnsKeepTheirOrder_andEachGetsItsOwnAlias() {
        ReportDefinition d = new ReportDefinition("documents", List.of(),
                List.of("owner", "title", "createdAt"), List.of(), null, null, null);

        ReportQuery q = build(d);

        assertThat(q.columns()).extracting(ReportQuery.Column::key).containsExactly("owner", "title", "createdAt");
        assertThat(q.columns()).extracting(ReportQuery.Column::alias).containsExactly("c0", "c1", "c2");
        assertThat(norm(q.sql())).startsWith("select d.document_id as doc_id, own.display_name as c0, d.title as c1,"
                + " d.created_at as c2 from documents d");
    }

    @Test
    void theFolderPathColumnSelectsTheFolderId_andIsBuiltFromTheCallersVisibleFoldersLater() {
        ReportDefinition d = new ReportDefinition("documents", List.of(), List.of("folderPath"), List.of(), null, null, null);

        assertThat(norm(build(d).sql())).contains("d.folder_id as c0");
        assertRefused(def(f("folderPath", "contains", "x")), builder, "REPORT_FIELD_UNAVAILABLE");
    }

    @Test
    void sortKeysApplyInOrder_withNullsLast_andADocumentIdTiebreakerSoPagingIsStable() {
        ReportDefinition d = new ReportDefinition("documents", List.of(), List.of("title"),
                List.of(new Sort("createdAt", "desc"), new Sort("title", null)), null, null, null);

        assertThat(norm(build(d).sql())).endsWith(
                "order by d.created_at desc nulls last, d.title asc nulls last, d.document_id");
    }

    @Test
    void groupedReportsOrderByTheGroupFirst_andGetAGroupCountQueryOverTheSameRows() {
        ReportDefinition d = new ReportDefinition("documents", List.of(f("legalHold", "is_false")), List.of("title"),
                List.of(new Sort("title", "asc")), "status", null, null);

        ReportQuery q = build(d);

        assertThat(norm(q.sql())).contains("order by d.status asc nulls last, d.title asc nulls last, d.document_id");
        assertThat(q.groupByKey()).isEqualTo("status");
        assertThat(norm(q.groupCountSql())).startsWith("select d.status as g, count(*) as n from documents d");
        assertThat(norm(q.groupCountSql())).contains("d.legal_hold is false").endsWith("group by 1");
        assertThat(norm(q.groupCountSql())).contains("d.tenant_id = :tenant");
    }

    @Test
    void ungroupedReportsHaveNoGroupCountQuery() {
        assertThat(build(def()).groupCountSql()).isNull();
        assertThat(build(def()).groupByKey()).isNull();
    }

    @Test
    void theCountQueryJoinsOnlyWhatTheFiltersNeed() {
        ReportDefinition d = new ReportDefinition("documents", List.of(f("legalHold", "is_true")),
                List.of("owner", "documentType", "versionNumber"), List.of(), null, null, null);

        ReportQuery q = build(d);

        assertThat(norm(q.sql())).contains("left join users own");
        assertThat(norm(q.countSql())).doesNotContain("join").startsWith("select count(*) from documents d where");
    }

    @Test
    void resolvedFiltersAreHumanReadable_andNeverListRawIds() {
        UUID owner = UUID.randomUUID();
        ReportQuery q = build(def(f("title", "contains", "Annual"), f("owner", "is_any_of", owner.toString()),
                f("pageCount", "between", "1", "5"), f("legalHold", "is_true"),
                f("createdAt", "between", "2024-01-01", "2024-01-31")));

        assertThat(q.resolvedFilters()).containsExactly(
                "Title: contains \"Annual\"",
                "Owner: is any of 1 selected value",
                "Page count: between 1 and 5",
                "Legal hold: is true",
                "Created at: between 2024-01-01 and 2024-01-31");
        assertThat(String.join(" ", q.resolvedFilters())).doesNotContain(owner.toString());
    }
}
