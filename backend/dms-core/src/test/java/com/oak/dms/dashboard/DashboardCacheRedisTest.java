package com.oak.dms.dashboard;

import com.oak.dms.dashboard.DashboardResponse.Documents;
import com.oak.dms.dashboard.DashboardResponse.Security;
import com.oak.dms.dashboard.DashboardResponse.Storage;
import com.oak.dms.dashboard.DashboardResponse.Usage;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.security.auth.AuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link DashboardCache} against a real Redis: what the mocked {@link DashboardCacheTest} can't show -
 * a repeat call really is served from Redis, simultaneous callers really share one scan, and a dead
 * Redis really costs only speed. Skipped when no Redis answers on {@code test.redis.host}:{@code test.redis.port}
 * (default localhost:6379, no password; {@code test.redis.password} if it has one).
 */
class DashboardCacheRedisTest {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private DashboardCache cache;
    private String key;

    private static LettuceConnectionFactory factory(String host, int port, String password) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        LettuceConnectionFactory f = new LettuceConnectionFactory(config, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2)).build());
        f.afterPropertiesSet();
        return f;
    }

    private static DashboardCache cacheOver(StringRedisTemplate redis) {
        return new DashboardCache(redis, mock(DashboardService.class), mock(DigitalImpactService.class),
                mock(AuthorizationService.class), mock(DocumentAccessService.class), 60);
    }

    @BeforeEach
    void setUp() {
        factory = factory(System.getProperty("test.redis.host", "localhost"),
                Integer.getInteger("test.redis.port", 6379), System.getProperty("test.redis.password"));
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        try {
            redis.opsForValue().get("dms:test:ping");
        } catch (RuntimeException e) {
            Assumptions.abort("No Redis to test against: " + e.getMessage());
        }
        cache = cacheOver(redis);
        key = DashboardCache.PREFIX + "test:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (redis != null && key != null) {
            try {
                redis.delete(List.of(key, key + ":lock"));
            } catch (RuntimeException ignored) {
                // nothing to clean up
            }
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    private static DashboardResponse response(long total) {
        LocalDate today = LocalDate.of(2026, 10, 14);
        return new DashboardResponse(30, true, new Documents(total, 5, 4, 3, 2), new Storage(10, 5, 4, 7),
                new Usage(1, 2, 3, 4, 5, 6),
                DashboardService.series(today.minusDays(29), today, 0, Map.of(), Map.of()),
                List.of(), List.of(), List.of(), List.of(), new Security(1, 2, 3, 4, 5), List.of(), "organisation",
                List.of(), DashboardService.months(today.withDayOfMonth(1).minusMonths(11), Map.of()),
                new Usage(0, 0, 0, 0, 0, 0));
    }

    private static long millis(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void aRepeatCallIsServedFromRedis_farFasterThanTheScan() {
        AtomicInteger scans = new AtomicInteger();
        Runnable call = () -> cache.cached(key, DashboardResponse.class, () -> {
            scans.incrementAndGet();
            sleep(1500); // the scan
            return response(3_700_000);
        });

        long first = millis(call);
        // The first read in a JVM also builds Jackson's reader for the response type; a running server pays that once.
        long coldRepeat = millis(call);
        long warmRepeat = millis(call);
        System.out.printf("DASHBOARD CACHE: first call %d ms (scan), first repeat %d ms (cold JSON reader),"
                + " warm repeat %d ms, scans run: %d%n", first, coldRepeat, warmRepeat, scans.get());

        assertThat(scans).hasValue(1);
        assertThat(first).isGreaterThanOrEqualTo(1500);
        assertThat(coldRepeat).isLessThan(1000);
        assertThat(warmRepeat).isLessThan(100);
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    }

    @Test
    void simultaneousCallersShareOneScan() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        int callers = 5;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            long start = System.nanoTime();
            List<Future<DashboardResponse>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> cache.cached(key, DashboardResponse.class, () -> {
                    scans.incrementAndGet();
                    sleep(1500);
                    return response(3_700_000);
                })));
            }
            for (Future<DashboardResponse> result : results) {
                assertThat(result.get().documents().total()).isEqualTo(3_700_000);
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("DASHBOARD CACHE: %d simultaneous callers, scans run: %d, all done in %d ms%n",
                    callers, scans.get(), elapsed);

            assertThat(scans).hasValue(1);
            // Waiting for the one scan, not running five in turn.
            assertThat(elapsed).isLessThan(4000);
            assertThat(redis.hasKey(key + ":lock")).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whenRedisIsUnreachable_theAnswerIsStillWorkedOut() {
        LettuceConnectionFactory dead = factory("localhost", 1, null); // nothing listens on port 1
        try {
            StringRedisTemplate deadRedis = new StringRedisTemplate(dead);
            deadRedis.afterPropertiesSet();
            DashboardCache offline = cacheOver(deadRedis);

            long[] took = new long[1];
            DashboardResponse[] answer = new DashboardResponse[1];
            took[0] = millis(() -> answer[0] = offline.cached(key, DashboardResponse.class, () -> response(99)));
            System.out.printf("DASHBOARD CACHE: Redis unreachable - answer still returned, in %d ms%n", took[0]);

            assertThat(answer[0].documents().total()).isEqualTo(99);
        } finally {
            dead.destroy();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
