package com.oak.dms.encryption;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unwrapped data keys kept briefly in memory, so a bulk export or a burst of
 * previews asks the key service once per key instead of once per read. Bounded
 * in time ({@code dms.encryption.key-cache-ttl}) and size; a key is wiped
 * (overwritten with zeros) when it expires or is pushed out, and callers only
 * ever get a copy. Never written anywhere - a restart empties it.
 */
@Component
public class DataKeyCache {

    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);

    private record Entry(byte[] key, Instant expires) {
    }

    @Autowired
    public DataKeyCache(EncryptionProperties properties) {
        this(properties.getKeyCacheTtl(), properties.getKeyCacheMaxEntries(), Clock.systemUTC());
    }

    DataKeyCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = ttl == null ? Duration.ZERO : ttl;
        this.maxEntries = Math.max(0, maxEntries);
        this.clock = clock;
    }

    public boolean enabled() {
        return !ttl.isZero() && !ttl.isNegative() && maxEntries > 0;
    }

    /** A copy of the cached key - the caller wipes its own copy when done. */
    public synchronized Optional<byte[]> get(String provider, String scope, String wrappedKey) {
        if (!enabled()) {
            return Optional.empty();
        }
        String id = id(provider, scope, wrappedKey);
        Entry entry = entries.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expires().isBefore(clock.instant())) {
            wipe(entries.remove(id));
            return Optional.empty();
        }
        return Optional.of(entry.key().clone());
    }

    /** Keeps a copy of {@code dataKey}; the caller still owns (and wipes) its own. */
    public synchronized void put(String provider, String scope, String wrappedKey, byte[] dataKey) {
        if (!enabled() || dataKey == null) {
            return;
        }
        Entry previous = entries.put(id(provider, scope, wrappedKey),
                new Entry(dataKey.clone(), clock.instant().plus(ttl)));
        if (previous != null) {
            wipe(previous);
        }
        evict();
    }

    /** Wipes everything - after a rotation the old keys are of no further use here. */
    public synchronized void clear() {
        entries.values().forEach(DataKeyCache::wipe);
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    private void evict() {
        Instant now = clock.instant();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> next = it.next();
            if (entries.size() > maxEntries || next.getValue().expires().isBefore(now)) {
                wipe(next.getValue());
                it.remove();
            } else if (entries.size() <= maxEntries) {
                break; // access-ordered: the rest are newer
            }
        }
    }

    private static void wipe(Entry entry) {
        if (entry != null) {
            Arrays.fill(entry.key(), (byte) 0);
        }
    }

    /** The wrapped key identifies the data key; provider and scope keep identical strings apart. */
    private static String id(String provider, String scope, String wrappedKey) {
        return provider + "|" + scope + "|" + wrappedKey;
    }
}
