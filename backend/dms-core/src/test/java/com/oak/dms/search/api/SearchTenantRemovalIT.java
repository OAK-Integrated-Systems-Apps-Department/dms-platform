package com.oak.dms.search.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removing an organisation (S15): its documents leave the index, its alias is dropped, its search state stops claiming
 * to be ready, other organisations are untouched, and reactivating it brings search back through the normal backfill.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchTenantRemovalIT extends SearchApiSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private long indexed(UUID tenant) throws Exception {
        engine.refresh(indexAdmin.writeAlias());
        return engine.count(indexAdmin.writeAlias(), tenant.toString(),
                JSON.readTree("{\"term\":{\"tenant_id\":\"" + tenant + "\"}}"));
    }

    private void drain() {
        for (int i = 0; i < 60 && indexer.runOnce() > 0; i++) {
            // keep draining
        }
    }

    @Test
    void removingATenantEmptiesItsIndex_dropsItsAlias_andLeavesOthersAlone() throws Exception {
        doc("Zebra one", "z.txt", "zebra");
        index();
        // another organisation with a document of its own
        UUID other = fx.tenant();
        var otherOwner = fx.user(other, "Olga");
        UUID otherType = fx.documentType(other, "Memo");
        fx.document(other, otherType, otherOwner, "Other zebra");
        backfill.run(other, "backfill");
        drain();
        assertThat(indexed(tenantId)).isEqualTo(1);
        assertThat(indexed(other)).isEqualTo(1);
        assertThat(indexAdmin.tenantAliasExists(tenantId)).isTrue();
        assertThat(tenantState.find(tenantId).orElseThrow().indexReady()).isTrue();

        fx.jdbc().update("update tenants set is_deleted = true, deleted_at = now() where tenant_id = ?", tenantId);
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ? and kind = 'tenant_purge'", tenantId)).isEqualTo(1);
        drain();

        assertThat(indexed(tenantId)).as("documents gone from the index").isZero();
        assertThat(indexAdmin.tenantAliasExists(tenantId)).as("alias dropped").isFalse();
        var state = tenantState.find(tenantId).orElseThrow();
        assertThat(state.indexReady()).as("not left claiming ready").isFalse();
        assertThat(state.backfillState()).isEqualTo("none");
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ?", tenantId)).as("queue empty").isZero();
        assertThat(indexed(other)).as("another organisation untouched").isEqualTo(1);
        assertThat(indexAdmin.tenantAliasExists(other)).isTrue();
        // and the documents themselves are not touched: removal is soft
        assertThat(fx.count("select count(*) from documents where tenant_id = ? and not is_deleted", tenantId)).isEqualTo(1);
    }

    @Test
    void reactivatingATenantBringsSearchBackThroughTheBackfill() throws Exception {
        doc("Zebra one", "z.txt", "zebra");
        index();
        fx.jdbc().update("update tenants set is_deleted = true, deleted_at = now() where tenant_id = ?", tenantId);
        drain();
        assertThat(indexed(tenantId)).isZero();

        fx.jdbc().update("update tenants set is_deleted = false, deleted_at = null where tenant_id = ?", tenantId);
        var reset = tenantState.find(tenantId).orElseThrow();
        assertThat(reset.indexReady()).isFalse();
        assertThat(reset.backfillState()).isEqualTo("none");
        // until the backfill has run, search falls back to the title search instead of showing nothing
        JsonNode fallback = search(alice, "q", "Zebra");
        assertThat(fallback.path("mode").asString()).isEqualTo("basic");
        assertThat(titles(fallback)).containsExactly("Zebra one");

        for (UUID tenant : tenantState.tenantsNeedingBackfill()) {
            if (tenant.equals(tenantId)) {
                backfill.run(tenant, "backfill");
            }
        }
        drain();
        engine.refresh(indexAdmin.writeAlias());
        assertThat(tenantState.find(tenantId).orElseThrow().indexReady()).isTrue();
        JsonNode back = search(alice, "q", "zebra");
        assertThat(back.path("mode").asString()).isEqualTo("search");
        assertThat(titles(back)).containsExactly("Zebra one");
    }

    @Test
    void aPurgeThatRunsAfterAReactivationDoesNotEmptyALiveTenant() throws Exception {
        doc("Zebra one", "z.txt", "zebra");
        index();
        fx.jdbc().update("update tenants set is_deleted = true, deleted_at = now() where tenant_id = ?", tenantId);
        fx.jdbc().update("update tenants set is_deleted = false, deleted_at = null where tenant_id = ?", tenantId);
        // the purge job is still queued: running it must be a no-op now
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ? and kind = 'tenant_purge'", tenantId)).isEqualTo(1);
        drain();
        assertThat(indexed(tenantId)).as("still indexed").isEqualTo(1);
        assertThat(fx.count("select count(*) from search_index_jobs where tenant_id = ?", tenantId)).isZero();
    }
}
