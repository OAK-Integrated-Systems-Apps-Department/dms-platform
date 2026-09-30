package com.oak.dms.scanning.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Plain JDBC: {@code UPDATE ... RETURNING} over a {@code FOR UPDATE SKIP LOCKED}
 * subquery has no JPQL equivalent. Joins the surrounding transaction's
 * connection, same as {@code DocumentMetadataRepositoryImpl}.
 */
@RequiredArgsConstructor
public class ScanResultRepositoryImpl implements ScanResultRepositoryCustom {

    private static final String CLAIM_DUE_SQL = """
            update scan_results
               set lease_expires_at = now() + make_interval(secs => ?)
             where scan_result_id in (
                   select scan_result_id
                     from scan_results
                    where status in ('pending', 'infected')
                      and is_deleted = false
                      and next_attempt_at <= now()
                      and (lease_expires_at is null or lease_expires_at < now())
                    order by next_attempt_at
                    limit ?
                    for update skip locked)
            returning scan_result_id
            """;

    private static final String CLAIM_ONE_SQL = """
            update scan_results
               set lease_expires_at = now() + make_interval(secs => ?)
             where scan_result_id = ?
               and status in ('pending', 'infected')
               and is_deleted = false
               and (lease_expires_at is null or lease_expires_at < now())
            """;

    private static final String STAGING_TO_PURGE_SQL = """
            select scan_result_id
              from scan_results
             where staging_purged_at is null
               and staging_object_key is not null
               and (status in ('clean', 'quarantined', 'unscannable')
                    or (status = 'failed' and coalesce(completed_at, updated_at, created_at)
                                               < now() - make_interval(secs => ?)))
             limit ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<UUID> claimDueWork(int batchSize, Duration lease) {
        return jdbcTemplate.queryForList(CLAIM_DUE_SQL, UUID.class, (double) lease.toSeconds(), batchSize);
    }

    @Override
    public boolean claimOne(UUID scanResultId, Duration lease) {
        return jdbcTemplate.update(CLAIM_ONE_SQL, (double) lease.toSeconds(), scanResultId) == 1;
    }

    @Override
    public List<UUID> findStagingToPurge(Duration failedRetention, int batchSize) {
        return jdbcTemplate.queryForList(STAGING_TO_PURGE_SQL, UUID.class, (double) failedRetention.toSeconds(), batchSize);
    }
}
