package com.oak.dms.reporting.datasource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.function.Function;

/**
 * Runs report queries in ONE read-only transaction at {@code REPEATABLE READ} (CRG-NFR-003), so the count, the rows and the group
 * counts of a report all see the same committed state - a document changed while the file is written cannot make it
 * inconsistent. A statement timeout is applied with {@code SET LOCAL}, so it ends with the transaction and can never leak onto
 * the pooled connection the next caller gets.
 * <p>
 * Always its own transaction ({@code REQUIRES_NEW}): an isolation level cannot be changed inside one that already started.
 */
@Component
public class ReadOnlySnapshot {

    /** PostgreSQL's "canceling statement due to statement timeout". */
    private static final String QUERY_CANCELED = "57014";

    private final PlatformTransactionManager transactions;
    private final NamedParameterJdbcTemplate jdbc;

    public ReadOnlySnapshot(PlatformTransactionManager transactions, NamedParameterJdbcTemplate jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    /** @param timeout statement timeout for everything {@code work} runs; a number of milliseconds, never user text */
    public <T> T run(Duration timeout, Function<NamedParameterJdbcTemplate, T> work) {
        return execute(timeout, jdbc, work);
    }

    /**
     * Like {@link #run}, but the template it hands to {@code work} reads a result set in chunks of {@code fetchSize} rows instead
     * of all at once - what lets a 200,000-row export run in a small heap. It shares the snapshot's connection.
     */
    public <T> T runStreaming(Duration timeout, int fetchSize, Function<NamedParameterJdbcTemplate, T> work) {
        JdbcTemplate streaming = new JdbcTemplate(jdbc.getJdbcTemplate().getDataSource());
        streaming.setFetchSize(fetchSize);
        return execute(timeout, new NamedParameterJdbcTemplate(streaming), work);
    }

    private <T> T execute(Duration timeout, NamedParameterJdbcTemplate template, Function<NamedParameterJdbcTemplate, T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            template.getJdbcTemplate().execute("set local statement_timeout = " + Math.max(1, timeout.toMillis()));
            return work.apply(template);
        });
    }

    /** Whether the failure is a statement timeout (or a cancel) rather than a real error. */
    public static boolean isTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException e && QUERY_CANCELED.equals(e.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
