package com.oak.dms.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * OBX-03: when an outbox event that failed to publish is tried again, and when it is given up on.
 * <p>
 * <b>When.</b> After the n-th failed attempt the event waits {@code initialDelay * multiplier^(n-1)}, never more than
 * {@code maxDelay}, then spread by a random factor of up to +-{@code jitter} so that events that failed together (a whole batch
 * does) - and relay instances that failed together - do not come back in lock step against a recovering broker. The result is
 * always positive, so a retry can never be scheduled in the past. The schedule is persisted on the row
 * ({@code next_attempt_at}); nothing here is an in-memory timer.
 * <p>
 * <b>When to give up.</b> An event is failed for good when its first failed publish is older than {@code maxAge} (the retry
 * WINDOW - the intended rule; {@code age} is measured from {@code first_failed_at}, not from creation), or - as a runaway guard,
 * not a policy - when it has had {@code maxAttempts} attempts. The window is checked when a publish FAILS: a failure with
 * {@code age < maxAge} schedules one more attempt, even if that attempt falls after the window ends (by at most
 * {@link #maxActualDelay()}), and if that last attempt fails the event is failed; a failure at {@code age >= maxAge} - exactly
 * at the boundary included - fails it at once. An event that waited for the relay for any length of time before its first
 * failure still gets the whole window. With the defaults the window holds about 100 attempts (about 126 at the most, if every
 * jitter draw were at its minimum), so the 150-attempt guard is reached only by something runaway.
 * <p>
 * <b>Defaults - approved for development testing, NOT yet validated against production workload requirements</b>: 10 s
 * initial delay, multiplier 2, 15 min nominal cap (18 min actual, with +20 % jitter), +-20 % jitter, 24 h window, 150 attempts.
 * Everything is configurable and validated at startup: {@code dms.outbox.retry.initial-delay-ms}, {@code max-delay-ms},
 * {@code multiplier}, {@code jitter}, {@code max-age-ms}, {@code max-attempts}.
 */
@Component
public class OutboxRetryPolicy {

    public static final long DEFAULT_INITIAL_DELAY_MS = 10_000;
    public static final long DEFAULT_MAX_DELAY_MS = 15 * 60_000;
    public static final double DEFAULT_JITTER = 0.2;
    public static final long DEFAULT_MAX_AGE_MS = 24 * 60 * 60_000L;
    public static final int DEFAULT_MAX_ATTEMPTS = 150;
    public static final double DEFAULT_MULTIPLIER = 2.0;

    /** Doubling stops mattering long before this; it only keeps {@code Math.pow} far from overflow. */
    private static final int MAX_DOUBLINGS = 40;

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double jitter;
    private final long maxAgeMs;
    private final int maxAttempts;
    private final double multiplier;
    private final DoubleSupplier random;

    @Autowired
    public OutboxRetryPolicy(
            @Value("${dms.outbox.retry.initial-delay-ms:" + DEFAULT_INITIAL_DELAY_MS + "}") long initialDelayMs,
            @Value("${dms.outbox.retry.max-delay-ms:" + DEFAULT_MAX_DELAY_MS + "}") long maxDelayMs,
            @Value("${dms.outbox.retry.jitter:" + DEFAULT_JITTER + "}") double jitter,
            @Value("${dms.outbox.retry.max-age-ms:" + DEFAULT_MAX_AGE_MS + "}") long maxAgeMs,
            @Value("${dms.outbox.retry.max-attempts:" + DEFAULT_MAX_ATTEMPTS + "}") int maxAttempts,
            @Value("${dms.outbox.retry.multiplier:" + DEFAULT_MULTIPLIER + "}") double multiplier) {
        this(initialDelayMs, maxDelayMs, jitter, maxAgeMs, maxAttempts, multiplier, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** For tests: the source of randomness is injected, so jitter is deterministic. {@code random} yields values in [0, 1). */
    public OutboxRetryPolicy(long initialDelayMs, long maxDelayMs, double jitter, long maxAgeMs, int maxAttempts,
                             DoubleSupplier random) {
        this(initialDelayMs, maxDelayMs, jitter, maxAgeMs, maxAttempts, DEFAULT_MULTIPLIER, random);
    }

    public OutboxRetryPolicy(long initialDelayMs, long maxDelayMs, double jitter, long maxAgeMs, int maxAttempts,
                             double multiplier, DoubleSupplier random) {
        if (initialDelayMs <= 0) {
            throw new IllegalArgumentException("dms.outbox.retry.initial-delay-ms must be positive");
        }
        if (maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("dms.outbox.retry.max-delay-ms must not be below the initial delay");
        }
        if (!(jitter >= 0 && jitter < 1)) {
            throw new IllegalArgumentException("dms.outbox.retry.jitter must be in [0, 1)");
        }
        if (maxAgeMs <= 0) {
            throw new IllegalArgumentException("dms.outbox.retry.max-age-ms must be positive (there is no unbounded retry)");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("dms.outbox.retry.max-attempts must be positive");
        }
        if (!(multiplier >= 1.0 && multiplier <= 10.0)) {
            throw new IllegalArgumentException("dms.outbox.retry.multiplier must be between 1 and 10");
        }
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitter = jitter;
        this.maxAgeMs = maxAgeMs;
        this.maxAttempts = maxAttempts;
        this.multiplier = multiplier;
        this.random = random;
    }

    public static OutboxRetryPolicy defaults() {
        return new OutboxRetryPolicy(DEFAULT_INITIAL_DELAY_MS, DEFAULT_MAX_DELAY_MS, DEFAULT_JITTER, DEFAULT_MAX_AGE_MS,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_MULTIPLIER);
    }

    /**
     * How long to wait before the next attempt, given how many attempts have now failed (at least 1). Always positive;
     * at most {@code maxDelay * (1 + jitter)}.
     */
    public Duration delayAfter(int failedAttempts) {
        int doublings = Math.min(Math.max(failedAttempts, 1) - 1, MAX_DOUBLINGS);
        double base = Math.min((double) maxDelayMs, initialDelayMs * Math.pow(multiplier, doublings));
        double r = Math.min(Math.max(random.getAsDouble(), 0.0), Math.nextDown(1.0));      // [0, 1)
        double factor = 1.0 + jitter * (2.0 * r - 1.0);                                      // (1 - jitter, 1 + jitter)
        return Duration.ofMillis(Math.max(1L, Math.round(base * factor)));
    }

    /** Whether to stop retrying: {@code age} (time since the first failed publish) is past the window, or the runaway guard is used up. */
    public boolean isExhausted(int failedAttempts, Duration age) {
        return failedAttempts >= maxAttempts || age.toMillis() >= maxAgeMs;
    }

    /** The longest a retry delay can be: the cap with the highest jitter ({@code maxDelay * (1 + jitter)}, 18 min by default). */
    public Duration maxActualDelay() {
        return Duration.ofMillis((long) Math.ceil(maxDelayMs * (1.0 + jitter)));
    }

    public long maxAgeMs() {
        return maxAgeMs;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
