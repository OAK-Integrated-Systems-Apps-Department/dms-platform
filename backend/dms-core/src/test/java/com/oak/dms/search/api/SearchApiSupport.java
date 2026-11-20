package com.oak.dms.search.api;

import com.oak.dms.search.SearchIntegrationTestSupport;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.index.IndexAdmin;
import com.oak.dms.search.index.SearchBackfillService;
import com.oak.dms.search.index.SearchIndexer;
import com.oak.dms.search.index.SearchTenantStateRepository;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The world every search API test starts from: a fresh organisation, a few people with different permissions, helpers to
 * make documents with text files and to bring the index up to date (backfill, drain the queue, refresh), and a small
 * request helper that URL-encodes. The prefix is shared by every subclass, so they all use one Spring context.
 */
abstract class SearchApiSupport extends WorkflowApiSupport {

    static final String PREFIX = "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @DynamicPropertySource
    static void search(DynamicPropertyRegistry registry) {
        registry.add("dms.search.opensearch.url", SearchIntegrationTestSupport::url);
        registry.add("dms.search.index.prefix", () -> PREFIX);
    }

    @MockitoBean(name = "renditionGenerationListener")
    private Object backgroundRenditions;

    @Autowired
    protected SearchIndexer indexer;
    @Autowired
    protected IndexAdmin indexAdmin;
    @Autowired
    protected SearchEngine engine;
    @Autowired
    protected SearchBackfillService backfill;
    @Autowired
    protected SearchTenantStateRepository tenantState;

    protected UUID tenantId;
    protected User tenantAdmin;
    protected User alice;     // owns most documents; may search
    protected User bob;       // may search
    protected User carol;     // may NOT search (no dsr permission)
    protected User archivist; // may search and view the archive
    protected UUID reportType;

    @BeforeEach
    void searchWorld() {
        SearchIntegrationTestSupport.assumeAvailable();
        tenantId = fx.tenant();
        tenantAdmin = fx.user(tenantId, "Admin");
        alice = fx.user(tenantId, "Alice");
        bob = fx.user(tenantId, "Bob");
        carol = fx.user(tenantId, "Carol");
        archivist = fx.user(tenantId, "Archivist");
        String[] searcher = {"dsr.search-documents", "docm.documents.view"};
        fx.role(tenantId, "Searcher", searcher, alice, bob);
        fx.role(tenantId, "Viewer only", new String[]{"docm.documents.view"}, carol);
        fx.role(tenantId, "Archivist", new String[]{"dsr.search-documents", "docm.documents.view", "crm.view-archive"}, archivist);
        reportType = fx.documentType(tenantId, "Report");
        fx.jdbc().update("delete from search_index_jobs");
        indexAdmin.forgetTenantAliases();
    }

    @AfterAll
    static void dropIndices() {
        SearchProperties p = new SearchProperties();
        p.getIndex().setPrefix(PREFIX);
        SearchIntegrationTestSupport.cleanup(p);
    }

    // ---- documents ---------------------------------------------------------------------------------------------

    protected UUID doc(String title, String fileName, String body) {
        return doc(alice, title, fileName, body);
    }

    protected UUID doc(User owner, String title, String fileName, String body) {
        UUID document = fx.document(tenantId, reportType, owner, title);
        UUID version = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?", UUID.class, document);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        fx.jdbc().update("update document_versions set original_filename = ?, mime_type = 'text/plain', size_bytes = ? where version_id = ?",
                fileName, bytes.length, version);
        fx.putVersionContent(version, bytes);
        return document;
    }

    protected UUID folder(UUID parent, String name) {
        UUID id = UUID.randomUUID();
        fx.jdbc().update("insert into folders (folder_id, tenant_id, parent_folder_id, folder_name) values (?, ?, ?, ?)",
                id, tenantId, parent, name);
        return id;
    }

    protected UUID metadataField(String key, String dataType, boolean searchable) {
        return fx.jdbc().queryForObject("insert into metadata_fields (tenant_id, field_key, label, data_type, is_system_field, is_searchable, status)"
                + " values (?, ?, ?, ?, false, ?, 'active') returning field_id", UUID.class, tenantId, key, key, dataType, searchable);
    }

    protected void setMetadata(UUID document, String fieldValuesJson) {
        Integer schema = fx.jdbc().queryForObject("select count(*) from document_type_schemas where tenant_id = ? and document_type_id = ?",
                Integer.class, tenantId, reportType);
        if (schema == null || schema == 0) {
            fx.jdbc().update("insert into document_type_schemas (tenant_id, document_type_id, schema_version) values (?, ?, 1)", tenantId, reportType);
        }
        fx.jdbc().update("delete from document_metadata where document_id = ?", document);
        fx.jdbc().update("insert into document_metadata (tenant_id, document_id, document_type_id, schema_version, field_values) values (?, ?, ?, 1, ?::jsonb)",
                tenantId, document, reportType, fieldValuesJson);
    }

    // ---- the index -----------------------------------------------------------------------------------------------

    /** Brings the index up to date: backfill once, drain the queue, make the writes visible. */
    protected void index() {
        if (tenantState.find(tenantId).map(s -> s.indexReady()).orElse(false) == false) {
            backfill.run(tenantId, "backfill");
        }
        for (int i = 0; i < 60 && indexer.runOnce() > 0; i++) {
            // keep draining
        }
        engine.refresh(indexAdmin.writeAlias());
    }

    // ---- requests --------------------------------------------------------------------------------------------------

    /** {@code query("q", "zebra", "type", id)} -> {@code q=zebra&type=...}, URL-encoded. */
    protected static String query(Object... pairs) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                parts.add(pairs[i] + "=" + URLEncoder.encode(String.valueOf(pairs[i + 1]), StandardCharsets.UTF_8));
            }
        }
        return String.join("&", parts);
    }

    /**
     * The request, with each value added through {@code queryParam}: MockMvc treats a URL string as a URI template and
     * would encode an already-encoded value a second time (quotes, spaces and colons would arrive mangled).
     */
    protected static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String path, Object... pairs) {
        var req = get(path);
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                req.queryParam(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
            }
        }
        return req;
    }

    protected JsonNode search(User as, Object... pairs) throws Exception {
        return call(request("/api/v1/search", pairs), fx.jwtFor(as), null, 200);
    }

    protected JsonNode searchExpecting(int status, User as, Object... pairs) throws Exception {
        return call(request("/api/v1/search", pairs), fx.jwtFor(as), null, status);
    }

    protected static List<String> titles(JsonNode response) {
        List<String> out = new ArrayList<>();
        response.get("items").forEach(i -> out.add(i.get("title").asString()));
        return out;
    }

    protected static List<UUID> ids(JsonNode response) {
        List<UUID> out = new ArrayList<>();
        response.get("items").forEach(i -> out.add(UUID.fromString(i.get("documentId").asString())));
        return out;
    }

    protected static List<String> noticeCodes(JsonNode response) {
        List<String> out = new ArrayList<>();
        response.get("notices").forEach(n -> out.add(n.get("code").asString()));
        return out;
    }
}
