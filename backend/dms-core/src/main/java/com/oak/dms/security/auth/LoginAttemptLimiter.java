package com.oak.dms.security.auth;

import com.oak.dms.common.exception.TooManyAuthAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coarse, fixed-window rate limiter keyed by client IP, applied to the
 * public {@code /api/v1/auth/*} endpoints as defense in depth alongside
 * Keycloak's own per-username brute-force protection - which does nothing
 * to stop one IP from cycling through many different usernames, or from
 * hammering {@code /mfa-required} to enumerate accounts. Not a substitute
 * for it: Keycloak remains the source of truth for locking an individual
 * account after repeated bad attempts.
 * <p>
 * Deliberately simple and single-instance: state lives in a
 * {@link ConcurrentHashMap} with no persistence or cross-node
 * coordination. That's adequate for a single dms-core instance behind a
 * load balancer with sticky sessions or one replica; if dms-core is ever
 * horizontally scaled without sticky routing, replace this with a shared
 * store (e.g. Redis + a fixed-window or token-bucket key) so the limit
 * applies across instances rather than per-instance.
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_ATTEMPTS_PER_WINDOW = 20;
    private static final long WINDOW_MILLIS = 60_000; // 1 minute

    private final ConcurrentHashMap<String, Window> windowsByIp = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @throws TooManyAuthAttemptsException if the calling IP has exceeded the window's attempt budget. */
    public void checkAndRecord(HttpServletRequest request) {
        String ip = clientIp(request);
        long now = clock.millis();
        Window window = windowsByIp.computeIfAbsent(ip, k -> new Window(now));

        synchronized (window) {
            if (now - window.startMillis.get() > WINDOW_MILLIS) {
                window.startMillis.set(now);
                window.count.set(0);
            }
            if (window.count.incrementAndGet() > MAX_ATTEMPTS_PER_WINDOW) {
                throw new TooManyAuthAttemptsException();
            }
        }

        opportunisticCleanup(now);
    }

    /**
     * Prefers the standard proxy header (dms-core normally sits behind a
     * load balancer/reverse proxy that terminates TLS and forwards this),
     * falling back to the direct socket address for local dev without a
     * proxy in front. Only the first hop of X-Forwarded-For is trusted -
     * see the caveat below.
     */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // The first entry is whichever hop is closest to the client as
            // seen by the *nearest* proxy. This is only trustworthy if
            // every proxy in front of dms-core overwrites/appends correctly
            // and a client can't inject its own X-Forwarded-For and have it
            // pass through untouched - true for a standard reverse-proxy
            // setup (nginx/ALB/etc.) but verify this holds for your actual
            // edge topology before relying on it in production.
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Bounds the map's growth without a background thread: on roughly 1 in
     * 500 requests, sweep out windows that are long stale. Best-effort,
     * not exact - fine for a rate limiter, not fine for anything that
     * needs precise accounting.
     */
    private void opportunisticCleanup(long now) {
        if (now % 500 != 0) {
            return;
        }
        windowsByIp.entrySet().removeIf(e -> now - e.getValue().startMillis.get() > WINDOW_MILLIS * 5);
    }

    private static final class Window {
        final AtomicLong startMillis;
        final AtomicLong count = new AtomicLong(0);

        Window(long startMillis) {
            this.startMillis = new AtomicLong(startMillis);
        }
    }
}
