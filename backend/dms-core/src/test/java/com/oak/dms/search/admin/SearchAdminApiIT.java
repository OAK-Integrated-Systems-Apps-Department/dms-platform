package com.oak.dms.search.admin;

import com.oak.dms.search.SearchIntegrationTestSupport;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.index.SearchIndexer;
import com.oak.dms.search.index.SearchRebuildService;
import com.oak.dms.search.index.SearchTenantStateRepository;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The search admin API: tenant administrators only, scoped to their own organisation, and audited. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchAdminApiIT extends WorkflowApiSupport {

    private static final String PREFIX = "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @DynamicPropertySource
    static void search(DynamicPropertyRegistry registry) {
        registry.add("dms.search.opensearch.url", SearchIntegrationTestSupport::url);
        registry.add("dms.search.index.prefix", () -> PREFIX);
    }

    @MockitoBean(name = "renditionGenerationListener")
    private Object backgroundRenditions;

    @Autowired
    private SearchTenantStateRepository tenantState;
    @Autowired
    private SearchIndexer indexer;
    @Autowired
    private SearchRebuildService rebuild;

    private UUID tenant;
    private User admin;
    private User member;
    private UUID type;

    @BeforeEach
    void setUp() {
        SearchIntegrationTestSupport.assumeAvailable();
        tenant = fx.tenant();
        admin = fx.user(tenant, "Admin");
        member = fx.user(tenant, "Member");
        type = fx.documentType(tenant, "Report");
        fx.jdbc().update("delete from search_index_jobs");
    }

    @AfterAll
    static void cleanUp() {
        SearchProperties p = new SearchProperties();
        p.getIndex().setPrefix(PREFIX);
        SearchIntegrationTestSupport.cleanup(p);
    }

    @Test
    void statusIsForTenantAdministratorsOnly() throws Exception {
        call(get("/api/v1/admin/search/status"), fx.tenantAdminJwtFor(admin), null, 200);
        call(get("/api/v1/admin/search/status"), fx.jwtFor(member), null, 403);
        mvc.perform(get("/api/v1/admin/search/status")).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void statusReportsEngineQueueAndReadinessOfTheCallersOwnTenantOnly() throws Exception {
        // two documents in this tenant, three in another: only this tenant's two are visible in the numbers
        UUID otherTenant = fx.tenant();
        User otherOwner = fx.user(otherTenant, "Elsewhere");
        UUID otherType = fx.documentType(otherTenant, "Memo");
        fx.document(tenant, type, admin, "One");
        fx.document(tenant, type, admin, "Two");
        for (int i = 0; i < 3; i++) {
            fx.document(otherTenant, otherType, otherOwner, "Other " + i);
        }

        JsonNode before = call(get("/api/v1/admin/search/status"), fx.tenantAdminJwtFor(admin), null, 200);
        assertThat(before.path("engine").asString()).isEqualTo("up");
        assertThat(before.path("pendingJobs").asLong()).isEqualTo(2);
        assertThat(before.path("failedJobs").asLong()).isZero();
        assertThat(before.path("indexReady").asBoolean()).isFalse();
        assertThat(before.path("searchEnabled").asBoolean()).isTrue();
        assertThat(before.path("backfillState").asString()).isEqualTo("none");
        assertThat(before.has("lastReconciledAt")).isTrue();

        // a parked job shows as failed, and the per-tenant switch shows as off
        fx.jdbc().update("insert into search_index_jobs (tenant_id, kind, document_id, reason, change_counter, status, attempts, max_attempts)"
                + " values (?, 'document', ?, 'x', 1, 'failed', 8, 8)", tenant, UUID.randomUUID());
        tenantState.setEnabled(tenant, false);
        JsonNode after = call(get("/api/v1/admin/search/status"), fx.tenantAdminJwtFor(admin), null, 200);
        assertThat(after.path("failedJobs").asLong()).isEqualTo(1);
        assertThat(after.path("searchEnabled").asBoolean()).isFalse();
    }

    @Test
    void reindexReturns202WithTheNewGeneration_isAuditedAndNeedsAnAdministrator() throws Exception {
        call(post("/api/v1/admin/search/reindex"), fx.jwtFor(member), null, 403);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'SEARCH_REINDEX_REQUESTED'", tenant)).isZero();

        long generationBefore = tenantState.generation(tenant);
        JsonNode r = call(post("/api/v1/admin/search/reindex"), fx.tenantAdminJwtFor(admin), null, 202);
        assertThat(r.path("generation").asLong()).isEqualTo(generationBefore + 1);
        assertThat(tenantState.generation(tenant)).isEqualTo(generationBefore + 1);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'SEARCH_REINDEX_REQUESTED'", tenant)).isEqualTo(1);
    }
}
