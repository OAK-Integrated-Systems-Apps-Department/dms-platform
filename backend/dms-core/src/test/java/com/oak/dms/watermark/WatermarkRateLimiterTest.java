package com.oak.dms.watermark;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.service.WatermarkRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rate limits, and - the part worth testing hardest - what each one does
 * when Redis isn't there.
 * <p>
 * The two endpoints deliberately behave differently on an outage, and the
 * reasoning is asymmetric rather than arbitrary: a watermark request has two
 * harder limits behind it (a row count in Postgres, and a bounded executor),
 * so failing open costs nothing. A preview has neither - it creates no job
 * and queues no work - so failing open there would turn a Redis outage into
 * an unmetered image-rendering endpoint.
 */
class WatermarkRateLimiterTest {

    private final WatermarkProperties properties = new WatermarkProperties();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AtomicLong fakeClock = new AtomicLong(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());

    private WatermarkRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        limiter = new WatermarkRateLimiter(redis, properties, new Clock() {
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
                return Instant.ofEpochMilli(fakeClock.get());
            }
        });
        TenantContext.set(UUID.randomUUID(), UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------- the limits

    @Test
    void requestsWithinTheWindowAreAllowed() {
        properties.getRateLimit().setWatermarksPerWindow(10);
        counts(1, 2, 3);

        assertThatCode(() -> limiter.checkWatermarkRequest(request())).doesNotThrowAnyException();
    }

    @Test
    void theRequestThatExceedsTheWindowIsRefusedWith429() {
        properties.getRateLimit().setWatermarksPerWindow(3);
        counts(4L);

        assertThatThrownBy(() -> limiter.checkWatermarkRequest(request()))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void theLimitIsOffWhenConfiguredToZero() {
        // An operator switching it off must not need Redis to be reachable
        // either - nothing should be consulted at all.
        properties.getRateLimit().setWatermarksPerWindow(0);
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter.checkWatermarkRequest(request())).doesNotThrowAnyException();
    }

    @Test
    void theWindowKeyMovesOnWithTheClockSoCountersReset() {
        properties.getRateLimit().setWindow(Duration.ofMinutes(1));
        properties.getRateLimit().setWatermarksPerWindow(2);

        java.util.Set<String> keysSeen = new java.util.HashSet<>();
        when(values.increment(anyString())).thenAnswer(invocation -> {
            keysSeen.add(invocation.getArgument(0));
            return 1L;
        });

        limiter.checkWatermarkRequest(request());
        fakeClock.addAndGet(Duration.ofMinutes(2).toMillis());
        limiter.checkWatermarkRequest(request());

        assertThat(keysSeen)
                .describedAs("a later window must use different keys, or the counter never resets")
                .hasSizeGreaterThan(2);
    }

    @Test
    void bothTheUserAndTheAddressAreCounted() {
        // Either alone leaves the other open: per user misses someone
        // cycling accounts, per address misses a runaway client.
        properties.getRateLimit().setWatermarksPerWindow(5);
        java.util.List<String> keys = new java.util.ArrayList<>();
        when(values.increment(anyString())).thenAnswer(invocation -> {
            keys.add(invocation.getArgument(0));
            return 1L;
        });

        limiter.checkWatermarkRequest(request());

        assertThat(keys).anyMatch(key -> key.contains("user:"));
        assertThat(keys).anyMatch(key -> key.contains("ip:"));
    }

    @Test
    void theForwardedAddressIsUsedWhenPresent() {
        properties.getRateLimit().setWatermarksPerWindow(5);
        java.util.List<String> keys = new java.util.ArrayList<>();
        when(values.increment(anyString())).thenAnswer(invocation -> {
            keys.add(invocation.getArgument(0));
            return 1L;
        });

        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        limiter.checkWatermarkRequest(request);

        // The client, not the proxy. Only ever a bucket, never an
        // authorisation decision - so a spoofed header costs its owner
        // their own quota and nothing more.
        assertThat(keys).anyMatch(key -> key.endsWith("ip:203.0.113.7"));
    }

    // ------------------------------------------------- Redis unavailable

    @Test
    void aWatermarkRequestIsAllowedWhenRedisIsDown() {
        // Two harder limits remain behind it: the active-job row count and
        // the bounded executor. A Redis outage must not stop people
        // watermarking documents.
        properties.getRateLimit().setWatermarksPerWindow(1);
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        for (int i = 0; i < 50; i++) {
            assertThatCode(() -> limiter.checkWatermarkRequest(request())).doesNotThrowAnyException();
        }
    }

    @Test
    void previewsAreStillLimitedWhenRedisIsDown() {
        // The case that must NOT fail open: a preview creates no job and
        // queues no work, so there is nothing else metering it.
        properties.getRateLimit().setPreviewsPerWindow(3);
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        MockHttpServletRequest request = request();
        for (int i = 0; i < 3; i++) {
            limiter.checkPreviewRequest(request);
        }

        assertThatThrownBy(() -> limiter.checkPreviewRequest(request))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("too quickly")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void theInMemoryFallbackResetsWithTheWindowRatherThanBlockingForEver() {
        properties.getRateLimit().setPreviewsPerWindow(2);
        properties.getRateLimit().setWindow(Duration.ofMinutes(1));
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        MockHttpServletRequest request = request();
        limiter.checkPreviewRequest(request);
        limiter.checkPreviewRequest(request);
        assertThatThrownBy(() -> limiter.checkPreviewRequest(request)).isInstanceOf(WatermarkException.class);

        fakeClock.addAndGet(Duration.ofMinutes(2).toMillis());
        assertThatCode(() -> limiter.checkPreviewRequest(request)).doesNotThrowAnyException();
    }

    @Test
    void anExpirySetFailureDoesNotBreakTheRequest() {
        properties.getRateLimit().setWatermarksPerWindow(5);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(Duration.class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter.checkWatermarkRequest(request())).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------- helpers

    private void counts(long... sequence) {
        var stub = when(values.increment(anyString())).thenReturn(sequence[0]);
        for (int i = 1; i < sequence.length; i++) {
            stub = stub.thenReturn(sequence[i]);
        }
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.4");
        return request;
    }
}
