package com.oak.dms.search.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.index.SearchSourceRepository.FieldDef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The query clauses, with no engine: layer 1 is always there, user text cannot reach query syntax, filters become filters. */
class SearchQueryBuilderTest {

    private final SearchQueryBuilder builder = new SearchQueryBuilder(new SearchProperties());
    private final UUID tenant = UUID.randomUUID();

    private static SearchRequest request(String q) {
        return new SearchRequest(q, null, null, null, null, null, null, null, Set.of("active"), null, false, List.of(), "relevance", false, 20, null);
    }

    private ObjectNode query(SearchRequest r) {
        return builder.query(tenant, r, List.of("active"), Map.of());
    }

    @Test
    void layerOneIsAlwaysInTheFilter_whateverTheUserAsked() {
        for (SearchRequest r : List.of(request(null), request("zebra"), request("tenant_id:other OR *"))) {
            String filter = query(r).path("bool").path("filter").toString();
            assertThat(filter).contains("\"tenant_id\":\"" + tenant + "\"").contains("\"is_deleted\":false")
                    .contains("\"purged\":false").contains("\"is_template\":false").contains("\"lifecycle_state\":[\"active\"]");
        }
    }

    @Test
    void userTextIsOnlyEverTheQueryStringOfASafeQueryOverNamedFields() {
        JsonNode sqs = query(request("title:secret OR tenant_id:*")).path("bool").path("must").get(0).path("simple_query_string");
        assertThat(sqs.path("lenient").asBoolean()).isTrue();
        assertThat(sqs.path("default_operator").asText()).isEqualTo("and");
        assertThat(sqs.path("flags").asText()).doesNotContain("FUZZY").doesNotContain("NEAR").doesNotContain("SLOP");
        assertThat(sqs.path("fields").toString()).doesNotContain("tenant_id");
        assertThat(query(request("zebra")).toString()).as("the text never appears outside the one query clause").doesNotContain("\"term\":{\"title\"");
    }

    @Test
    void rankingBoostsTitleAndFileNameAboveMetadataAboveBody() {
        JsonNode fields = query(request("zebra")).path("bool").path("must").get(0).path("simple_query_string").path("fields");
        List<String> f = new java.util.ArrayList<>();
        fields.forEach(n -> f.add(n.asText()));
        assertThat(boost(f, "title")).isGreaterThan(boost(f, "metadata_text"));
        assertThat(boost(f, "file_name")).isGreaterThan(boost(f, "metadata_text"));
        assertThat(boost(f, "metadata_text")).isGreaterThan(boost(f, "body"));
        assertThat(query(request("zebra")).path("bool").path("should")).as("whole-phrase boosts for title and file name").hasSize(2);
    }

    private static float boost(List<String> fields, String name) {
        return fields.stream().filter(s -> s.startsWith(name + "^")).map(s -> Float.parseFloat(s.substring(s.indexOf('^') + 1))).findFirst().orElseThrow();
    }

    @Test
    void noKeywordsMeansMatchAll() {
        assertThat(query(request(null)).path("bool").path("must").get(0).has("match_all")).isTrue();
        assertThat(query(request("   ")).path("bool").path("must").get(0).has("match_all")).isTrue();
    }

    @Test
    void theLastWordMatchesAsAWordOrAPrefix_otherFormsAreLeftAsTyped() {
        assertThat(SearchQueryBuilder.prepare("shared")).isEqualTo("(shared | shared*)");
        assertThat(SearchQueryBuilder.prepare("annual budg")).isEqualTo("annual (budg | budg*)");
        assertThat(SearchQueryBuilder.prepare("\"annual budget\"")).isEqualTo("\"annual budget\"");
        assertThat(SearchQueryBuilder.prepare("budget -annual")).isEqualTo("budget -annual");
        assertThat(SearchQueryBuilder.prepare("budg*")).isEqualTo("budg*");
        assertThat(SearchQueryBuilder.prepare("a")).as("one letter is not worth a prefix").isEqualTo("a");
        assertThat(SearchQueryBuilder.prepare("\"unterminated")).as("an unterminated phrase is closed").isEqualTo("\"unterminated\"");
        assertThat(SearchQueryBuilder.plain("\"Annual\" budget -x (y)")).isEqualTo("Annual budget x y");
    }

    @Test
    void filtersBecomeFilterClauses() {
        UUID type = UUID.randomUUID();
        UUID dept = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID folder = UUID.randomUUID();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        SearchRequest r = new SearchRequest(null, type, dept, owner, from, null, null, from, Set.of("active", "archived"), folder, true,
                List.of(), "title", true, 20, null);
        String f = builder.query(tenant, r, List.of("active", "archived"), Map.of()).path("bool").path("filter").toString();
        assertThat(f).contains("\"document_type_id\":\"" + type + "\"").contains("\"owner_department_id\":\"" + dept + "\"")
                .contains("\"owner_id\":\"" + owner + "\"").contains("\"ancestor_folder_ids\":\"" + folder + "\"")
                .contains("\"created_at\":{\"gte\":\"2026-01-01T00:00:00Z\"}").contains("\"updated_at\":{\"lte\":\"2026-01-01T00:00:00Z\"}")
                .contains("\"lifecycle_state\":[\"active\",\"archived\"]");
        SearchRequest direct = new SearchRequest(null, null, null, null, null, null, null, null, Set.of("active"), folder, false,
                List.of(), "relevance", false, 20, null);
        assertThat(query(direct).path("bool").path("filter").toString()).contains("\"folder_id\":\"" + folder + "\"").doesNotContain("ancestor");
    }

    @Test
    void metadataFiltersAreTypedAndNested() {
        UUID amount = UUID.randomUUID();
        UUID memo = UUID.randomUUID();
        Map<UUID, FieldDef> defs = Map.of(amount, new FieldDef(amount, "amount", "number", true, "active"),
                memo, new FieldDef(memo, "memo", "text", true, "active"));
        SearchRequest r = new SearchRequest(null, null, null, null, null, null, null, null, Set.of("active"), null, false,
                List.of(new SearchRequest.MetaFilter(amount, "gte", "1000"), new SearchRequest.MetaFilter(memo, "contains", "50%*off?")),
                "relevance", false, 20, null);
        String f = builder.query(tenant, r, List.of("active"), defs).path("bool").path("filter").toString();
        assertThat(f).contains("\"nested\"").contains("\"metadata.v_num\":{\"gte\":1000.0}").contains("\"metadata.field_id\":\"" + amount + "\"");
        assertThat(f).contains("\"case_insensitive\":true").contains("50%\\\\*off\\\\?").as("wildcard characters in user text are escaped");
    }

    @Test
    void sortAlwaysEndsWithADocumentIdTiebreakerSoPagingIsStable() {
        for (String sort : List.of("relevance", "title", "created", "modified")) {
            SearchRequest r = new SearchRequest("x", null, null, null, null, null, null, null, Set.of("active"), null, false, List.of(), sort, true, 20, null);
            JsonNode s = builder.sort(r);
            assertThat(s.get(s.size() - 1).has("document_id")).as(sort).isTrue();
        }
        assertThat(builder.sort(request("x")).get(0).has("_score")).isTrue();
        assertThat(builder.sort(request(null)).get(0).has("_score")).as("no keywords, no score").isFalse();
    }

    @Test
    void candidatesNeverAskForSourceOrATotal_andHighlightsOnlyForGivenIds() {
        ObjectNode body = builder.candidates(query(request("x")), request("x"), 20, null);
        assertThat(body.path("_source").asBoolean(true)).isFalse();
        assertThat(body.path("track_total_hits").asBoolean(true)).isFalse();
        ObjectNode h = builder.highlights(query(request("x")), List.of("id-1", "id-2"), request("x"));
        assertThat(h.path("query").path("bool").path("filter").toString()).contains("\"ids\"").contains("id-1").contains("id-2");
        assertThat(h.path("highlight").path("encoder").asText()).isEqualTo("html");
        assertThat(h.path("size").asInt()).isEqualTo(2);
    }
}
