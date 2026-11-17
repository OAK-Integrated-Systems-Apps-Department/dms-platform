package com.oak.dms.search.api;

import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S14 admin controls: reindex and reconcile are single-flight (409 while one runs) and rate limited (429 inside the
 * interval), the state lives in PostgreSQL, and the status says what is running and whether a reindex is advisable.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchAdminRunStateIT extends SearchApiSupport {

    private static final String REINDEX = "/api/v1/admin/search/reindex";
    private static final String RECONCILE = "/api/v1/admin/search/reconcile";
    private static final String STATUS = "/api/v1/admin/search/status";

    private JsonNode status() throws Exception {
        return call(get(STATUS), fx.tenantAdminJwtFor(tenantAdmin), null, 200);
    }

    private void state(String sql, Object... args) {
        tenantState.ensure(tenantId);
        fx.jdbc().update(sql, args);
    }

    @Test
    void reindexIsRefusedWhileOneRuns_andInsideTheMinimumInterval() throws Exception {
        var admin = fx.tenantAdminJwtFor(tenantAdmin);
        // a backfill or rebuild is running: 409, nothing started
        state("update search_tenant_state set backfill_state = 'running' where tenant_id = ?", tenantId);
        long generation = tenantState.generation(tenantId);
        JsonNode running = call(post(REINDEX), admin, null, 409);
        assertThat(running.path("code").asString()).isEqualTo("REINDEX_IN_PROGRESS");
        assertThat(tenantState.generation(tenantId)).isEqualTo(generation);
        assertThat(status().path("reindexRunning").asBoolean()).isTrue();

        // one finished a moment ago: 429
        state("update search_tenant_state set backfill_state = 'done', last_reindex_requested_at = now() where tenant_id = ?", tenantId);
        JsonNode limited = call(post(REINDEX), admin, null, 429);
        assertThat(limited.path("code").asString()).isEqualTo("REINDEX_RATE_LIMITED");
        assertThat(limited.path("details").path("retryAfterSeconds").asLong()).isPositive();
        assertThat(status().path("reindexRunning").asBoolean()).isFalse();

        // long enough ago: accepted, and an immediate second call is refused again
        state("update search_tenant_state set last_reindex_requested_at = now() - interval '10 minutes' where tenant_id = ?", tenantId);
        call(post(REINDEX), admin, null, 202);
        assertThat(tenantState.generation(tenantId)).isEqualTo(generation + 1);
        int again = mvc.perform(post(REINDEX).with(admin)).andReturn().getResponse().getStatus();
        assertThat(again).as("running (409) or just finished (429), never accepted").isIn(409, 429);
    }

    @Test
    void reconcileIsSingleFlightRateLimitedAuditedAndAdminOnly() throws Exception {
        var admin = fx.tenantAdminJwtFor(tenantAdmin);
        call(post(RECONCILE), fx.jwtFor(alice), null, 403);

        state("update search_tenant_state set reconcile_running_until = now() + interval '1 hour' where tenant_id = ?", tenantId);
        assertThat(call(post(RECONCILE), admin, null, 409).path("code").asString()).isEqualTo("RECONCILE_IN_PROGRESS");
        assertThat(status().path("reconcileRunning").asBoolean()).isTrue();

        state("update search_tenant_state set reconcile_running_until = null, last_reconcile_requested_at = now() where tenant_id = ?", tenantId);
        assertThat(call(post(RECONCILE), admin, null, 429).path("code").asString()).isEqualTo("RECONCILE_RATE_LIMITED");
        assertThat(status().path("reconcileRunning").asBoolean()).isFalse();
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'SEARCH_RECONCILE_REQUESTED'", tenantId)).isZero();

        // an expired lease frees itself (a crashed run does not block forever)
        state("update search_tenant_state set reconcile_running_until = now() - interval '1 minute', last_reconcile_requested_at = now() - interval '1 hour' where tenant_id = ?", tenantId);
        doc("Zebra one", "z.txt", "zebra");
        index();
        call(post(RECONCILE), admin, null, 202);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = 'SEARCH_RECONCILE_REQUESTED'", tenantId)).isEqualTo(1);

        // it runs in the background and records the first gap
        JsonNode done = null;
        for (int i = 0; i < 100; i++) {
            done = status();
            if (!done.path("reconcileRunning").asBoolean() && !done.path("gap").isNull()) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(done.path("gap").isNull()).isFalse();
        assertThat(done.path("gap").path("missing").asLong()).isZero();
        assertThat(done.path("gap").path("orphans").asLong()).isZero();
        assertThat(done.path("lastReconciledAt").isNull()).isFalse();
        assertThat(done.path("reconcileRunning").asBoolean()).isFalse();
    }

    @Test
    void theStatusSaysWhenAReindexIsAdvisableAfterSearchableFieldsChange() throws Exception {
        index();
        assertThat(status().path("reindexRecommended").asBoolean()).isFalse();

        UUID field = metadataField("invoice_no", "text", true);
        state("update search_tenant_state set last_backfill_at = now() - interval '1 hour' where tenant_id = ?", tenantId);
        fx.jdbc().update("update metadata_fields set updated_at = now() where field_id = ?", field);
        assertThat(status().path("reindexRecommended").asBoolean()).isTrue();

        // a completed backfill (which a reindex ends with) clears the hint
        state("update search_tenant_state set last_backfill_at = now() where tenant_id = ?", tenantId);
        assertThat(status().path("reindexRecommended").asBoolean()).isFalse();
        // never for another organisation's fields
        UUID otherTenant = fx.tenant();
        fx.jdbc().update("insert into metadata_fields (tenant_id, field_key, label, data_type, is_system_field, is_searchable, status, updated_at)"
                + " values (?, 'x', 'x', 'text', false, true, 'active', now() + interval '1 hour')", otherTenant);
        assertThat(status().path("reindexRecommended").asBoolean()).isFalse();
    }

    @Test
    void theNewStatusFieldsArePresent() throws Exception {
        JsonNode s = status();
        for (String field : new String[]{"reindexRecommended", "reindexRunning", "reconcileRunning", "gap", "lastReconciledAt"}) {
            assertThat(s.has(field)).as(field).isTrue();
        }
        assertThat(Map.of("x", 1)).isNotEmpty();
    }
}
