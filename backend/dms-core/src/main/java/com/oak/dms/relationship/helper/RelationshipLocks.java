package com.oak.dms.relationship.helper;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Lock helpers for writing relationships. The order inside a write transaction is always:
 * type row -> per-type advisory lock (loop-preventing types only) -> documents in ascending id
 * order -> the audit chain lock (taken last by AuditService). A wait that exceeds the timeout
 * surfaces as PessimisticLockingFailureException -> 409 CONCURRENT_UPDATE.
 */
@Component
@RequiredArgsConstructor
public class RelationshipLocks {

    static final String LOCK_TIMEOUT = "5s";

    private final JdbcTemplate jdbcTemplate;

    /** Bounds every lock wait in this transaction (transaction-local, so it never leaks to the pool). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyLockTimeout() {
        jdbcTemplate.queryForObject("select set_config('lock_timeout', ?, true)", String.class, LOCK_TIMEOUT);
    }

    /**
     * Serialises every write of one loop-preventing type in one tenant. Locking just the two
     * documents of a link is not enough: a loop of four or more documents can be closed by two
     * links that share no document, each checking before the other commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTypeForCycleCheck(UUID tenantId, UUID typeId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> null, "drm-cycle:" + tenantId + ":" + typeId);
    }
}
