package com.oak.dms.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OBX-04 without a database: the cleanup's control flow - off by default, bounded runs, failure handling, no overlap. What a
 * batch may delete is SQL, covered by {@code OutboxCleanupSqlSafetyTest} (the text) and {@code OutboxCleanupIT} (PostgreSQL).
 */
class OutboxCleanupTest {

    private static final long RETENTION_SECONDS = 720L * 3600;

    private final OutboxCleanupStore store = mock(OutboxCleanupStore.class);
    private final TransactionOperations tx = TransactionOperations.withoutTransaction();

    private static OutboxCleanupPolicy policy(boolean enabled, int batchSize, int maxBatches) {
        return new OutboxCleanupPolicy(enabled, 720, batchSize, 3_600_000L, maxBatches);
    }

    private OutboxCleanup cleanup(OutboxCleanupPolicy policy) {
        return new OutboxCleanup(store, tx, policy, 30_000);
    }

    // ---- off by default, and not at startup

    @Test
    void whenDisabledNothingIsDoneAndTheDatabaseIsNeverTouched() {
        TransactionOperations tripwire = mock(TransactionOperations.class);

        int deleted = new OutboxCleanup(store, tripwire, policy(false, 100, 1), 30_000).runOnce();

        assertThat(deleted).isZero();
        verifyNoInteractions(store);
        verifyNoInteractions(tripwire);
    }

    @Test
    void theDefaultPolicyIsDisabled_soADefaultInstallationNeverDeletes() {
        new OutboxCleanup(store, tx, OutboxCleanupPolicy.defaults(), 30_000).runOnce();

        verifyNoInteractions(store);
    }

    @Test
    void constructingTheCleanupDoesNothing_nothingRunsAtStartup_enabledOrNot() {
        cleanup(policy(true, 100, 1));
        cleanup(policy(false, 100, 1));

        verifyNoInteractions(store);
    }

    @Test
    void theScheduleWaitsOneFullIntervalBeforeTheFirstRun_andOneIntervalAfterEachRunEnds() throws Exception {
        Scheduled scheduled = OutboxCleanup.class.getMethod("scheduledRun").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${dms.outbox.cleanup.interval-ms:3600000}");
        assertThat(scheduled.initialDelayString()).as("no run at startup").isEqualTo(scheduled.fixedDelayString());
        assertThat(scheduled.fixedRate()).isEqualTo(-1);
    }

    @Test
    void theThreeBeansWireTogetherInARealSpringContext_andADefaultInstallationDoesNothing() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.register(OutboxCleanupPolicy.class, OutboxCleanupStore.class, OutboxCleanup.class);
            context.refresh();

            OutboxCleanup bean = context.getBean(OutboxCleanup.class);
            // Spring itself called the mocks' init callbacks while starting; what matters is what the cleanup does from here
            org.mockito.Mockito.clearInvocations(context.getBean(JdbcTemplate.class), context.getBean(PlatformTransactionManager.class));

            assertThat(context.getBean(OutboxCleanupPolicy.class).enabled()).isFalse();
            assertThat(bean.runOnce()).as("disabled by default: the database mock is never reached").isZero();
            verifyNoInteractions(context.getBean(JdbcTemplate.class));
            verifyNoInteractions(context.getBean(PlatformTransactionManager.class));
        }
    }

    // ---- bounded runs

    @Test
    void theBatchSizeAndTheRetentionAreHandedToTheStoreExactly() {
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenReturn(7);

        cleanup(policy(true, 250, 1)).runOnce();

        verify(store).deletePublishedBatch(RETENTION_SECONDS, 250);
    }

    @Test
    void aFullBatchWithTheDefaultOneBatchPerRunStopsAfterOneBatch() {
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenReturn(100);

        int deleted = cleanup(policy(true, 100, 1)).runOnce();

        assertThat(deleted).isEqualTo(100);
        verify(store, times(1)).deletePublishedBatch(anyLong(), anyInt());
    }

    @Test
    void theMaximumNumberOfBatchesPerRunIsNeverExceeded() {
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenReturn(100);               // always a full batch: more is always left

        int deleted = cleanup(policy(true, 100, 3)).runOnce();

        assertThat(deleted).isEqualTo(300);
        verify(store, times(3)).deletePublishedBatch(anyLong(), anyInt());
    }

    @Test
    void aPartialBatchMeansNothingEligibleIsLeftSoTheRunStopsEarly() {
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenReturn(100).thenReturn(40);

        int deleted = cleanup(policy(true, 100, 10)).runOnce();

        assertThat(deleted).isEqualTo(140);
        verify(store, times(2)).deletePublishedBatch(anyLong(), anyInt());
    }

    @Test
    void anEmptyRunDeletesNothingAndStopsAtOnce() {
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenReturn(0);

        int deleted = cleanup(policy(true, 100, 10)).runOnce();

        assertThat(deleted).isZero();
        verify(store, times(1)).deletePublishedBatch(anyLong(), anyInt());
    }

    @Test
    void aRunHasATimeBudget_noNewBatchIsStartedOnceItIsUsedUp() {
        AtomicInteger batches = new AtomicInteger();
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenAnswer(invocation -> {
            batches.incrementAndGet();
            Thread.sleep(40);
            return 100;
        });

        int deleted = new OutboxCleanup(store, tx, policy(true, 100, 100), 100).runOnce();     // 100 ms budget, 40 ms per batch

        assertThat(batches.get()).as("far fewer than the 100 batches the quota would allow").isBetween(2, 5);
        assertThat(deleted).isEqualTo(batches.get() * 100);
    }

    @Test
    void theBatchesAreOnlyEverCalledInsideATransaction() {
        AtomicInteger inside = new AtomicInteger();
        TransactionOperations counting = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) throws TransactionException {
                inside.incrementAndGet();
                return action.doInTransaction(null);
            }
        };
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenReturn(100);

        new OutboxCleanup(store, counting, policy(true, 100, 4), 30_000).runOnce();

        assertThat(inside.get()).as("one transaction per batch").isEqualTo(4);
    }

    // ---- failure handling

    @Test
    void aDatabaseFailureEndsTheRunWithoutThrowing_andTheNextRunWorks() {
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenThrow(new IllegalStateException("db down")).thenReturn(5);
        OutboxCleanup cleanup = cleanup(policy(true, 100, 3));

        int failed = cleanup.runOnce();                                 // must not throw: it runs on a shared scheduler thread
        int recovered = cleanup.runOnce();

        assertThat(failed).isZero();
        assertThat(recovered).isEqualTo(5);
    }

    @Test
    void aFailureInALaterBatchKeepsTheEarlierBatchesCountedAndStopsTheRun() {
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenReturn(100).thenThrow(new IllegalStateException("boom"));

        int deleted = cleanup(policy(true, 100, 5)).runOnce();

        assertThat(deleted).as("the first batch committed on its own").isEqualTo(100);
        verify(store, times(2)).deletePublishedBatch(anyLong(), anyInt());
    }

    @Test
    void aBatchWhoseTransactionFailsToCommitDeletesNothing() {
        // an in-memory "table": 300 rows; the fake store deletes inside the callback, the fake transaction rolls it back
        int[] rows = {300};
        OutboxCleanupStore fake = mock(OutboxCleanupStore.class);
        when(fake.deletePublishedBatch(anyLong(), anyInt())).thenAnswer(invocation -> {
            int n = Math.min(100, rows[0]);
            rows[0] -= n;
            return n;
        });
        TransactionOperations rollingBack = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) throws TransactionException {
                int before = rows[0];
                action.doInTransaction(null);
                rows[0] = before;                                         // the commit failed: everything the batch did is undone
                throw new org.springframework.transaction.TransactionSystemException("commit failed");
            }
        };

        int deleted = new OutboxCleanup(fake, rollingBack, policy(true, 100, 3), 30_000).runOnce();

        assertThat(deleted).as("a batch that did not commit is not counted").isZero();
        assertThat(rows[0]).as("and nothing was deleted").isEqualTo(300);
    }

    // ---- no overlap

    @Test
    void aRunThatIsStillGoingBlocksANewTickOnTheSameInstance() throws Exception {
        CountDownLatch inBatch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenAnswer(invocation -> {
            inBatch.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return 1;
        });
        OutboxCleanup cleanup = cleanup(policy(true, 100, 1));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = pool.submit(cleanup::runOnce);
            assertThat(inBatch.await(10, TimeUnit.SECONDS)).isTrue();

            int second = cleanup.runOnce();                               // a second tick while the first is mid-batch

            assertThat(second).as("skipped, not run in parallel").isZero();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        verify(store, times(1)).deletePublishedBatch(anyLong(), eq(100));
    }

    @Test
    void afterARunEndsANewOneIsAllowed() {
        when(store.deletePublishedBatch(anyLong(), anyInt())).thenReturn(0);
        OutboxCleanup cleanup = cleanup(policy(true, 100, 1));

        cleanup.runOnce();
        cleanup.runOnce();

        verify(store, times(2)).deletePublishedBatch(anyLong(), anyInt());
        verify(store, never()).deletePublishedBatch(anyLong(), eq(0));
    }
}
