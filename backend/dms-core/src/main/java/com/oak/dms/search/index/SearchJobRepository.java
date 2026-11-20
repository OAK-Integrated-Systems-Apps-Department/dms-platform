package com.oak.dms.search.index;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The index queue (V138): claim, complete, fail. The claim is the notification dispatcher's pattern
 * ({@code FOR UPDATE SKIP LOCKED} plus a lease): each row goes to exactly one worker across instances, and a worker
 * that dies simply lets its lease expire so another instance picks the row up (crash recovery).
 *
 * <p>There is one pending row per document, so a change that arrives while a job runs just bumps its
 * {@code change_counter}. {@link #complete} deletes the row only if the counter is still the one that was processed;
 * otherwise the row survives (lock released) and is processed again at once, so no change is ever lost.
 */
@Repository
public class SearchJobRepository {

    public record Job(UUID jobId, UUID tenantId, String kind, UUID documentId, UUID folderId, String reason,
                      long changeCounter, int attempts, int maxAttempts) {
    }

    private static final String CLAIM_SQL = """
            UPDATE search_index_jobs
               SET locked_until = now() + make_interval(secs => ?), attempts = attempts + 1, updated_at = now()
             WHERE job_id IN (
                 SELECT job_id FROM search_index_jobs
                  WHERE status = 'pending' AND next_attempt_at <= now()
                    AND (locked_until IS NULL OR locked_until < now())
                  ORDER BY next_attempt_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT ?)
            RETURNING job_id, tenant_id, kind, document_id, folder_id, reason, change_counter, attempts, max_attempts
            """;

    private final JdbcTemplate jdbc;

    public SearchJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Job> claim(Duration lease, int batchSize) {
        return jdbc.query(CLAIM_SQL, (rs, n) -> new Job(
                rs.getObject("job_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("kind"),
                rs.getObject("document_id", UUID.class), rs.getObject("folder_id", UUID.class),
                rs.getString("reason"), rs.getLong("change_counter"), rs.getInt("attempts"), rs.getInt("max_attempts")),
                (double) lease.toSeconds(), batchSize);
    }

    /** Removes the job if nothing newer arrived while it ran; otherwise releases it for an immediate re-run. */
    public void complete(Job job) {
        int deleted = jdbc.update("DELETE FROM search_index_jobs WHERE job_id = ? AND change_counter = ?",
                job.jobId(), job.changeCounter());
        if (deleted == 0) {
            jdbc.update("UPDATE search_index_jobs SET locked_until = NULL, next_attempt_at = now() WHERE job_id = ?",
                    job.jobId());
        }
    }

    /** Records a failure and schedules the retry; after max attempts the job is parked as 'failed' (a visible dead letter). */
    public void fail(Job job, String error, Duration backoff) {
        int updated = jdbc.update("""
                UPDATE search_index_jobs
                   SET last_error = ?, locked_until = NULL,
                       next_attempt_at = now() + make_interval(secs => ?),
                       status = CASE WHEN attempts >= max_attempts THEN 'failed' ELSE 'pending' END,
                       updated_at = now()
                 WHERE job_id = ? AND change_counter = ?
                """, truncate(error), (double) backoff.toSeconds(), job.jobId(), job.changeCounter());
        if (updated == 0) {
            // A newer change arrived: it already reset attempts; just let it run now.
            jdbc.update("UPDATE search_index_jobs SET locked_until = NULL, next_attempt_at = now() WHERE job_id = ?", job.jobId());
        }
    }

    /** Enqueues (coalescing) a document - used by backfill, reconcile and subtree expansion. */
    public void enqueueDocument(UUID tenantId, UUID documentId, String reason) {
        jdbc.query("SELECT search_enqueue_document(?, ?, ?)", rs -> { }, tenantId, documentId, reason);
    }

    public long pendingCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM search_index_jobs WHERE status = 'pending'", Long.class);
        return n == null ? 0 : n;
    }

    public long failedCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM search_index_jobs WHERE status = 'failed'", Long.class);
        return n == null ? 0 : n;
    }

    public long pendingCount(UUID tenantId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM search_index_jobs WHERE status = 'pending' AND tenant_id = ?",
                Long.class, tenantId);
        return n == null ? 0 : n;
    }

    public long failedCount(UUID tenantId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM search_index_jobs WHERE status = 'failed' AND tenant_id = ?",
                Long.class, tenantId);
        return n == null ? 0 : n;
    }

    /**
     * Age basis for the lag metric: when the oldest change that is due (and not parked) became due. A coalesced job's
     * next_attempt_at is reset on every change, so this is the age of the freshest unprocessed change in the worst row.
     */
    public Optional<OffsetDateTime> oldestDueAt() {
        return jdbc.query("SELECT min(next_attempt_at) FROM search_index_jobs WHERE status = 'pending' AND next_attempt_at <= now()",
                rs -> rs.next() ? Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)) : Optional.<OffsetDateTime>empty());
    }

    /** Of the given documents, those that already have a job waiting (they are about to be repaired by the indexer). */
    public java.util.Set<UUID> pendingDocumentIds(UUID tenantId, java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(jdbc.query("""
                SELECT document_id FROM search_index_jobs
                 WHERE tenant_id = ? AND kind = 'document' AND document_id = ANY(?::uuid[])
                """, (rs, n) -> rs.getObject(1, UUID.class), tenantId, ids.toArray(new UUID[0])));
    }

    public Optional<OffsetDateTime> oldestDueAt(UUID tenantId) {
        return jdbc.query("SELECT min(next_attempt_at) FROM search_index_jobs WHERE status = 'pending' AND next_attempt_at <= now() AND tenant_id = ?",
                rs -> rs.next() ? Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)) : Optional.<OffsetDateTime>empty(), tenantId);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
