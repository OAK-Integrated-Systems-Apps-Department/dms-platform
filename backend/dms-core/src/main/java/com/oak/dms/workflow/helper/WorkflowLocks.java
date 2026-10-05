package com.oak.dms.workflow.helper;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounds how long a workflow transaction waits for a row lock (Q8: 5 s).
 * {@code set_config(..., true)} is transaction-local, so it never leaks to
 * the pooled connection's next user. A wait that times out surfaces as
 * PessimisticLockingFailureException -> 409 CONCURRENT_UPDATE
 * (GlobalExceptionHandler). JdbcTemplate joins the surrounding JPA
 * transaction's connection.
 */
@Component
@RequiredArgsConstructor
public class WorkflowLocks {

    static final String LOCK_TIMEOUT = "5s";

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyLockTimeout() {
        jdbcTemplate.queryForObject("select set_config('lock_timeout', ?, true)", String.class, LOCK_TIMEOUT);
    }
}
