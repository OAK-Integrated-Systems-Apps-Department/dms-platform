package com.oak.dms.dashboard;

import com.oak.dms.dashboard.DashboardResponse.Documents;
import com.oak.dms.dashboard.DashboardResponse.Security;
import com.oak.dms.dashboard.DashboardResponse.Storage;
import com.oak.dms.dashboard.DashboardResponse.Usage;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.security.auth.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link DashboardCache}: what it stores and serves, and that Redis trouble only ever costs speed. */
class DashboardCacheTest {

    private final Map<String, String> store = new HashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private DashboardCache cache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(i -> {
            store.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        cache = new DashboardCache(redis, mock(DashboardService.class), mock(DigitalImpactService.class),
                mock(AuthorizationService.class), mock(DocumentAccessService.class), 60);
    }

    /** A real response - dates, nested records, empty lists - not a stand-in, so the JSON round trip is the real one. */
    private static DashboardResponse response(long total) {
        LocalDate today = LocalDate.of(2026, 10, 14);
        LocalDate start = today.minusDays(29);
        Usage usage = new Usage(1, 2, 3, 4, 5, 6);
        return new DashboardResponse(30, true, new Documents(total, 5, 4, 3, 2), new Storage(10, 5, 4, 7), usage,
                DashboardService.series(start, today, 0, Map.of(start, 2L), Map.of(today, new long[]{1, 2, 3})),
                List.of(), List.of(), List.of(), List.of(), new Security(1, 2, 3, 4, 5), List.of(), "organisation",
                List.of(), DashboardService.months(today.withDayOfMonth(1).minusMonths(11), Map.of()),
                new Usage(0, 0, 0, 0, 0, 0));
    }

    @Test
    void secondCallIsServedFromRedis_andIsIdenticalToTheFirst() {
        AtomicInteger scans = new AtomicInteger();

        DashboardResponse first = cache.cached("k", DashboardResponse.class, () -> {
            scans.incrementAndGet();
            return response(3_700_000);
        });
        DashboardResponse second = cache.cached("k", DashboardResponse.class, () -> {
            scans.incrementAndGet();
            return response(-1);
        });

        assertThat(scans).hasValue(1);
        assertThat(second).isEqualTo(first);
        verify(ops).set(eq("k"), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    void theLockIsReleasedAfterTheAnswerIsStored() {
        cache.cached("k", DashboardResponse.class, () -> response(1));

        verify(redis).delete("k:lock");
    }

    @Test
    void aFailedScanStoresNothing_andStillReleasesTheLock() {
        try {
            cache.cached("k", DashboardResponse.class, () -> {
                throw new IllegalStateException("database down");
            });
        } catch (IllegalStateException expected) {
            // propagated to the caller, as without a cache
        }

        assertThat(store).isEmpty();
        verify(redis).delete("k:lock");
    }

    @Test
    void redisDown_justWorksTheAnswerOut() {
        when(ops.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        DashboardResponse answer = cache.cached("k", DashboardResponse.class, () -> response(42));

        assertThat(answer.documents().total()).isEqualTo(42);
    }

    @Test
    void anUnreadableEntryIsTreatedAsAMiss() {
        store.put("k", "{not json");

        DashboardResponse answer = cache.cached("k", DashboardResponse.class, () -> response(7));

        assertThat(answer.documents().total()).isEqualTo(7);
    }
}
