package com.oak.dms.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Data access for {@link OutboxEvent}. findBatchToPublish backs
 * {@link OutboxRelay}'s polling loop.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The batch the relay publishes next: up to {@code pageable.getPageSize()} of the {@code pending} rows that are DUE (OBX-03: their
     * {@code next_attempt_at} has arrived), earliest due first, each one row-locked for the rest of the CALLER's transaction (OBX-01).
     * <p>
     * Only the page size is used - the relay always asks for the first page, and with {@code SKIP LOCKED} a page
     * number would have no stable meaning.
     * <p>
     * This used to be a plain select, so two dms-core instances polling at the same time read the same pending rows,
     * published them twice, and then overwrote each other's result. The locks are what make the relays take turns:
     * a row another relay holds is skipped rather than waited for, so instances split a backlog between them and
     * nobody blocks. The locks are held until the relay's transaction ends - after the status updates are flushed - and
     * PostgreSQL releases them if the relay's connection or transaction dies, so a crashed relay leaves its rows
     * {@code pending} for the next poll (no lease to expire, no stale worker to fence).
     * <p>
     * It is only meaningful inside a transaction: outside one a {@code FOR UPDATE} lock is released as soon as the
     * statement returns and would protect nothing, so both methods refuse to run without one
     * ({@link Propagation#MANDATORY}) instead of silently doing so.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    default List<OutboxEvent> findBatchToPublish(Pageable pageable) {
        if (!pageable.isPaged()) {
            throw new IllegalArgumentException("A batch size is required");
        }
        return lockPendingBatch(pageable.getPageSize());
    }

    /**
     * The locking select behind {@link #findBatchToPublish}. Native SQL, so the locking clause is exactly what is
     * written here rather than whatever the Hibernate dialect renders for a lock hint. Rows are taken in the order they became
     * due: a new event is due when it is created, a retried one when its backoff ends; {@code created_at} and the id break
     * ties, so the order is deterministic. A row waiting for its retry is not selected at all, so it cannot hold a place in the
     * batch ahead of rows that are ready. PostgreSQL skips locked rows before applying the {@code limit}, so a relay that finds some rows locked
     * still fills its batch from the rows after them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            select * from outbox_events
             where status = 'pending'
               and next_attempt_at <= now()
             order by next_attempt_at asc, created_at asc, outbox_event_id asc
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("limit") int limit);

    /**
     * The DATABASE's current time in epoch milliseconds ({@code clock_timestamp()}, not the transaction's start time), so a
     * retry is scheduled on the same clock the batch query uses to decide what is eligible, and a long batch does not make the
     * backoff shorter than intended.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "select (extract(epoch from clock_timestamp()) * 1000)::bigint", nativeQuery = true)
    long databaseClockMillis();

    @Query("select count(e) from OutboxEvent e where e.status = com.oak.dms.outbox.OutboxEvent.Status.failed "
            + "and e.attempts >= :maxAttempts")
    long countDeadLettered(@Param("maxAttempts") int maxAttempts);
}
