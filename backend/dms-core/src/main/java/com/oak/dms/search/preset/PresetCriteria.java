package com.oak.dms.search.preset;

import com.oak.dms.search.api.SearchApiException;
import com.oak.dms.search.query.SearchRequest;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The criteria a preset stores: exactly the search request's filters, minus paging ({@code size}, {@code cursor}).
 * Parsing is strict (unknown keys are refused), so a preset can never carry anything that is not a search criterion: no
 * document id, no result data. Version 1 of the stored shape; see {@link PresetCriteriaMigrator}.
 */
public record PresetCriteria(String q, UUID documentTypeId, UUID departmentId, UUID ownerId, Instant createdFrom,
                             Instant createdTo, Instant modifiedFrom, Instant modifiedTo, List<String> statuses,
                             UUID folderId, boolean includeSubfolders, List<SearchRequest.MetaFilter> meta, String sort,
                             boolean ascending) {

    public static final int CURRENT_VERSION = 1;

    /** Never the values: keywords and filters are the user's own and must not reach a log line. */
    @Override
    public String toString() {
        return "PresetCriteria" + fieldNames();
    }

    static final Set<String> KEYS = Set.of("schemaVersion", "q", "documentTypeId", "departmentId", "ownerId", "createdFrom",
            "createdTo", "modifiedFrom", "modifiedTo", "statuses", "folderId", "includeSubfolders", "meta", "sort", "ascending");
    private static final Set<String> SORTS = Set.of("relevance", "title", "created", "modified");
    private static final Set<String> OPS = Set.of("eq", "gte", "lte", "contains");
    private static final Set<String> ACTIVE_ONLY = Set.of("active");

    /** Parses and validates a client- or database-supplied map. Throws 400 PRESET_INVALID for anything out of shape. */
    public static PresetCriteria parse(Map<String, Object> m) {
        if (m == null) {
            throw invalid("Criteria are required.");
        }
        for (String key : m.keySet()) {
            if (!KEYS.contains(key)) {
                throw invalid("Unknown criterion '" + key + "'.");
            }
        }
        String q = text(m, "q");
        String sort = text(m, "sort");
        if (sort != null && !SORTS.contains(sort)) {
            throw invalid("Unknown sort '" + sort + "'.");
        }
        List<String> statuses = new ArrayList<>();
        Object st = m.get("statuses");
        if (st != null) {
            if (!(st instanceof List<?> list)) {
                throw invalid("statuses must be a list.");
            }
            for (Object o : list) {
                String s = o == null ? null : o.toString();
                if (s == null || !SearchRequest.STATES.contains(s)) {
                    throw invalid("Unknown status '" + s + "'.");
                }
                if (!statuses.contains(s)) {
                    statuses.add(s);
                }
            }
        }
        List<SearchRequest.MetaFilter> meta = new ArrayList<>();
        Object mv = m.get("meta");
        if (mv != null) {
            if (!(mv instanceof List<?> list)) {
                throw invalid("meta must be a list.");
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> f)) {
                    throw invalid("Each metadata filter must be an object.");
                }
                for (Object k : f.keySet()) {
                    if (!Set.of("fieldId", "op", "value").contains(String.valueOf(k))) {
                        throw invalid("Unknown metadata filter key '" + k + "'.");
                    }
                }
                String op = f.get("op") == null ? null : f.get("op").toString();
                String value = f.get("value") == null ? null : f.get("value").toString();
                if (op == null || !OPS.contains(op) || value == null || value.isBlank()) {
                    throw invalid("A metadata filter needs a field, an operator (eq, gte, lte, contains) and a value.");
                }
                meta.add(new SearchRequest.MetaFilter(uuid(f.get("fieldId"), "fieldId"), op, value));
            }
        }
        Instant cf = instant(m, "createdFrom");
        Instant ct = instant(m, "createdTo");
        Instant mf = instant(m, "modifiedFrom");
        Instant mt = instant(m, "modifiedTo");
        if (cf != null && ct != null && cf.isAfter(ct) || mf != null && mt != null && mf.isAfter(mt)) {
            throw invalid("A date range must start before it ends.");
        }
        Object version = m.get("schemaVersion");
        if (version != null && !(version instanceof Number)) {
            throw invalid("schemaVersion must be a number.");
        }
        return new PresetCriteria(q == null || q.isBlank() ? null : q.strip(), uuid(m.get("documentTypeId"), "documentTypeId"),
                uuid(m.get("departmentId"), "departmentId"), uuid(m.get("ownerId"), "ownerId"), cf, ct, mf, mt, statuses,
                uuid(m.get("folderId"), "folderId"), Boolean.TRUE.equals(m.get("includeSubfolders")), meta, sort,
                Boolean.TRUE.equals(m.get("ascending")));
    }

    /** At least one thing to search for: keywords or a filter. Sorting, the default "active", and subfolders alone do not count. */
    public boolean hasCriteria() {
        return q != null || documentTypeId != null || departmentId != null || ownerId != null || folderId != null
                || createdFrom != null || createdTo != null || modifiedFrom != null || modifiedTo != null
                || !meta.isEmpty() || (!statuses.isEmpty() && !new LinkedHashSet<>(statuses).equals(ACTIVE_ONLY));
    }

    public boolean wantsArchived() {
        return statuses.contains("inactive") || statuses.contains("archived");
    }

    /** The stored/returned form: only what is set, plus the schema version. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", CURRENT_VERSION);
        put(out, "q", q);
        put(out, "documentTypeId", documentTypeId);
        put(out, "departmentId", departmentId);
        put(out, "ownerId", ownerId);
        put(out, "createdFrom", createdFrom);
        put(out, "createdTo", createdTo);
        put(out, "modifiedFrom", modifiedFrom);
        put(out, "modifiedTo", modifiedTo);
        if (!statuses.isEmpty()) {
            out.put("statuses", List.copyOf(statuses));
        }
        put(out, "folderId", folderId);
        if (folderId != null && includeSubfolders) {
            out.put("includeSubfolders", true);
        }
        if (!meta.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (SearchRequest.MetaFilter f : meta) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("fieldId", f.fieldId().toString());
                e.put("op", f.op());
                e.put("value", f.value());
                list.add(e);
            }
            out.put("meta", list);
        }
        put(out, "sort", sort);
        if (ascending) {
            out.put("ascending", true);
        }
        return out;
    }

    /** The names of the criteria in use (never values): what the audit trail records. */
    public List<String> fieldNames() {
        List<String> names = new ArrayList<>(toMap().keySet());
        names.remove("schemaVersion");
        return names;
    }

    /** A short, human summary for the list ("invoice 2024, 3 filters"); the user's own words, no ids. */
    public String summary() {
        int filters = fieldNames().size() - (q != null ? 1 : 0);
        if (sort != null) {
            filters--;
        }
        if (ascending) {
            filters--;
        }
        StringBuilder sb = new StringBuilder();
        if (q != null) {
            sb.append('"').append(q.length() > 60 ? q.substring(0, 60) + "..." : q).append('"');
        }
        if (filters > 0) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(filters).append(filters == 1 ? " filter" : " filters");
        }
        return sb.length() == 0 ? "No criteria" : sb.toString();
    }

    public SearchRequest toRequest(int size) {
        Set<String> states = statuses.isEmpty() ? ACTIVE_ONLY : new LinkedHashSet<>(statuses);
        return new SearchRequest(q, documentTypeId, departmentId, ownerId, createdFrom, createdTo, modifiedFrom, modifiedTo,
                states, folderId, folderId != null && includeSubfolders, List.copyOf(meta),
                sort == null ? "relevance" : sort, ascending, size, null);
    }

    /** What the user's search form holds, after the validator dropped outdated filters (paging is not criteria). */
    public static PresetCriteria fromRequest(SearchRequest r) {
        List<String> statuses = r.statuses() == null ? List.of() : r.statuses().stream().sorted().toList();
        return new PresetCriteria(r.hasKeywords() ? r.q().strip() : null, r.documentTypeId(), r.departmentId(), r.ownerId(),
                r.createdFrom(), r.createdTo(), r.modifiedFrom(), r.modifiedTo(), statuses, r.folderId(),
                r.includeSubfolders(), r.meta() == null ? List.of() : List.copyOf(r.meta()),
                "relevance".equals(r.sort()) ? null : r.sort(), r.ascending());
    }

    public PresetCriteria withoutArchived() {
        List<String> kept = statuses.stream().filter(s -> s.equals("active")).toList();
        return new PresetCriteria(q, documentTypeId, departmentId, ownerId, createdFrom, createdTo, modifiedFrom, modifiedTo,
                kept, folderId, includeSubfolders, meta, sort, ascending);
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value instanceof Instant || value instanceof UUID ? value.toString() : value);
        }
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw invalid(key + " must be text.");
        }
        return s;
    }

    private static UUID uuid(Object v, String key) {
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            throw invalid(key + " is not a valid id.");
        }
    }

    private static Instant instant(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(v.toString());
        } catch (DateTimeParseException e) {
            throw invalid(key + " is not a valid date and time.");
        }
    }

    static SearchApiException invalid(String message) {
        return new SearchApiException(HttpStatus.BAD_REQUEST, "PRESET_INVALID", message);
    }
}
