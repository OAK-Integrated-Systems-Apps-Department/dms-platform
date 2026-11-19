package com.oak.dms.search.index;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Per-tenant search state (V138): the on/off switch, readiness, rebuild generation, backfill and reconcile progress. */
@Repository
public class SearchTenantStateRepository {

    public record State(UUID tenantId, boolean enabled, boolean indexReady, long generation, String backfillState,
                        UUID backfillCursor, OffsetDateTime lastBackfillAt, OffsetDateTime lastReconciledAt,
                        String lastGapJson) {
    }

    private final JdbcTemplate jdbc;

    public SearchTenantStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<State> find(UUID tenantId) {
        return jdbc.query("""
                select tenant_id, enabled, index_ready, generation, backfill_state, backfill_cursor,
                       last_backfill_at, last_reconciled_at, last_gap::text
                  from search_tenant_state where tenant_id = ?
                """, (rs, n) -> new State(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3), rs.getLong(4),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, OffsetDateTime.class),
                rs.getObject(8, OffsetDateTime.class), rs.getString(9)), tenantId).stream().findFirst();
    }

    /** The row, created with defaults when the tenant has none yet. Safe under concurrency. */
    public State ensure(UUID tenantId) {
        jdbc.update("insert into search_tenant_state (tenant_id) values (?) on conflict do nothing", tenantId);
        return find(tenantId).orElseThrow();
    }

    public boolean enabled(UUID tenantId) {
        return find(tenantId).map(State::enabled).orElse(true);
    }

    public long generation(UUID tenantId) {
        return find(tenantId).map(State::generation).orElse(1L);
    }

    public void setEnabled(UUID tenantId, boolean enabled) {
        ensure(tenantId);
        jdbc.update("update search_tenant_state set enabled = ?, updated_at = now() where tenant_id = ?", enabled, tenantId);
    }

    public void setIndexReady(UUID tenantId, boolean ready) {
        ensure(tenantId);
        jdbc.update("update search_tenant_state set index_ready = ?, updated_at = now() where tenant_id = ?", ready, tenantId);
    }

    public void setBackfill(UUID tenantId, String state, UUID cursor) {
        ensure(tenantId);
        jdbc.update("""
                update search_tenant_state set backfill_state = ?, backfill_cursor = ?,
                       last_backfill_at = case when ? = 'done' then now() else last_backfill_at end, updated_at = now()
                 where tenant_id = ?
                """, state, cursor, state, tenantId);
    }

    /**
     * Starts a tenant rebuild: a new generation and a running backfill. The tenant stays ready: searches keep using the
     * existing documents until each is overwritten by its re-indexed copy (no downtime). Returns the new generation.
     */
    public long startRebuild(UUID tenantId) {
        ensure(tenantId);
        return jdbc.queryForObject("""
                update search_tenant_state set generation = generation + 1,
                       backfill_state = 'running', backfill_cursor = null, updated_at = now()
                 where tenant_id = ? returning generation
                """, Long.class, tenantId);
    }

    public void recordReconcile(UUID tenantId, String gapJson) {
        ensure(tenantId);
        jdbc.update("update search_tenant_state set last_reconciled_at = now(), last_gap = ?::jsonb, updated_at = now() where tenant_id = ?",
                gapJson, tenantId);
    }

    /** Tenants that exist and are not removed (the set the backfill and reconciler walk). */
    public List<UUID> activeTenantIds() {
        return jdbc.query("select tenant_id from tenants where not is_deleted order by tenant_id",
                (rs, n) -> rs.getObject(1, UUID.class));
    }

    public List<UUID> tenantsNeedingBackfill() {
        return jdbc.query("""
                select t.tenant_id from tenants t
                  left join search_tenant_state s on s.tenant_id = t.tenant_id
                 where not t.is_deleted and coalesce(s.enabled, true)
                   and coalesce(s.backfill_state, 'none') in ('none', 'running')
                 order by t.tenant_id
                """, (rs, n) -> rs.getObject(1, UUID.class));
    }

    /**
     * Marks the tenant's index ready once its backfill has finished and nothing is waiting in its queue. Returns true when
     * this call made it ready.
     */
    public boolean markReadyIfDrained(UUID tenantId) {
        return jdbc.update("""
                update search_tenant_state set index_ready = true, updated_at = now()
                 where tenant_id = ? and not index_ready and backfill_state = 'done'
                   and not exists (select 1 from search_index_jobs j where j.tenant_id = search_tenant_state.tenant_id
                                    and j.status = 'pending')
                """, tenantId) > 0;
    }

    // ---- admin single-flight and rate limits (V143) -------------------------------------------------------------

    /** True while the organisation is removed (soft-deleted). */
    public boolean isRemoved(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select coalesce((select is_deleted from tenants where tenant_id = ?), true)", Boolean.class, tenantId));
    }

    public enum Claim { OK, RUNNING, RATE_LIMITED }

    /** Atomically takes the right to start a reindex: refused while a backfill runs (RUNNING) or inside the interval. */
    public Claim claimReindex(UUID tenantId, java.time.Duration minInterval) {
        ensure(tenantId);
        int n = jdbc.update("""
                update search_tenant_state set last_reindex_requested_at = now(), updated_at = now()
                 where tenant_id = ? and backfill_state <> 'running'
                   and (last_reindex_requested_at is null
                        or last_reindex_requested_at < now() - make_interval(secs => ?))
                """, tenantId, (double) minInterval.toSeconds());
        if (n == 1) {
            return Claim.OK;
        }
        return find(tenantId).map(s -> "running".equals(s.backfillState())).orElse(false) ? Claim.RUNNING : Claim.RATE_LIMITED;
    }

    /** Atomically takes the right to run an on-demand reconcile (a lease, so a crashed run frees itself). */
    public Claim claimReconcile(UUID tenantId, java.time.Duration minInterval, java.time.Duration lease) {
        ensure(tenantId);
        int n = jdbc.update("""
                update search_tenant_state set last_reconcile_requested_at = now(),
                       reconcile_running_until = now() + make_interval(secs => ?), updated_at = now()
                 where tenant_id = ? and (reconcile_running_until is null or reconcile_running_until < now())
                   and (last_reconcile_requested_at is null
                        or last_reconcile_requested_at < now() - make_interval(secs => ?))
                """, (double) lease.toSeconds(), tenantId, (double) minInterval.toSeconds());
        if (n == 1) {
            return Claim.OK;
        }
        return isReconcileRunning(tenantId) ? Claim.RUNNING : Claim.RATE_LIMITED;
    }

    public void finishReconcile(UUID tenantId) {
        jdbc.update("update search_tenant_state set reconcile_running_until = null, updated_at = now() where tenant_id = ?", tenantId);
    }

    public boolean isReconcileRunning(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select coalesce((select reconcile_running_until > now() from search_tenant_state where tenant_id = ?), false)",
                Boolean.class, tenantId));
    }

    /**
     * True when a metadata field definition of the tenant changed after the last completed backfill. Changing whether a
     * field is searchable (or its type) only affects documents indexed afterwards, so existing ones need a reindex. Any
     * change counts (a heuristic: a renamed label also trips it); a reindex clears it.
     */
    public boolean reindexRecommended(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from search_tenant_state s
                      join metadata_fields f on f.tenant_id = s.tenant_id
                     where s.tenant_id = ? and s.last_backfill_at is not null
                       and coalesce(f.updated_at, f.created_at) > s.last_backfill_at)
                """, Boolean.class, tenantId));
    }
}
