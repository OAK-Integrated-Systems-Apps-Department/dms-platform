package com.oak.dms.watermark.service;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fixed-window limit on how often one person, or one address, may ask for
 * watermarks.
 *
 * <h2>Two limits, because they stop different things</h2>
 * Per user catches a runaway client or a script; per IP catches someone
 * cycling through accounts. Either alone leaves the other open.
 *
 * <h2>Redis, and what happens when it isn't there</h2>
 * Redis holds the counters so the limit applies across instances rather than
 * per instance - {@code LoginAttemptLimiter}'s own comment names this as the
 * upgrade it wants. The project treats Redis as best-effort everywhere else
 * ({@code RedisSchemaCacheService} catches and degrades to the database), so
 * this does too.
 * <p>
 * <b>POST /watermark fails OPEN.</b> If Redis is unreachable the request is
 * allowed, because it is not the only thing standing between a user and the
 * server: {@code dms.watermark.max-active-jobs-per-user} is a count of rows
 * in Postgres, and the bounded executor refuses work beyond its queue. A
 * Redis outage should not stop people watermarking documents when two harder
 * limits remain.
 * <p>
 * <b>The preview endpoint cannot fail open</b>, because it has neither of
 * those backstops - it creates no job and queues no work, it just renders.
 * Failing open there would turn a Redis outage into an unmetered
 * image-rendering endpoint. So preview falls back to an in-memory limiter:
 * per instance rather than global, which is weaker, but a weaker limit is
 * the right answer when the alternative is none.
 */
@Slf4j
@Component
public class WatermarkRateLimiter {

    private static final String KEY_PREFIX = "dms:watermark:rate:";

    private final StringRedisTemplate redis;
    private final WatermarkProperties properties;
    private final Clock clock;
    /** Only ever used for previews, and only when Redis is unreachable - see the class comment. */
    private final ConcurrentHashMap<String, Window> localWindows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public WatermarkRateLimiter(StringRedisTemplate redis, WatermarkProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    /** Takes the clock so a test can move a fixed window on without waiting for one. */
    public WatermarkRateLimiter(StringRedisTemplate redis, WatermarkProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starting a watermark job. Fails open if Redis is unreachable. */
    public void checkWatermarkRequest(HttpServletRequest request) {
        WatermarkProperties.RateLimit limits = properties.getRateLimit();
        check("wm", limits.getWatermarksPerWindow(), limits.getWindow(), request, true,
                "You're asking for watermarks faster than they can be produced. Wait a moment and try again.");
    }

    /** Rendering a template preview. Falls back to an in-memory limiter rather than failing open. */
    public void checkPreviewRequest(HttpServletRequest request) {
        WatermarkProperties.RateLimit limits = properties.getRateLimit();
        check("pv", limits.getPreviewsPerWindow(), limits.getWindow(), request, false,
                "You're previewing watermarks too quickly. Wait a moment and try again.");
    }

    private void check(String scope, int permitted, Duration window, HttpServletRequest request,
                       boolean failOpen, String message) {
        if (permitted <= 0) {
            return;   // 0 or negative disables the limit entirely
        }
        for (String subject : subjects(request)) {
            String key = KEY_PREFIX + scope + ':' + windowStart(window) + ':' + subject;
            Long used = increment(key, window, scope, failOpen);
            if (used != null && used > permitted) {
                throw WatermarkException.rateLimited("WATERMARK_RATE_LIMITED", message);
            }
        }
    }

    /**
     * @return the count after this request, or null when it could not be
     *         established AND the caller fails open
     */
    private Long increment(String key, Duration window, String scope, boolean failOpen) {
        try {
            Long used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                // Only the request that created the window sets the expiry,
                // so a long run of requests can't keep pushing it out and
                // turn a fixed window into a sliding one that never resets.
                redis.expire(key, window.plusSeconds(1));
            }
            return used;
        } catch (RuntimeException e) {
            if (failOpen) {
                log.warn("Redis unavailable for the watermark rate limit - allowing the request: {}",
                        e.getMessage());
                return null;
            }
            log.warn("Redis unavailable for the {} rate limit - falling back to this instance's own counter: {}",
                    scope, e.getMessage());
            return localCount(key, window);
        }
    }

    /**
     * Per-instance fixed window. Weaker than the Redis one by exactly the
     * number of instances, which is the price of not leaving the preview
     * endpoint unmetered during an outage.
     */
    private long localCount(String key, Duration window) {
        long now = clock.millis();
        Window current = localWindows.compute(key, (ignored, existing) ->
                existing == null || now - existing.startedAt >= window.toMillis()
                        ? new Window(now) : existing);
        if (localWindows.size() > 10_000) {
            // The key includes the window start, so entries are naturally
            // short-lived; this only stops an unbounded accumulation if
            // Redis stays down for a long time.
            localWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= window.toMillis());
        }
        return current.count.incrementAndGet();
    }

    /**
     * Who this request counts against: the signed-in user, and the calling
     * address. Both, so neither can be used to get around the other.
     */
    private String[] subjects(HttpServletRequest request) {
        // getUserId() throws when nothing is bound. A rate limiter must not
        // be the thing that decides a request is unauthenticated - that is
        // the filter chain's job, and it has already run - so an unbound
        // context here just means "count this against the address only".
        UUID userId;
        try {
            userId = TenantContext.getUserId();
        } catch (IllegalStateException e) {
            userId = null;
        }
        String address = clientAddress(request);
        if (userId == null) {
            return new String[]{"ip:" + address};
        }
        return new String[]{"user:" + userId, "ip:" + address};
    }

    /**
     * The first address in {@code X-Forwarded-For}, or the socket's. Only
     * used as a rate-limit bucket - it is never an authorisation decision,
     * so a spoofed header costs its owner their own quota and nothing more.
     */
    private static String clientAddress(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /** The window this instant falls in, so every node agrees on the boundaries. */
    private long windowStart(Duration window) {
        long millis = Math.max(1, window.toMillis());
        return clock.millis() / millis;
    }

    private static final class Window {
        private final long startedAt;
        private final AtomicLong count = new AtomicLong();

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
