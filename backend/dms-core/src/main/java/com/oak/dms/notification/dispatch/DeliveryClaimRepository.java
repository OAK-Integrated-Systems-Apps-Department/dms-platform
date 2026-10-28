package com.oak.dms.notification.dispatch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The atomic dispatcher claim (3.2, N4). One UPDATE ... FROM a FOR UPDATE SKIP
 * LOCKED sub-select marks a batch of due pending deliveries 'sending' and
 * leases them (locked_until). SKIP LOCKED gives each row to exactly one claimer
 * across instances; the lease lets another instance reclaim a row whose claimer
 * crashed. Uses JdbcTemplate (not JPA) for the RETURNING clause.
 */
@Repository
public class DeliveryClaimRepository {

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
            RETURNING delivery_id
            """;

    private final JdbcTemplate jdbc;

    public DeliveryClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims up to {@code batchSize} due deliveries, leasing them for {@code leaseSeconds}; returns their ids. */
    public List<UUID> claimDue(long leaseSeconds, int batchSize) {
        return jdbc.query(CLAIM_SQL,
                (rs, rowNum) -> rs.getObject("delivery_id", UUID.class),
                (double) leaseSeconds, batchSize);
    }
}
