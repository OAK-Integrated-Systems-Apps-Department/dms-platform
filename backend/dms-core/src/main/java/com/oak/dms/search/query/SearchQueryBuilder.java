package com.oak.dms.search.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.index.SearchSourceRepository.FieldDef;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the engine query (D3 layer 1, plus ranking). Pure: no I/O, so every clause is unit-testable.
 *
 * <p>Layer 1 is applied here, not left to callers: tenant, not deleted, not purged, not a template, and only the lifecycle
 * states the caller is allowed AND asked for. User text never reaches the engine as query syntax it could abuse:
 * {@code simple_query_string} is lenient (no syntax errors), has no field names or wildcards on fields, and its flags are
 * limited to AND/OR/NOT, phrases and prefixes.
 *
 * <p>Ranking is the boosts in {@link SearchProperties.Query}: title and file name above metadata above body, with extra
 * weight when the whole phrase appears in the title or file name.
 */
@Component
public class SearchQueryBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLAIN = Pattern.compile("[\\p{L}\\p{N}\\s]");

    private final SearchProperties props;

    public SearchQueryBuilder(SearchProperties props) {
        this.props = props;
    }

    /** Sort clause; the last key is always {@code document_id} so {@code search_after} paging is stable. */
    public ArrayNode sort(SearchRequest r) {
        ArrayNode sort = JSON.createArrayNode();
        String dir = r.ascending() ? "asc" : "desc";
        switch (r.sort() == null ? "relevance" : r.sort()) {
            case "title" -> sort.addObject().putObject("title.sort").put("order", dir).put("unmapped_type", "keyword");
            case "created" -> sort.addObject().putObject("created_at").put("order", dir);
            case "modified" -> sort.addObject().putObject("updated_at").put("order", dir);
            default -> {
                if (r.hasKeywords()) {
                    sort.addObject().putObject("_score").put("order", r.ascending() ? "asc" : "desc");
                }
                sort.addObject().putObject("updated_at").put("order", "desc");
            }
        }
        sort.addObject().putObject("document_id").put("order", "asc");
        return sort;
    }

    /**
     * The {@code query} object: keywords (must + phrase boosts) and every filter.
     *
     * @param states  lifecycle states the caller may see AND asked for (never empty)
     * @param fields  tenant field definitions by id, to type the metadata filters
     */
    public ObjectNode query(UUID tenantId, SearchRequest r, List<String> states, Map<UUID, FieldDef> fields) {
        ObjectNode query = JSON.createObjectNode();
        ObjectNode bool = filters(query, tenantId, r, states, fields);

        // Keywords.
        if (r.hasKeywords()) {
            SearchProperties.Query q = props.getQuery();
            ObjectNode sqs = bool.putArray("must").addObject().putObject("simple_query_string");
            sqs.put("query", prepare(r.q()));
            ArrayNode f = sqs.putArray("fields");
            f.add("title^" + q.getTitleBoost());
            f.add("title.stem^" + q.getTitleStemBoost());
            f.add("file_name^" + q.getFileNameBoost());
            f.add("metadata_text^" + q.getMetadataBoost());
            f.add("body^" + q.getBodyBoost());
            sqs.put("default_operator", "and");
            sqs.put("flags", "AND|OR|NOT|PHRASE|PREFIX|PRECEDENCE|ESCAPE|WHITESPACE");
            sqs.put("lenient", true);
            String plain = plain(r.q());
            if (!plain.isBlank()) {
                ArrayNode should = bool.putArray("should");
                should.addObject().putObject("match_phrase").putObject("title").put("query", plain).put("boost", q.getTitlePhraseBoost());
                should.addObject().putObject("match_phrase").putObject("file_name").put("query", plain).put("boost", q.getFileNamePhraseBoost());
            }
        } else {
            bool.putArray("must").addObject().putObject("match_all");
        }
        return query;
    }

    /** The filter part shared by the keyword query and the fuzzy "did you mean" probe. Returns the bool node. */
    private ObjectNode filters(ObjectNode query, UUID tenantId, SearchRequest r, List<String> states, Map<UUID, FieldDef> fields) {
        ObjectNode bool = query.putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        // Layer 1 (D3): always applied, whatever the user asked.
        filter.addObject().putObject("term").put("tenant_id", tenantId.toString());
        filter.addObject().putObject("term").put("is_deleted", false);
        filter.addObject().putObject("term").put("purged", false);
        filter.addObject().putObject("term").put("is_template", false);
        ArrayNode st = filter.addObject().putObject("terms").putArray("lifecycle_state");
        states.forEach(st::add);

        // User filters.
        if (r.documentTypeId() != null) {
            filter.addObject().putObject("term").put("document_type_id", r.documentTypeId().toString());
        }
        if (r.departmentId() != null) {
            filter.addObject().putObject("term").put("owner_department_id", r.departmentId().toString());
        }
        if (r.ownerId() != null) {
            filter.addObject().putObject("term").put("owner_id", r.ownerId().toString());
        }
        if (r.folderId() != null) {
            // ancestor_folder_ids holds the folder itself and every ancestor, so one term covers "with subfolders"
            filter.addObject().putObject("term").put(r.includeSubfolders() ? "ancestor_folder_ids" : "folder_id", r.folderId().toString());
        }
        range(filter, "created_at", r.createdFrom(), r.createdTo());
        range(filter, "updated_at", r.modifiedFrom(), r.modifiedTo());
        if (r.meta() != null) {
            for (SearchRequest.MetaFilter m : r.meta()) {
                FieldDef def = fields.get(m.fieldId());
                if (def != null) {
                    filter.add(metadata(m, def));
                }
            }
        }

        return bool;
    }

    /**
     * The "did you mean" probe (only used when a search found nothing): the same layer-1 and user filters, with the words
     * matched fuzzily against title and file name. Its candidates are verified in PostgreSQL before any word is borrowed.
     */
    public ObjectNode fuzzy(UUID tenantId, SearchRequest r, List<String> states, Map<UUID, FieldDef> fields) {
        ObjectNode query = JSON.createObjectNode();
        ObjectNode bool = filters(query, tenantId, r, states, fields);
        ObjectNode inner = bool.putArray("must").addObject().putObject("bool");
        ArrayNode should = inner.putArray("should");
        String plain = plain(r.q());
        should.addObject().putObject("match").putObject("title").put("query", plain).put("fuzziness", "AUTO").put("operator", "or");
        should.addObject().putObject("match").putObject("file_name").put("query", plain).put("fuzziness", "AUTO").put("operator", "or");
        inner.put("minimum_should_match", 1);
        return query;
    }

    /** The full {@code _search} body for finding candidates: ids and sort values only (no source, no highlights). */
    public ObjectNode candidates(ObjectNode query, SearchRequest r, int size, JsonNode searchAfter) {
        ObjectNode body = JSON.createObjectNode();
        body.set("query", query);
        body.set("sort", sort(r));
        body.put("size", size);
        body.put("_source", false);
        body.put("track_total_hits", false); // the engine's total is never used (D3): do not even compute it
        if (searchAfter != null && searchAfter.isArray() && !searchAfter.isEmpty()) {
            body.set("search_after", searchAfter);
        }
        return body;
    }

    /** The second request (D3): the same query restricted to verified ids, with highlights. Hidden documents are never in it. */
    public ObjectNode highlights(ObjectNode query, List<String> verifiedIds, SearchRequest r) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode bool = body.putObject("query").putObject("bool");
        bool.putArray("must").add(query);
        bool.putArray("filter").addObject().putObject("ids").putArray("values").addAll(toArray(verifiedIds));
        body.put("size", verifiedIds.size());
        body.putArray("_source").add("updated_at").add("legal_hold").add("body_status").add("mime_type").add("file_name");
        if (r.hasKeywords()) {
            SearchProperties.Query q = props.getQuery();
            ObjectNode h = body.putObject("highlight");
            h.putArray("pre_tags").add("<mark>");
            h.putArray("post_tags").add("</mark>");
            h.put("encoder", "html"); // everything else in a snippet is escaped; only <mark> is markup
            ObjectNode fields = h.putObject("fields");
            fields.putObject("title").put("number_of_fragments", 0);
            fields.putObject("file_name").put("number_of_fragments", 0);
            fields.putObject("body").put("number_of_fragments", q.getHighlightFragments()).put("fragment_size", q.getHighlightFragmentSize());
        }
        return body;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static ArrayNode toArray(List<String> values) {
        ArrayNode a = JSON.createArrayNode();
        values.forEach(a::add);
        return a;
    }

    private static void range(ArrayNode filter, String field, Instant from, Instant to) {
        if (from == null && to == null) {
            return;
        }
        ObjectNode r = filter.addObject().putObject("range").putObject(field);
        if (from != null) {
            r.put("gte", from.toString());
        }
        if (to != null) {
            r.put("lte", to.toString());
        }
    }

    private static ObjectNode metadata(SearchRequest.MetaFilter m, FieldDef def) {
        ObjectNode nested = JSON.createObjectNode();
        ObjectNode n = nested.putObject("nested");
        n.put("path", "metadata");
        ArrayNode filter = n.putObject("query").putObject("bool").putArray("filter");
        filter.addObject().putObject("term").put("metadata.field_id", def.fieldId().toString());
        String type = def.dataType();
        boolean numeric = "number".equals(type) || "decimal".equals(type);
        boolean date = "date".equals(type) || "datetime".equals(type);
        switch (m.op()) {
            case "gte", "lte" -> {
                String field = numeric ? "metadata.v_num" : "metadata.v_date";
                ObjectNode range = filter.addObject().putObject("range").putObject(field);
                if (numeric) {
                    range.put(m.op(), Double.parseDouble(m.value()));
                } else {
                    range.put(m.op(), m.value());
                }
            }
            case "contains" -> filter.addObject().putObject("wildcard").putObject("metadata.v_kw")
                    .put("value", "*" + escapeWildcard(m.value()) + "*").put("case_insensitive", true);
            default -> { // eq
                if (numeric) {
                    filter.addObject().putObject("term").put("metadata.v_num", Double.parseDouble(m.value()));
                } else if (date) {
                    filter.addObject().putObject("term").put("metadata.v_date", m.value());
                } else if ("boolean".equals(type)) {
                    filter.addObject().putObject("term").put("metadata.v_bool", Boolean.parseBoolean(m.value()));
                } else {
                    filter.addObject().putObject("term").put("metadata.v_kw", m.value());
                }
            }
        }
        return nested;
    }

    private static String escapeWildcard(String s) {
        return s.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    /**
     * Makes typing feel instant: the last bare word also matches as a prefix ({@code quart} finds "quarterly"). It is
     * written {@code (word | word*)} rather than {@code word*} alone because a wildcard is not analysed: {@code shared*}
     * would never match the stemmed term "share" in the body, while the plain {@code shared} is analysed and does. Quoted
     * phrases and words that already carry an operator are left exactly as typed.
     */
    static String prepare(String raw) {
        String q = raw.strip();
        if (q.isEmpty() || q.endsWith("\"") || q.endsWith("*") || q.endsWith(")")) {
            return q;
        }
        int quotes = (int) q.chars().filter(c -> c == '"').count();
        if (quotes % 2 == 1) { // an unterminated phrase: close it rather than let it swallow the rest
            return q + "\"";
        }
        int space = q.lastIndexOf(' ');
        String last = space < 0 ? q : q.substring(space + 1);
        if (last.length() >= 2 && last.chars().allMatch(Character::isLetterOrDigit)) {
            String head = space < 0 ? "" : q.substring(0, space + 1);
            return head + "(" + last + " | " + last + "*)";
        }
        return q;
    }

    /** Just the words (no operators or quotes), for the whole-phrase boosts. */
    static String plain(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            sb.append(PLAIN.matcher(String.valueOf(c)).matches() ? c : ' ');
        }
        return sb.toString().strip().replaceAll("\\s+", " ");
    }
}
