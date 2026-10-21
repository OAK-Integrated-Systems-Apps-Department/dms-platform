package com.oak.dms.relationship;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * NFR-002: 1,000,000 relationship records without degradation. Opt-in - it generates 20,000 documents and
 * a million links - so it is tagged {@code perf} and left out of the normal {@code verify}. Run it with
 * <pre>./mvnw verify -Pperf -Dit.test=RelationshipScaleIT</pre>
 * (see docs/relationships-user-guide.md).
 */
@Tag("perf")
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipScaleIT extends RelationshipApiSupport {

    private static final int DOCUMENTS = 20_000;
    private static final int LINKS = 1_000_000;

    @Test
    void aMillionLinks_panelFilterAndCreateStayFast_andUseTheIndexes() throws Exception {
        UUID tenantId = tenantWithTypes();
        User user = fx.user(tenantId, "Editor");
        fx.role(tenantId, "Editor", new String[]{CREATE, REMOVE, VIEW}, user);
        UUID docType = fx.documentType(tenantId, "Contract");
        var jdbc = fx.jdbc();
        UUID location = jdbc.queryForObject("select storage_location_id from storage_locations where tenant_id = ? and purpose = 'document'",
                UUID.class, tenantId);
        UUID staging = jdbc.queryForObject("select storage_location_id from storage_locations where tenant_id = ? and purpose = 'staging'",
                UUID.class, tenantId);
        UUID engine = jdbc.queryForObject("select scan_engine_id from scan_engines where status = 'active' order by created_at limit 1", UUID.class);

        // 20,000 documents, each with a clean version (the scan-gate trigger wants a clean scan result per version).
        long t0 = System.nanoTime();
        new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(s -> {
            jdbc.execute("create temp table gen_docs on commit drop as select g as n, gen_random_uuid() as document_id,"
                    + " gen_random_uuid() as version_id from generate_series(0, " + (DOCUMENTS - 1) + ") g");
            jdbc.update("insert into documents (document_id, tenant_id, document_type_id, title, current_version_id, latest_version_id,"
                    + " owner_user_id) select document_id, ?, ?, 'Scale ' || n, version_id, version_id, ? from gen_docs",
                    tenantId, docType, user.getUserId());
            jdbc.update("insert into document_versions (version_id, document_id, tenant_id, version_number, major_version, minor_version,"
                    + " storage_location_id, storage_object_key, original_filename, mime_type, file_hash, size_bytes, created_reason,"
                    + " scan_status) select version_id, document_id, ?, 1, 1, 0, ?, 'k' || version_id, 'a.pdf', 'application/pdf',"
                    + " md5(n::text), 1, 'initial_upload', 'clean' from gen_docs", tenantId, location);
            jdbc.update("insert into scan_results (tenant_id, scan_engine_id, document_version_id, staging_location_id, original_filename,"
                    + " file_hash, size_bytes, mime_type, status, engine_name, scan_mode, completed_at, uploaded_by, ingestion_kind,"
                    + " created_reason, document_type_id, title) select ?, ?, version_id, ?, 'a.pdf', md5(n::text), 1, 'application/pdf',"
                    + " 'clean', 'scale', 'pre_store_stream', now(), ?, 'new_document', 'initial_upload', ?, 'Scale ' || n from gen_docs",
                    tenantId, engine, staging, user.getUserId(), docType);
            // A million links: source k % N, target (source + 1 + k / N) % N - every pair distinct, ~50 out and ~50 in per document;
            // one in ten Supersedes (acyclic by construction: only forward offsets are used for it).
            jdbc.update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id, target_document_id, created_by)"
                    + " select ?, case when k % 10 = 0 then (select relationship_type_id from relationship_types where tenant_id = ? and code = 'SUPERSEDES')"
                    + " else (select relationship_type_id from relationship_types where tenant_id = ? and code = 'MAIN_ATTACHMENT') end,"
                    + " a.ids[(k % " + DOCUMENTS + ") + 1], a.ids[((k % " + DOCUMENTS + ") + 1 + k / " + DOCUMENTS + ") % " + DOCUMENTS + " + 1], ?"
                    + " from (select array_agg(document_id order by n) ids from gen_docs) a, generate_series(0, " + (LINKS - 1) + ") k",
                    tenantId, tenantId, tenantId, user.getUserId());
        });
        jdbc.execute("analyze document_relationships");
        System.out.printf("Generated %d documents and %d links in %d ms%n", DOCUMENTS, LINKS, (System.nanoTime() - t0) / 1_000_000);
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ?", tenantId)).isEqualTo(LINKS);

        UUID hub = jdbc.queryForObject("select document_id from documents where tenant_id = ? and title = 'Scale 0'", UUID.class, tenantId);

        // The panel (about 100 links): fast, and planned on the indexes.
        panel(user, hub);
        long start = System.nanoTime();
        JsonNode p = panel(user, hub);
        long panelMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("Panel with %d links: %d ms%n", p.get("total").asInt(), panelMs);
        assertThat(p.get("total").asInt()).isBetween(90, 110);
        assertThat(panelMs).isLessThan(2000);
        String plan = String.join("\n", jdbc.queryForList("explain select r.* from document_relationships r where r.tenant_id = ?"
                + " and r.source_document_id = ? and r.removed_at is null", String.class, tenantId, hub));
        assertThat(plan).contains("ix_document_relationships_source");
        plan = String.join("\n", jdbc.queryForList("explain select r.* from document_relationships r where r.tenant_id = ?"
                + " and r.target_document_id = ? and r.removed_at is null", String.class, tenantId, hub));
        assertThat(plan).contains("ix_document_relationships_target");

        // Browsing by relationship: "documents that supersede this", and by type alone.
        start = System.nanoTime();
        JsonNode supersede = call(get("/api/v1/documents?all=true&relatedTo=" + hub + "&relationshipType=SUPERSEDES&direction=source"),
                fx.jwtFor(user), null, 200);
        long filterMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("relatedTo filter: %d results, %d ms%n", supersede.get("content").size(), filterMs);
        assertThat(filterMs).isLessThan(2000);
        start = System.nanoTime();
        call(get("/api/v1/documents?all=true&relationshipType=SUPERSEDES"), fx.jwtFor(user), null, 200);
        long typeMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("type-only filter: %d ms%n", typeMs);
        assertThat(typeMs).isLessThan(2000);

        // Creating a Supersedes link (loop check over a big graph) and a plain link.
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        UUID last = jdbc.queryForObject("select document_id from documents where tenant_id = ? and title = ?", UUID.class, tenantId,
                "Scale " + (DOCUMENTS - 1));
        start = System.nanoTime();
        int status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/document-relationships")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body("relationshipTypeId", supersedes, "sourceDocumentId", hub, "targetDocumentId", last)))
                .with(fx.jwtFor(user))).andReturn().getResponse().getStatus();
        long createMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("create Supersedes (status %d): %d ms%n", status, createMs);
        assertThat(List.of(201, 409)).contains(status);
        assertThat(createMs).isLessThan(5000);
    }
}
