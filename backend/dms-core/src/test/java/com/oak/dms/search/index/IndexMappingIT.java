package com.oak.dms.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.SearchIntegrationTestSupport;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.OpenSearchRestEngine;
import com.oak.dms.search.engine.SearchEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The mapping and analysers against a real node: scripts, accents, file names, prefix, phrase, typed metadata. */
class IndexMappingIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SearchProperties props;
    private OpenSearchRestEngine engine;
    private IndexAdmin admin;
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SearchIntegrationTestSupport.assumeAvailable();
        props = SearchIntegrationTestSupport.props();
        engine = new OpenSearchRestEngine(props);
        admin = new IndexAdmin(engine, props);
        admin.ensureIndex();
        admin.ensureTenantAlias(tenant);
    }

    @AfterEach
    void tearDown() {
        if (props != null) {
            SearchIntegrationTestSupport.cleanup(props);
        }
    }

    private void put(String id, String title, String fileName, String metadataText, String body) throws Exception {
        ObjectNode d = JSON.createObjectNode();
        d.put("tenant_id", tenant.toString());
        d.put("document_id", id);
        d.put("change_counter", 1);
        d.put("generation", 1);
        d.put("title", title);
        d.put("file_name", fileName);
        d.put("metadata_text", metadataText);
        d.put("body", body);
        d.put("lifecycle_state", "active");
        d.put("is_deleted", false);
        d.put("purged", false);
        d.put("is_template", false);
        assertThat(engine.bulk(List.of(SearchEngine.BulkOp.index(admin.currentIndex(), id, tenant.toString(), 1,
                JSON.writeValueAsString(d)))).allOk()).isTrue();
    }

    private List<String> ids(String queryJson) throws Exception {
        engine.refresh(admin.writeAlias());
        JsonNode r = engine.search(admin.readAlias(tenant), tenant.toString(),
                JSON.readTree("{\"query\":" + queryJson + ",\"size\":20,\"_source\":[\"document_id\"]}"));
        return java.util.stream.StreamSupport.stream(r.path("hits").path("hits").spliterator(), false)
                .map(h -> h.path("_id").asText()).toList();
    }

    private String simple(String q, String... fields) {
        String f = String.join("\",\"", fields);
        return "{\"simple_query_string\":{\"query\":\"" + q.replace("\"", "\\\"")
                + "\",\"fields\":[\"" + f + "\"],\"default_operator\":\"and\"}}";
    }

    @Test
    void sinhalaTamilAndThaiAreTokenisedAndFoldingKeepsIndicMarks() throws Exception {
        put("si", "ශ්‍රී ලංකා ගිවිසුම", "si.pdf", "", "");
        put("ta", "தமிழ் ஒப்பந்தம்", "ta.pdf", "", "");
        put("th", "สวัสดีครับ", "th.pdf", "", "");
        assertThat(ids(simple("ලංකා", "title"))).containsExactly("si");
        assertThat(ids(simple("ஒப்பந்தம்", "title"))).containsExactly("ta");
        // dictionary word-breaking: a word inside an unspaced Thai phrase is found
        assertThat(ids(simple("สวัสดี", "title"))).containsExactly("th");
        // folding must not strip the virama: the mark-less spelling is a different word and must not match
        assertThat(ids(simple("ஒபபநதம", "title"))).isEmpty();
    }

    @Test
    void accentsAreFoldedAndFileNamesSplitOnUnderscoreAndDot() throws Exception {
        put("a", "Café menu", "Invoice_2024.final-v2.pdf", "", "");
        assertThat(ids(simple("cafe", "title"))).containsExactly("a");
        assertThat(ids(simple("CAFÉ", "title"))).containsExactly("a");
        assertThat(ids(simple("final", "file_name"))).containsExactly("a");
        assertThat(ids(simple("invoice", "file_name"))).containsExactly("a");
        assertThat(ids(simple("2024", "file_name"))).containsExactly("a");
    }

    @Test
    void prefixAndPhraseMatching() throws Exception {
        put("p1", "Quarterly revenue report", "q3.pdf", "", "");
        put("p2", "Revenue report quarterly", "q4.pdf", "", "");
        assertThat(ids(simple("quart*", "title"))).containsExactlyInAnyOrder("p1", "p2");
        assertThat(ids(simple("\"quarterly revenue\"", "title"))).containsExactly("p1");
        assertThat(ids(simple("rep*", "title"))).containsExactlyInAnyOrder("p1", "p2");
    }

    @Test
    void bodyIsStemmedStoredAndNotReturnedInSource() throws Exception {
        put("b", "Plain title", "b.txt", "", "All invoices were paid on time");
        assertThat(ids(simple("invoice", "body"))).containsExactly("b");
        engine.refresh(admin.writeAlias());
        JsonNode r = engine.search(admin.readAlias(tenant), tenant.toString(), JSON.readTree(
                "{\"query\":" + simple("invoices", "body") + ",\"highlight\":{\"fields\":{\"body\":{}}}}"));
        JsonNode hit = r.path("hits").path("hits").get(0);
        assertThat(hit.path("_source").has("body")).as("body is excluded from _source").isFalse();
        assertThat(hit.path("highlight").path("body").get(0).asText()).contains("<em>invoices</em>");
    }

    @Test
    void nestedTypedMetadataSupportsEachValueSlot() throws Exception {
        ObjectNode d = JSON.createObjectNode();
        d.put("tenant_id", tenant.toString());
        d.put("document_id", "m");
        d.put("change_counter", 1);
        var arr = d.putArray("metadata");
        arr.addObject().put("field_id", "f-amount").put("key", "amount").put("v_num", 1500.5);
        arr.addObject().put("field_id", "f-status").put("key", "status").put("v_kw", "approved");
        arr.addObject().put("field_id", "f-due").put("key", "due").put("v_date", "2026-03-01");
        arr.addObject().put("field_id", "f-paid").put("key", "paid").put("v_bool", true);
        engine.bulk(List.of(SearchEngine.BulkOp.index(admin.currentIndex(), "m", tenant.toString(), 1, JSON.writeValueAsString(d))));
        String nestedNum = "{\"nested\":{\"path\":\"metadata\",\"query\":{\"bool\":{\"filter\":["
                + "{\"term\":{\"metadata.field_id\":\"f-amount\"}},{\"range\":{\"metadata.v_num\":{\"gte\":1000}}}]}}}}";
        assertThat(ids(nestedNum)).containsExactly("m");
        String wrongField = "{\"nested\":{\"path\":\"metadata\",\"query\":{\"bool\":{\"filter\":["
                + "{\"term\":{\"metadata.field_id\":\"f-status\"}},{\"range\":{\"metadata.v_num\":{\"gte\":1000}}}]}}}}";
        assertThat(ids(wrongField)).isEmpty();
        String kw = "{\"nested\":{\"path\":\"metadata\",\"query\":{\"bool\":{\"filter\":["
                + "{\"term\":{\"metadata.field_id\":\"f-status\"}},{\"term\":{\"metadata.v_kw\":\"approved\"}}]}}}}";
        assertThat(ids(kw)).containsExactly("m");
        String date = "{\"nested\":{\"path\":\"metadata\",\"query\":{\"range\":{\"metadata.v_date\":{\"lte\":\"2026-12-31\"}}}}}";
        assertThat(ids(date)).containsExactly("m");
    }

    @Test
    void unknownFieldsAreRejectedByTheStrictMapping() throws Exception {
        var bad = JSON.createObjectNode().put("tenant_id", tenant.toString()).put("surprise", 1);
        var res = engine.bulk(List.of(SearchEngine.BulkOp.index(admin.currentIndex(), "z", tenant.toString(), 1, JSON.writeValueAsString(bad))));
        assertThat(res.items().get(0).ok()).isFalse();
    }

    @Test
    void routingIsRequiredOnTheIndex() throws Exception {
        var d = JSON.createObjectNode().put("tenant_id", tenant.toString());
        var res = engine.bulk(List.of(SearchEngine.BulkOp.index(admin.currentIndex(), "r", null, 1, JSON.writeValueAsString(d))));
        assertThat(res.items().get(0).ok()).as("a document without routing must be refused").isFalse();
    }
}
