package com.oak.dms.reporting.datasource;

import com.oak.dms.common.sql.LikePatterns;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.catalog.FieldDescriptor.ValueKind;
import com.oak.dms.reporting.catalog.Operator;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ReportDefinition.Filter;
import com.oak.dms.reporting.dto.ReportDefinition.Sort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The ONLY place SQL for documents is built. It takes the tenant, the caller's {@link DocumentAccessView}, a definition and the
 * effective catalogue, and returns a parameterised {@link ReportQuery}.
 * <ul>
 *   <li><b>No user input in SQL.</b> A field is a catalogue key resolved to an expression that is a code constant (or, for a
 *       custom metadata field, derived from its data type with the field id bound as a parameter). Values, dates and
 *       patterns are always bound parameters; operators, directions and join sets come from closed lists.</li>
 *   <li><b>One access predicate.</b> Row scope is pushed into SQL the way {@code DashboardService} does it, plus documents a
 *       running approval lets the caller read ({@code workflowReadableIds()}). The count and group-count statements use the
 *       same predicate, so no total can include a row the caller cannot see.</li>
 *   <li>Always excluded: templates, recycle-bin documents and purged tombstones.</li>
 * </ul>
 */
@Component
public class DocumentReportQueryBuilder {

    /** The caps from {@code dms.reports} (filters, columns, metadata filters - metadata filters read JSON, so they are capped). */
    public record Limits(int maxFilters, int maxColumns, int maxMetadataFilters) {
    }

    private static final int MAX_SORT_KEYS = 3;
    private static final int MAX_VALUES = 1000;
    private static final int MAX_TEXT_LENGTH = 500;

    private static final String ALWAYS = "d.tenant_id = :tenant and not d.is_deleted and not d.is_template"
            + " and d.purged_at is null";

    /** Row scope. Mirrors {@code DashboardService.VISIBLE}, plus documents a running approval lets the caller read. */
    private static final String ACCESS = "(:everything"
            + " or (d.access_mode = 'inherit' and (d.folder_id is null or d.folder_id not in (:hidden)))"
            + " or (d.access_mode = 'restricted' and d.document_id in (:granted))"
            + " or d.document_id in (:wfReadable))";

    /** Every join is a LEFT join, so a document with no type, department, owner or version still shows (brief 2.1). */
    private static final Map<String, String> JOINS = new LinkedHashMap<>();

    static {
        JOINS.put("type", "left join document_types t on t.document_type_id = d.document_type_id");
        JOINS.put("dept", "left join departments dep on dep.department_id = d.owner_department_id");
        JOINS.put("owner", "left join users own on own.user_id = d.owner_user_id and own.tenant_id = d.tenant_id");
        JOINS.put("creator", "left join users cre on cre.user_id = d.created_by and cre.tenant_id = d.tenant_id");
        JOINS.put("modifier", "left join users upd on upd.user_id = d.updated_by and upd.tenant_id = d.tenant_id");
        JOINS.put("version", "left join document_versions v on v.version_id = coalesce(d.current_version_id, d.latest_version_id)");
        JOINS.put("wf", "left join lateral (select i.status, i.current_stage_instance_id from workflow_instances i"
                + " where i.tenant_id = d.tenant_id and i.document_id = d.document_id and not i.is_deleted"
                + " order by i.started_at desc limit 1) wf on true");
        JOINS.put("stage", "left join workflow_stage_instances si on si.stage_instance_id = wf.current_stage_instance_id"
                + " left join workflow_stages stg on stg.stage_id = si.stage_id");
        JOINS.put("meta", "left join document_metadata dm on dm.tenant_id = d.tenant_id and dm.document_id = d.document_id");
    }

    /** One query's working state. */
    private static final class Ctx {
        final Map<String, Object> params = new LinkedHashMap<>();
        final Map<UUID, String> metaParams = new LinkedHashMap<>();
        final Set<String> joins = new LinkedHashSet<>();
        final ZoneId zone;
        final LocalDate today;
        int seq;

        Ctx(ZoneId zone, LocalDate today) {
            this.zone = zone;
            this.today = today;
        }

        String bind(Object value) {
            String name = "p" + seq++;
            params.put(name, value);
            return name;
        }

        String metaKey(UUID fieldId) {
            return metaParams.computeIfAbsent(fieldId, id -> {
                String name = "mk" + metaParams.size();
                params.put(name, id.toString());
                return name;
            });
        }
    }

    public ReportQuery build(UUID tenantId, DocumentAccessView access, ReportDefinition def, EffectiveCatalogue catalogue,
                             Limits limits, ZoneId zone, Instant now, boolean includeInactiveDefault) {
        if (def.source() != null && !"documents".equals(def.source())) {
            throw bad("REPORT_SOURCE_UNSUPPORTED", "Only reports on documents are available.", "source");
        }
        List<String> columnKeys = def.columnsOrEmpty();
        if (columnKeys.isEmpty()) {
            throw bad("REPORT_COLUMNS_REQUIRED", "Choose at least one column.", "columns");
        }
        if (columnKeys.size() > limits.maxColumns()) {
            throw bad("REPORT_TOO_MANY_COLUMNS", "A report can have at most " + limits.maxColumns() + " columns.", "columns");
        }
        List<Filter> filters = def.filtersOrEmpty();
        if (filters.size() > limits.maxFilters()) {
            throw bad("REPORT_TOO_MANY_FILTERS", "A report can have at most " + limits.maxFilters() + " filters.", "filters");
        }
        List<Sort> sorts = def.sortOrEmpty();
        if (sorts.size() > MAX_SORT_KEYS) {
            throw bad("REPORT_SORT_INVALID", "A report can be sorted by at most " + MAX_SORT_KEYS + " columns.", "sort");
        }
        long metadataFilters = filters.stream().filter(f -> f.field() != null && f.field().startsWith(FieldDescriptor.META_PREFIX)).count();
        if (metadataFilters > limits.maxMetadataFilters()) {
            throw bad("REPORT_TOO_MANY_METADATA_FILTERS", "A report can filter on at most " + limits.maxMetadataFilters()
                    + " custom fields.", "filters");
        }

        Ctx c = new Ctx(zone, now.atZone(zone).toLocalDate());
        c.params.put("tenant", tenantId);
        c.params.put("everything", access.seesEverything());
        c.params.put("hidden", nonEmpty(access.folders().hiddenFolderIds()));
        c.params.put("granted", access.grantedIds());
        c.params.put("wfReadable", access.workflowReadableIds());

        // Filters first: the count statement joins only what they need.
        List<String> where = new ArrayList<>();
        List<String> described = new ArrayList<>();
        boolean filtersLifecycle = false;
        for (int i = 0; i < filters.size(); i++) {
            Filter filter = filters.get(i);
            String path = "filters[" + i + "]";
            FieldDescriptor field = require(catalogue, filter.field(), path + ".field");
            if (!field.filterable()) {
                throw unavailable(path + ".field");
            }
            filtersLifecycle |= "lifecycleState".equals(field.key());
            where.add(filterSql(field, filter, c, path, described));
        }
        Set<String> filterJoins = new LinkedHashSet<>(c.joins);

        boolean includeInactive = def.includeInactive() != null ? def.includeInactive() : includeInactiveDefault;
        String lifecycle = includeInactive || filtersLifecycle ? "" : " and d.lifecycle_state = 'active'";
        String whereSql = ALWAYS + " and " + ACCESS + lifecycle + where.stream().map(w -> " and " + w).collect(Collectors.joining());

        // Columns, in the order requested.
        List<ReportQuery.Column> columns = new ArrayList<>();
        List<String> selects = new ArrayList<>();
        for (int i = 0; i < columnKeys.size(); i++) {
            FieldDescriptor field = require(catalogue, columnKeys.get(i), "columns[" + i + "]");
            if (!field.selectable()) {
                throw unavailable("columns[" + i + "]");
            }
            String alias = "c" + i;
            selects.add(valueExpr(field, c, false) + " as " + alias);
            columns.add(new ReportQuery.Column(field.key(), field.label(), field.type(), field.valueKind(), field.multi(),
                    field.special(), alias));
        }

        // Group first, then the sort keys.
        List<String> orderBy = new ArrayList<>();
        String groupExpr = null;
        String groupKey = null;
        ReportQuery.Column groupColumn = null;
        if (def.groupBy() != null && !def.groupBy().isBlank()) {
            FieldDescriptor group = require(catalogue, def.groupBy(), "groupBy");
            if (!group.groupable()) {
                throw bad("REPORT_GROUP_INVALID", "This report can't be grouped by " + group.label() + ".", "groupBy");
            }
            groupKey = group.key();
            groupExpr = valueExpr(group, c, false);
            orderBy.add(groupExpr + " asc nulls last");
            // Selected on every row (as "grp") so the file can print a group header when the value changes, whether or not
            // the group-by field is one of the columns.
            selects.add(groupExpr + " as grp");
            groupColumn = new ReportQuery.Column(group.key(), group.label(), group.type(), group.valueKind(), group.multi(),
                    group.special(), "grp");
        }
        for (int i = 0; i < sorts.size(); i++) {
            Sort sort = sorts.get(i);
            String path = "sort[" + i + "]";
            FieldDescriptor field = require(catalogue, sort.field(), path + ".field");
            if (!field.sortable()) {
                throw bad("REPORT_SORT_INVALID", "This report can't be sorted by " + field.label() + ".", path + ".field");
            }
            String direction = sort.direction() == null ? "asc" : sort.direction().toLowerCase(Locale.ROOT);
            if (!direction.equals("asc") && !direction.equals("desc")) {
                throw bad("REPORT_SORT_INVALID", "Sort direction must be ascending or descending.", path + ".direction");
            }
            orderBy.add(valueExpr(field, c, false) + " " + direction + " nulls last");
        }
        orderBy.add("d.document_id");

        String sql = "select d.document_id as doc_id, " + String.join(", ", selects) + " from documents d"
                + joinSql(c.joins) + " where " + whereSql + " order by " + String.join(", ", orderBy);
        String countSql = "select count(*)" + " from documents d" + joinSql(filterJoins) + " where " + whereSql;
        String groupCountSql = null;
        if (groupExpr != null) {
            Set<String> groupJoins = new LinkedHashSet<>(filterJoins);
            catalogue.find(groupKey).ifPresent(g -> groupJoins.addAll(g.joins()));
            if (catalogue.find(groupKey).map(FieldDescriptor::isMetadata).orElse(false)) {
                groupJoins.add("meta");
            }
            groupCountSql = "select " + groupExpr + " as g, count(*) as n from documents d" + joinSql(groupJoins)
                    // By ordinal: a custom field's expression carries bound parameters, and the same text with two
                    // placeholders is not "the same expression" to PostgreSQL's GROUP BY check.
                    + " where " + whereSql + " group by 1";
        }

        return new ReportQuery(sql, countSql, groupCountSql, c.params, List.copyOf(columns), groupKey, groupColumn,
                List.copyOf(described), !access.seesEverything(), includeInactive);
    }

    // ---- expressions

    private static String joinSql(Set<String> needed) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> join : JOINS.entrySet()) {
            if (needed.contains(join.getKey())) {
                sb.append(' ').append(join.getValue());
            }
        }
        return sb.toString();
    }

    /** What a cell shows and what sorts. A metadata field gets a typed, defensive expression. */
    private String valueExpr(FieldDescriptor f, Ctx c, boolean forFilter) {
        if (f.isMetadata()) {
            c.joins.add("meta");
            return metaTyped(f, c);
        }
        c.joins.addAll(f.joins());
        return forFilter && f.filterExpr() != null ? f.filterExpr() : f.selectExpr();
    }

    private static String metaText(String key) {
        return "dm.field_values #>> array[cast(:" + key + " as text), 'v']";
    }

    private static String metaRaw(String key) {
        return "dm.field_values -> cast(:" + key + " as text) -> 'v'";
    }

    /**
     * A custom field's value as the type it should be, NULL when the stored JSON does not fit (a malformed value must not
     * fail the whole report). The field id is bound, never written into the SQL.
     */
    private static String metaTyped(FieldDescriptor f, Ctx c) {
        String k = c.metaKey(f.metadataFieldId());
        return switch (f.metadataDataType()) {
            case "number", "decimal" -> "case when jsonb_typeof(" + metaRaw(k) + ") = 'number' then ("
                    + metaText(k) + ")::numeric end";
            case "date", "datetime" -> "case when (" + metaText(k) + ") ~ '^\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])'"
                    + " then left(" + metaText(k) + ", 10)::date end";
            case "boolean" -> "case when jsonb_typeof(" + metaRaw(k) + ") = 'boolean' then ("
                    + metaText(k) + ")::boolean end";
            default -> metaText(k);
        };
    }

    // ---- filters

    private String filterSql(FieldDescriptor f, Filter filter, Ctx c, String path, List<String> described) {
        Operator op = Operator.fromKey(filter.operator())
                .filter(o -> f.type().allows(o))
                .orElseThrow(() -> bad("REPORT_OPERATOR_INVALID",
                        "\"" + filter.operator() + "\" can't be used on " + f.label() + ".", path + ".operator"));
        List<String> values = filter.values() == null ? List.of() : filter.values();
        checkArity(op, values, path);

        String sql;
        switch (f.type()) {
            case TEXT -> sql = textSql(f, op, values, c, described);
            case CHOICE, REFERENCE -> sql = choiceSql(f, op, values, c, path, described);
            case NUMBER -> sql = numberSql(f, op, values, c, path, described);
            case DATE -> sql = dateSql(f, op, values, c, path, described);
            case BOOLEAN -> {
                String expr = valueExpr(f, c, true);
                sql = expr + (op == Operator.IS_TRUE ? " is true" : " is false");
                described.add(f.label() + ": " + (op == Operator.IS_TRUE ? "is true" : "is false"));
            }
            default -> throw unavailable(path + ".field");
        }
        return sql;
    }

    private static void checkArity(Operator op, List<String> values, String path) {
        int n = values.size();
        boolean ok = switch (op.arity()) {
            case 0 -> n == 0;
            case 1 -> n == 1;
            case 2 -> n == 2;
            default -> n >= 1 && n <= MAX_VALUES;
        };
        if (!ok || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > MAX_TEXT_LENGTH)) {
            throw bad("REPORT_VALUE_INVALID", "Check the value for this filter.", path + ".values");
        }
    }

    private String textSql(FieldDescriptor f, Operator op, List<String> values, Ctx c, List<String> described) {
        String expr = valueExpr(f, c, true);
        String label = f.label() + ": ";
        switch (op) {
            case CONTAINS -> {
                described.add(label + "contains \"" + values.get(0) + "\"");
                return "lower(" + expr + ") like :" + c.bind(LikePatterns.contains(values.get(0)));
            }
            case STARTS_WITH -> {
                described.add(label + "starts with \"" + values.get(0) + "\"");
                return "lower(" + expr + ") like :" + c.bind(LikePatterns.escape(values.get(0).toLowerCase(Locale.ROOT)) + "%");
            }
            case EQUALS -> {
                described.add(label + "equals \"" + values.get(0) + "\"");
                return "lower(" + expr + ") = :" + c.bind(values.get(0).toLowerCase(Locale.ROOT));
            }
            default -> {
                described.add(label + "is empty");
                return "(" + expr + " is null or " + expr + " = '')";
            }
        }
    }

    private String choiceSql(FieldDescriptor f, Operator op, List<String> values, Ctx c, String path, List<String> described) {
        String label = f.label() + ": ";
        if (f.special() == Special.STAGE) {
            return stageSql(f, op, values, c, described);
        }
        if (f.multi()) {
            return multiSql(f, op, values, c, described);
        }
        String expr = valueExpr(f, c, true);
        if (op == Operator.IS_EMPTY) {
            described.add(label + "is empty");
            return f.isMetadata() ? "(" + expr + " is null or " + expr + " = '')" : expr + " is null";
        }
        Collection<?> bound = f.valueKind() == ValueKind.UUID ? parseUuids(values, path) : List.copyOf(values);
        described.add(label + (op == Operator.IS_ANY_OF ? "is any of " : "is none of ") + describeValues(f, values));
        String in = expr + (op == Operator.IS_ANY_OF ? " in (:" : " not in (:") + c.bind(bound) + ")";
        return op == Operator.IS_ANY_OF ? in : "(" + expr + " is null or " + in + ")";
    }

    /** A multi-select custom field: stored as a JSON list, matched by containment. */
    private String multiSql(FieldDescriptor f, Operator op, List<String> values, Ctx c, List<String> described) {
        c.joins.add("meta");
        String k = c.metaKey(f.metadataFieldId());
        String raw = metaRaw(k);
        String label = f.label() + ": ";
        if (op == Operator.IS_EMPTY) {
            described.add(label + "is empty");
            return "coalesce(case when jsonb_typeof(" + raw + ") = 'array' then jsonb_array_length(" + raw + ") end, 0) = 0";
        }
        String any = "(" + values.stream().map(v -> "(" + raw + ") @> to_jsonb(cast(:" + c.bind(v) + " as text))")
                .collect(Collectors.joining(" or ")) + ")";
        described.add(label + (op == Operator.IS_ANY_OF ? "is any of " : "is none of ") + describeValues(f, values));
        return op == Operator.IS_ANY_OF ? any : "not coalesce(" + any + ", false)";
    }

    /** Workflow stage: by name (several templates may share one) or by id, only while the latest instance is running. */
    private String stageSql(FieldDescriptor f, Operator op, List<String> values, Ctx c, List<String> described) {
        c.joins.addAll(f.joins());
        String label = f.label() + ": ";
        if (op == Operator.IS_EMPTY) {
            described.add(label + "is empty");
            return "(wf.status is distinct from 'in_progress' or stg.name is null)";
        }
        List<UUID> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String v : values) {
            try {
                ids.add(UUID.fromString(v.strip()));
            } catch (IllegalArgumentException notAnId) {
                names.add(v.strip().toLowerCase(Locale.ROOT));
            }
        }
        List<String> parts = new ArrayList<>();
        if (!ids.isEmpty()) {
            parts.add("si.stage_id in (:" + c.bind(ids) + ")");
        }
        if (!names.isEmpty()) {
            parts.add("lower(stg.name) in (:" + c.bind(names) + ")");
        }
        String match = "(wf.status = 'in_progress' and (" + String.join(" or ", parts) + "))";
        described.add(label + (op == Operator.IS_ANY_OF ? "is any of " : "is none of ") + describeValues(f, values));
        return op == Operator.IS_ANY_OF ? match : "not coalesce(" + match + ", false)";
    }

    private String numberSql(FieldDescriptor f, Operator op, List<String> values, Ctx c, String path, List<String> described) {
        String expr = valueExpr(f, c, true);
        List<BigDecimal> numbers = new ArrayList<>();
        for (String v : values) {
            try {
                numbers.add(new BigDecimal(v.strip()));
            } catch (NumberFormatException e) {
                throw bad("REPORT_VALUE_INVALID", "\"" + v + "\" is not a number.", path + ".values");
            }
        }
        String label = f.label() + ": ";
        if (op == Operator.BETWEEN) {
            if (numbers.get(0).compareTo(numbers.get(1)) > 0) {
                throw bad("REPORT_VALUE_INVALID", "The first number must not be larger than the second.", path + ".values");
            }
            described.add(label + "between " + numbers.get(0).toPlainString() + " and " + numbers.get(1).toPlainString());
            return expr + " between :" + c.bind(numbers.get(0)) + " and :" + c.bind(numbers.get(1));
        }
        String symbol = switch (op) {
            case EQ -> "=";
            case NE -> "<>";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            default -> ">=";
        };
        described.add(label + symbol + " " + numbers.get(0).toPlainString());
        return expr + " " + symbol + " :" + c.bind(numbers.get(0));
    }

    private String dateSql(FieldDescriptor f, Operator op, List<String> values, Ctx c, String path, List<String> described) {
        String expr = valueExpr(f, c, true);
        String label = f.label() + ": ";
        switch (op) {
            case IS_EMPTY -> {
                described.add(label + "is empty");
                return expr + " is null";
            }
            case RELATIVE -> {
                RelativeDates.Range range = RelativeDates.resolve(values.get(0), c.today).orElseThrow(() -> bad(
                        "REPORT_VALUE_INVALID", "\"" + values.get(0) + "\" is not a date range.", path + ".values"));
                described.add(label + relativeLabel(values.get(0)) + " (" + range.describe() + ")");
                return range(expr, f, range.fromInclusive(), range.toExclusive(), c);
            }
            case ON -> {
                LocalDate day = parseDate(values.get(0), path);
                described.add(label + "on " + day);
                return range(expr, f, day, day.plusDays(1), c);
            }
            case BEFORE -> {
                LocalDate day = parseDate(values.get(0), path);
                described.add(label + "before " + day);
                return expr + " < :" + c.bind(bound(f, day, c));
            }
            case AFTER -> {
                LocalDate day = parseDate(values.get(0), path);
                described.add(label + "after " + day);
                return expr + " >= :" + c.bind(bound(f, day.plusDays(1), c));
            }
            default -> { // BETWEEN, inclusive of the last day
                LocalDate from = parseDate(values.get(0), path);
                LocalDate to = parseDate(values.get(1), path);
                if (from.isAfter(to)) {
                    throw bad("REPORT_VALUE_INVALID", "The start date must not be after the end date.", path + ".values");
                }
                described.add(label + "between " + from + " and " + to);
                return range(expr, f, from, to.plusDays(1), c);
            }
        }
    }

    private String range(String expr, FieldDescriptor f, LocalDate fromInclusive, LocalDate toExclusive, Ctx c) {
        return "(" + expr + " >= :" + c.bind(bound(f, fromInclusive, c)) + " and " + expr + " < :"
                + c.bind(bound(f, toExclusive, c)) + ")";
    }

    /** A timestamp column compares against the instant the day starts in the report's zone; a date compares as a date. */
    private static Object bound(FieldDescriptor f, LocalDate day, Ctx c) {
        return f.valueKind() == ValueKind.DATE ? day : day.atStartOfDay(c.zone).toInstant().atOffset(ZoneOffset.UTC);
    }

    private static LocalDate parseDate(String text, String path) {
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw bad("REPORT_VALUE_INVALID", "Dates must look like 2024-03-31.", path + ".values");
        }
    }

    private static List<UUID> parseUuids(List<String> values, String path) {
        List<UUID> ids = new ArrayList<>();
        for (String v : values) {
            try {
                ids.add(UUID.fromString(v.strip()));
            } catch (IllegalArgumentException e) {
                throw bad("REPORT_VALUE_INVALID", "Check the selected values.", path + ".values");
            }
        }
        return ids;
    }

    // ---- wording

    private static String relativeLabel(String expression) {
        return expression.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** A reference is shown as a count (the ids mean nothing to a reader); a fixed value is shown as it is. */
    private static String describeValues(FieldDescriptor f, List<String> values) {
        if (f.valueKind() == ValueKind.UUID) {
            return values.size() + " selected value" + (values.size() == 1 ? "" : "s");
        }
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
    }

    // ---- lookups and errors

    private static FieldDescriptor require(EffectiveCatalogue catalogue, String key, String path) {
        Optional<FieldDescriptor> field = catalogue.find(key);
        return field.orElseThrow(() -> unavailable(path));
    }

    private static ReportException unavailable(String path) {
        return bad("REPORT_FIELD_UNAVAILABLE", "This field isn't available for reports.", path);
    }

    private static ReportException bad(String code, String message, String path) {
        return ReportException.invalid(code, message, Map.of(path, message));
    }

    private static Collection<UUID> nonEmpty(Set<UUID> ids) {
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : List.copyOf(ids);
    }
}
