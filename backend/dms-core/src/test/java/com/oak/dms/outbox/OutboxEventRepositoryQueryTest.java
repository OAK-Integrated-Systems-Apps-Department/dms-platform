package com.oak.dms.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBX-01: pins the shape of the query the relay uses to pick its batch, without a database. Text and annotation checks -
 * they catch an accidental edit; the behaviour itself (two transactions never getting the same row) is exercised
 * against PostgreSQL by {@code OutboxRelayConcurrencyIT}.
 * <p>
 * Written against the ORIGINAL repository first: it took no lock, which is the defect, so the first test fails there.
 */
class OutboxEventRepositoryQueryTest {

    private static String normalised(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /** The select(s) the repository declares over outbox rows that are still pending. */
    private static List<String> pendingBatchQueries() {
        return Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(Query.class))
                .filter(q -> q != null)
                .map(q -> normalised(q.value()))
                .filter(q -> q.startsWith("select") && !q.startsWith("select count")
                        && (q.contains("outbox_events") || q.contains("outboxevent"))
                        && (q.contains("'pending'") || q.contains("status.pending")))
                .toList();
    }

    @Test
    void theBatchQueryRowLocksWhatItSelectsAndSkipsRowsAnotherRelayHolds() {
        List<String> queries = pendingBatchQueries();

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0)).contains("for update skip locked");
    }

    @Test
    void theBatchQueryTakesOnlyPendingRowsOldestFirstUpToTheBatchSize() {
        String sql = pendingBatchQueries().get(0);

        assertThat(sql).contains("status = 'pending'");
        assertThat(sql).as("deterministic order among the rows that are due: due time, then creation, then id")
                .contains("order by next_attempt_at asc, created_at asc, outbox_event_id asc");
        assertThat(sql).contains("limit :limit");
        // the lock clause comes last, after ordering and limit
        assertThat(sql.indexOf("for update skip locked")).isGreaterThan(sql.indexOf("limit :limit"));
    }

    @Test
    void theBatchQuerySelectsOnlyEventsWhoseRetryTimeHasArrived_onTheDatabaseClock() {
        String sql = pendingBatchQueries().get(0);

        assertThat(sql).contains("next_attempt_at <= now()");
        // the eligibility test is part of the same locked select, before ordering and limit
        assertThat(sql.indexOf("next_attempt_at <= now()")).isLessThan(sql.indexOf("order by"));
    }

    @Test
    void theRetryTimeIsReadFromTheDatabaseClockNotTheTransactionStartTime() throws Exception {
        Method clock = OutboxEventRepository.class.getDeclaredMethod("databaseClockMillis");

        String sql = normalised(clock.getAnnotation(Query.class).value());
        assertThat(sql).contains("clock_timestamp()");
        assertThat(sql).doesNotContain("now()");
        assertThat(clock.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void theBatchQueryIsNativeSqlSoTheLockingClauseIsExactlyWhatIsWritten() throws Exception {
        Method locking = OutboxEventRepository.class.getDeclaredMethod("lockPendingBatch", int.class);

        assertThat(locking.getAnnotation(Query.class).nativeQuery()).isTrue();
    }

    @Test
    void theLockingMethodsRefuseToRunWithoutATransaction() throws Exception {
        Method find = OutboxEventRepository.class.getMethod("findBatchToPublish", org.springframework.data.domain.Pageable.class);
        Method locking = OutboxEventRepository.class.getDeclaredMethod("lockPendingBatch", int.class);

        for (Method method : List.of(find, locking)) {
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).as(method.getName()).isEqualTo(Propagation.MANDATORY);
        }
    }

    @Test
    void theCountOfDeadLetteredEventsIsUnchangedAndTakesNoLock() throws Exception {
        Method count = OutboxEventRepository.class.getDeclaredMethod("countDeadLettered", int.class);

        String sql = normalised(count.getAnnotation(Query.class).value());
        assertThat(sql).startsWith("select count(e)");
        assertThat(sql).doesNotContain("for update");
    }
}
