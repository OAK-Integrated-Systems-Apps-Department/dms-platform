package com.oak.dms.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * OBX-04: the one statement that ever deletes from {@code outbox_events}.
 * <p>
 * <b>What it may delete.</b> Only rows that are BOTH {@code status = 'published'} AND have a {@code published_at} older than
 * the cutoff. Never a pending row (including one waiting for its retry), never a failed one, never a row without a
 * {@code published_at}, never a row inside the retention window. The conditions are written TWICE: the inner select picks and
 * locks the candidates, and the DELETE checks status and cutoff again on the row it is about to remove, so a delete never
 * rests on the select alone.
 * <p>
 * <b>The cutoff is the DATABASE's:</b> {@code now() - retention}, evaluated by PostgreSQL in the same statement (the same
 * {@code now()} for the select and the delete). No application clock is involved.
 * <p>
 * <b>Locking.</b> At most {@code batchSize} rows, in the order {@code published_at, outbox_event_id}, each row-locked with
 * {@code FOR UPDATE SKIP LOCKED}: another cleanup (another application instance) takes different rows instead of waiting, and
 * a row some other transaction holds is skipped, not waited for. No table lock; the lock lives as long as the caller's
 * transaction, which is why the method refuses to run without one ({@link Propagation#MANDATORY}) - the cleanup keeps each
 * batch in a short transaction of its own. The relay's rows (pending) can never be in this set, so the relay's own
 * {@code SKIP LOCKED} selection and this one never meet.
 * <p>
 * The candidate select is a {@code MATERIALIZED} CTE so PostgreSQL evaluates it exactly once: a locking select with a
 * {@code LIMIT} inside an {@code IN (subquery)} can be re-run by the planner and touch more rows than the limit.
 */
@Repository
public class OutboxCleanupStore {

    static final String DELETE_PUBLISHED_BATCH = """
            with picked as materialized (
                select outbox_event_id
                  from outbox_events
                 where status = 'published'
                   and published_at < now() - make_interval(secs => cast(? as double precision))
                 order by published_at asc, outbox_event_id asc
                 limit ?
                   for update skip locked)
            delete from outbox_events e
             using picked p
             where e.outbox_event_id = p.outbox_event_id
               and e.status = 'published'
               and e.published_at < now() - make_interval(secs => cast(? as double precision))
            """;

    private final JdbcTemplate jdbc;

    public OutboxCleanupStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes up to {@code batchSize} published rows older than {@code retentionSeconds} (on the database clock) and returns
     * how many it deleted. Must run inside a transaction; the rows stay deleted only if that transaction commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deletePublishedBatch(long retentionSeconds, int batchSize) {
        if (retentionSeconds < OutboxCleanupPolicy.MIN_RETENTION_HOURS * 3600) {
            throw new IllegalArgumentException("a published event is never deleted within 24 hours of being published");
        }
        if (batchSize < 1 || batchSize > OutboxCleanupPolicy.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batch size out of range: " + batchSize);
        }
        return jdbc.update(DELETE_PUBLISHED_BATCH, retentionSeconds, batchSize, retentionSeconds);
    }
}
