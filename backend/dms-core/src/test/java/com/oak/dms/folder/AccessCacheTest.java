package com.oak.dms.folder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Access data reused until the tenant's access generation moves on - and never kept from a write. */
class AccessCacheTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final AccessCache cache = new AccessCache(jdbc, Duration.ofMinutes(5), 100, clock);
    private final AtomicInteger loads = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }
    }

    private void generation(long value) {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(tenant))).thenReturn(List.of(value));
    }

    private Map<UUID, FolderAccessLevel> grants() {
        return cache.folderGrants(tenant, user, () -> {
            loads.incrementAndGet();
            return Map.of(UUID.randomUUID(), FolderAccessLevel.view);
        });
    }

    @Test
    void reusedWhileTheGenerationStands_reloadedTheMomentItMovesOn() {
        generation(7);
        var first = grants();
        assertThat(grants()).isSameAs(first);
        assertThat(loads).hasValue(1);

        generation(8); // someone changed a folder, an entry or a role
        assertThat(grants()).isNotSameAs(first);
        assertThat(loads).hasValue(2);
    }

    @Test
    void theTtlIsABackstop() {
        generation(7);
        grants();
        now.set(now.get().plus(Duration.ofMinutes(6)));
        grants();
        assertThat(loads).hasValue(2);
    }

    @Test
    void foldersComeBackAsCopies_soChangingOneCantChangeTheCache() {
        generation(1);
        Folder stored = Folder.builder().folderId(UUID.randomUUID()).tenantId(tenant).folderName("Finance")
                .accessMode(FolderAccessMode.restricted).build();
        Folder mine = cache.folders(tenant, () -> List.of(stored)).get(0);
        mine.setAccessMode(FolderAccessMode.everyone);

        assertThat(cache.folders(tenant, List::of).get(0).getAccessMode()).isEqualTo(FolderAccessMode.restricted);
    }

    @Test
    void nothingIsKeptFromInsideAReadWriteTransaction_itMayRollBack() {
        generation(3);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        grants();
        grants();
        assertThat(loads).hasValue(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    void aZeroTtl_turnsItOff() {
        AccessCache off = new AccessCache(jdbc, Duration.ZERO, 100, clock);
        generation(1);
        off.folderGrants(tenant, user, () -> {
            loads.incrementAndGet();
            return Map.of();
        });
        off.folderGrants(tenant, user, () -> {
            loads.incrementAndGet();
            return Map.of();
        });
        assertThat(loads).hasValue(2);
    }
}
