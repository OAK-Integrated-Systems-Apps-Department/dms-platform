package com.oak.dms.ownership.helper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Runs an ownership write again when Postgres picks it as the victim of a deadlock. A transfer locks its documents
 * first and writes the audit event last (the chain lock); code that edits a document through a JPA entity takes the
 * chain lock before its UPDATE is flushed, so the two can meet head-on. The database resolves that at once by
 * rolling one side back, and the retry then finds the other side finished. A lock <em>timeout</em> (a document
 * held for 5s) is not retried: it surfaces as the usual retryable 409.
 * <p>
 * Call it from outside the transaction (the controller), so each attempt is a fresh transaction.
 */
@Slf4j
@Component
public class OwnershipRetry {

    static final int ATTEMPTS = 4;

    public <T> T run(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= ATTEMPTS || !deadlock(e)) {
                    throw e;
                }
                log.info("Ownership write was a deadlock victim, retrying (attempt {} of {})", attempt + 1, ATTEMPTS);
                try {
                    Thread.sleep(25L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static boolean deadlock(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (cause instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
