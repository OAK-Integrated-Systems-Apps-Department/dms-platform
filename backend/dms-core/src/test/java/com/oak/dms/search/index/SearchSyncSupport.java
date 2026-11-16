package com.oak.dms.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Shared test plumbing for the search ITs that run the real indexer against the disposable OpenSearch node: make
 * documents with files, drain the queue, and look at what ended up in the index.
 */
public class SearchSyncSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    final SearchEngine engine;
    final IndexAdmin admin;
    final SearchIndexer indexer;
    final JdbcTemplate jdbc;
    final WorkflowFixtures fx;

    public SearchSyncSupport(SearchEngine engine, IndexAdmin admin, SearchIndexer indexer, JdbcTemplate jdbc, WorkflowFixtures fx) {
        this.engine = engine;
        this.admin = admin;
        this.indexer = indexer;
        this.jdbc = jdbc;
        this.fx = fx;
    }

    /** A document with a text file, ready to index. */
    public UUID textDocument(UUID tenant, UUID type, User owner, String title, String fileName, String body) {
        UUID doc = fx.document(tenant, type, owner, title);
        UUID version = versionOf(doc);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        jdbc.update("update document_versions set original_filename = ?, mime_type = 'text/plain', size_bytes = ? where version_id = ?",
                fileName, bytes.length, version);
        fx.putVersionContent(version, bytes);
        return doc;
    }

    public UUID versionOf(UUID doc) {
        return jdbc.queryForObject("select current_version_id from documents where document_id = ?", UUID.class, doc);
    }

    public UUID folder(UUID tenant, UUID parent, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into folders (folder_id, tenant_id, parent_folder_id, folder_name) values (?, ?, ?, ?)",
                id, tenant, parent, name);
        return id;
    }

    /** Runs the indexer until nothing due is left (bounded), like a few scheduler ticks. */
    public int drain() {
        int total = 0;
        for (int i = 0; i < 60; i++) {
            int n = indexer.runOnce();
            if (n == 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    public long jobs(UUID tenant) {
        return jdbc.queryForObject("select count(*) from search_index_jobs where tenant_id = ?", Long.class, tenant);
    }

    public long jobsFor(UUID doc) {
        return jdbc.queryForObject("select count(*) from search_index_jobs where document_id = ?", Long.class, doc);
    }

    /** The indexed {@code _source} of a document, or null when it is not in the index. */
    public JsonNode source(UUID tenant, UUID doc) {
        engine.refresh(admin.writeAlias());
        JsonNode r = engine.search(admin.readAlias(tenant), tenant.toString(),
                parse("{\"query\":{\"ids\":{\"values\":[\"" + doc + "\"]}},\"size\":1}"));
        JsonNode hits = r.path("hits").path("hits");
        return hits.isEmpty() ? null : hits.get(0).path("_source");
    }

    public boolean indexed(UUID tenant, UUID doc) {
        return source(tenant, doc) != null;
    }

    /** Ids of this tenant's documents matching a query (query DSL fragment). */
    public List<String> ids(UUID tenant, String queryJson) {
        engine.refresh(admin.writeAlias());
        JsonNode r = engine.search(admin.readAlias(tenant), tenant.toString(),
                parse("{\"query\":" + queryJson + ",\"size\":100,\"_source\":false}"));
        return StreamSupport.stream(r.path("hits").path("hits").spliterator(), false).map(h -> h.path("_id").asText()).toList();
    }

    public long countInIndex(UUID tenant) {
        engine.refresh(admin.writeAlias());
        return engine.count(admin.readAlias(tenant), tenant.toString(), parse("{\"match_all\":{}}"));
    }

    public static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String match(String field, String text) {
        return "{\"match\":{\"" + field + "\":\"" + text + "\"}}";
    }
}
