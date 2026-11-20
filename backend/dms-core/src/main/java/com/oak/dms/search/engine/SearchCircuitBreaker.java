package com.oak.dms.search.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal consecutive-failure breaker: after {@code failureThreshold} failures in a row
 * the circuit opens for {@code openFor}, so user requests stop paying the connect/read
 * timeout while OpenSearch is down. One success closes it. After the open window one
 * probe is allowed through (half-open by time, not by a state machine).
 */
public class SearchCircuitBreaker {

    private final int failureThreshold;
    private final Duration openFor;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.EPOCH);

    public SearchCircuitBreaker(int failureThreshold, Duration openFor) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openFor = openFor;
    }

    public boolean isOpen() {
        return Instant.now().isBefore(openUntil.get());
    }

    public void onSuccess() {
        failures.set(0);
        openUntil.set(Instant.EPOCH);
    }

    public void onFailure() {
        if (failures.incrementAndGet() >= failureThreshold) {
            openUntil.set(Instant.now().plus(openFor));
            failures.set(0);
        }
    }
}
