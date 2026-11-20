package com.oak.dms.notification.dispatch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The atomic dispatcher claim (3.2, N4). One UPDATE ... FROM a FOR UPDATE SKIP
 * LOCKED sub-select marks a batch of due pending deliveries 'sending' and
 * leases them (locked_until). SKIP LOCKED gives each row to exactly one claimer
 * across instances.
 * <p>
 * The claim itself only ever takes 'pending' rows. A row whose claimer stopped
 * before writing a result stays 'sending' with an expired lease, and is picked
 * up by {@link #lockExpiredLeases} (NOT-01): the dispatcher counts that abandoned
 * attempt, then retries the row with backoff or fails it. The lease value
 * returned by the claim is the fencing token for the result write
 * ({@link #lockIfStillLeased}): a worker that finishes after its lease was
 * recovered finds the token gone and discards its result. Uses JdbcTemplate (not
 * JPA) for the RETURNING clause and the row locks.
 */
@Repository
public class DeliveryClaimRepository {

    /** A claimed delivery and the lease it was claimed under - the proof of ownership for the result write. */
    public record Lease(UUID deliveryId, OffsetDateTime leaseUntil) {
    }

    private static final String CLAIM_SQL = """
            UPDATE notification_deliveries
               SET status = 'sending', locked_until = now() + make_interval(secs => ?), updated_at = now()
             WHERE delivery_id IN (
                 SELECT delivery_id FROM notification_deliveries
                  WHERE status = 'pending' AND next_attempt_at <= now()
                    AND (locked_until IS NULL OR locked_until < now())
                  ORDER BY next_attempt_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT ?)
            RETURNING delivery_id, locked_until
            """;

    /**
     * Locks (for the rest of the caller's transaction) up to N deliveries that were claimed but never finished: still
     * 'sending' with a lease that has run out. SKIP LOCKED, so two dispatchers never recover the same row, and a row
     * whose result is being written right now (it holds the row lock) is not touched.
     */
    private static final String EXPIRED_LEASES_SQL = """
            SELECT delivery_id FROM notification_deliveries
             WHERE status = 'sending' AND (locked_until IS NULL OR locked_until < now())
             ORDER BY locked_until NULLS FIRST
             FOR UPDATE SKIP LOCKED
             LIMIT ?
            """;

    /** Locks one delivery, but only while it is still 'sending' under exactly the lease the caller was given. */
    private static final String LOCK_IF_LEASED_SQL = """
            SELECT 1 FROM notification_deliveries
             WHERE delivery_id = ? AND status = 'sending' AND locked_until = ?
             FOR UPDATE
            """;

    private final JdbcTemplate jdbc;

    public DeliveryClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims up to {@code batchSize} due deliveries, leasing them for {@code leaseSeconds}; returns each id with its lease. */
    public List<Lease> claimLeases(long leaseSeconds, int batchSize) {
        return jdbc.query(CLAIM_SQL,
                (rs, rowNum) -> new Lease(rs.getObject("delivery_id", UUID.class),
                        rs.getObject("locked_until", OffsetDateTime.class)),
                (double) leaseSeconds, batchSize);
    }

    /** Claims up to {@code batchSize} due deliveries, leasing them for {@code leaseSeconds}; returns their ids. */
    public List<UUID> claimDue(long leaseSeconds, int batchSize) {
        return claimLeases(leaseSeconds, batchSize).stream().map(Lease::deliveryId).toList();
    }

    /** Ids of up to {@code batchSize} abandoned deliveries, row-locked until the surrounding transaction ends. */
    public List<UUID> lockExpiredLeases(int batchSize) {
        return jdbc.query(EXPIRED_LEASES_SQL, (rs, rowNum) -> rs.getObject("delivery_id", UUID.class), batchSize);
    }

    /**
     * True, with the row locked until the surrounding transaction ends, when the delivery is still 'sending' under
     * {@code lease}; false when it was recovered, re-claimed or otherwise finished in the meantime (a stale worker).
     */
    public boolean lockIfStillLeased(UUID deliveryId, OffsetDateTime lease) {
        return !jdbc.queryForList(LOCK_IF_LEASED_SQL, Integer.class, deliveryId, lease).isEmpty();
    }
}
