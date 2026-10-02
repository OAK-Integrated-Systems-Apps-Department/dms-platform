package com.oak.dms.encryption;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The unwrapped-key cache: copies only, expiry, a size bound - and wiped keys when they leave. */
class DataKeyCacheTest {

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

    @Test
    void returnsCopies_soNoCallerCanWipeOrChangeTheCachedKey() {
        DataKeyCache cache = new DataKeyCache(Duration.ofMinutes(5), 10, clock);
        byte[] key = {1, 2, 3};
        cache.put("openbao", "t1", "vault:v1:a", key);
        key[0] = 9; // the caller wipes or reuses its own copy

        byte[] first = cache.get("openbao", "t1", "vault:v1:a").orElseThrow();
        first[1] = 0;
        assertThat(cache.get("openbao", "t1", "vault:v1:a").orElseThrow()).containsExactly(1, 2, 3);
        // The same wrapped string under another scope or service is another key.
        assertThat(cache.get("openbao", "t2", "vault:v1:a")).isEmpty();
        assertThat(cache.get("local-dev", "t1", "vault:v1:a")).isEmpty();
    }

    @Test
    void expiresAfterItsTtl_andStaysWithinItsSize() {
        DataKeyCache cache = new DataKeyCache(Duration.ofMinutes(5), 2, clock);
        cache.put("p", "s", "a", new byte[]{1});
        cache.put("p", "s", "b", new byte[]{2});
        cache.put("p", "s", "c", new byte[]{3}); // pushes out the oldest

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("p", "s", "a")).isEmpty();

        now.set(now.get().plus(Duration.ofMinutes(6)));
        assertThat(cache.get("p", "s", "c")).isEmpty();
    }

    @Test
    void aZeroTtl_turnsItOff() {
        DataKeyCache cache = new DataKeyCache(Duration.ZERO, 10, clock);
        cache.put("p", "s", "a", new byte[]{1});

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.get("p", "s", "a")).isEmpty();
    }
}
