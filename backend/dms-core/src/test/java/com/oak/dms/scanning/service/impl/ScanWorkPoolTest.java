package com.oak.dms.scanning.service.impl;

import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Several due files are worked on at once, each exactly once, and a failure doesn't stop the rest. */
class ScanWorkPoolTest {

    private final ScanResultRepository repository = mock(ScanResultRepository.class);
    private final IngestionProcessor processor = mock(IngestionProcessor.class);
    private final Deque<UUID> due = new ArrayDeque<>();
    private ScanWorkPool pool;

    private ScanWorkPool pool(int workers) {
        ScanProperties properties = new ScanProperties();
        properties.getRetry().setWorkers(workers);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // Stands in for claimDueWork's FOR UPDATE SKIP LOCKED: each row goes to one caller only.
        when(repository.claimDueWork(anyInt(), any(), any())).thenAnswer(invocation -> {
            synchronized (due) {
                UUID next = due.poll();
                return next == null ? List.of() : List.of(next);
            }
        });
        pool = new ScanWorkPool(repository, processor, properties, tx);
        return pool;
    }

    @AfterEach
    void stop() {
        if (pool != null) {
            pool.destroy();
        }
    }

    @Test
    void wake_worksOnTwoLargeFilesAtTheSameTime() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        due.add(first);
        due.add(second);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(processor).process(any());

        pool(3).wake();

        // With one thread the second file would only start once the first finished - which it can't here.
        assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    @Test
    void wake_processesEveryDueRowOnce_evenWhenOneFails() throws Exception {
        Set<UUID> processed = ConcurrentHashMap.newKeySet();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            due.add(UUID.randomUUID());
        }
        UUID failing = due.peekFirst();
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            calls.incrementAndGet();
            processed.add(id);
            done.countDown();
            if (id.equals(failing)) {
                throw new IllegalStateException("storage down");
            }
            return null;
        }).when(processor).process(any());

        pool(2).wake();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(processed).hasSize(5);
        assertThat(calls.get()).isEqualTo(5);
    }

    @Test
    void process_runsUnderTheRowsTenant_soItsKeyEncryptsTheDocument() throws Exception {
        UUID scanResultId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        ScanResult row = new ScanResult();
        row.setTenantId(tenantId);
        when(repository.findById(scanResultId)).thenReturn(Optional.of(row));
        due.add(scanResultId);
        AtomicReference<UUID> tenantDuring = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            tenantDuring.set(TenantContext.getTenantIdOrNull());
            done.countDown();
            return null;
        }).when(processor).process(any());

        pool(1).wake();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tenantDuring.get()).isEqualTo(tenantId);
    }
}
