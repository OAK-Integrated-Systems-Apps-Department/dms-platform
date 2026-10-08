package com.oak.dms.folder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What {@link FolderAccessService} and {@link DocumentAccessService} work out
 * on every request, kept between requests: each tenant's folder tree and
 * whether it has restricted documents, and each user's access entries.
 * <p>
 * Every entry is keyed by the tenant's <em>access generation</em>
 * (access_generations, V61), which database triggers move on whenever a
 * folder, an access entry, a role, a role assignment or a restricted document
 * changes - so a change takes effect on the very next request, on every
 * instance, and no code path can forget to invalidate. Reading the
 * generation is one primary-key lookup per check.
 * <p>
 * Admin status is never cached (it comes from the caller's token). Folders
 * are handed out as copies, so a caller changing one can't change the cache.
 * Nothing is stored from inside a read-write transaction: it may see its
 * own uncommitted changes, which must not outlive a rollback. A TTL bounds
 * everything as a backstop; zero turns the cache off.
 */
@Component
public class AccessCache {

    private final JdbcTemplate jdbc;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Cached> entries;

    private record Cached(long generation, Instant expires, Object value) {
    }

    @Autowired
    public AccessCache(JdbcTemplate jdbc, @Value("${dms.access.cache-ttl:PT5M}") Duration ttl,
                       @Value("${dms.access.cache-max-entries:10000}") int maxEntries) {
        this(jdbc, ttl, maxEntries, Clock.systemUTC());
    }

    AccessCache(JdbcTemplate jdbc, Duration ttl, int maxEntries, Clock clock) {
        this.jdbc = jdbc;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > AccessCache.this.maxEntries;
            }
        };
    }

    /** The tenant's live folders - copies, safe to change. */
    public List<Folder> folders(UUID tenantId, Supplier<List<Folder>> load) {
        List<Folder> folders = get("folders:" + tenantId, tenantId, () -> load.get().stream()
                .map(AccessCache::copy).toList());
        return folders.stream().map(AccessCache::copy).toList();
    }

    public boolean anyRestrictedDocuments(UUID tenantId, Supplier<Boolean> load) {
        return get("restricted-docs:" + tenantId, tenantId, load);
    }

    /** The best level the user's own and their roles' entries give, per restricted folder. */
    public Map<UUID, FolderAccessLevel> folderGrants(UUID tenantId, UUID userId,
                                                     Supplier<Map<UUID, FolderAccessLevel>> load) {
        return get("folder-grants:" + tenantId + ":" + userId, tenantId, () -> Map.copyOf(load.get()));
    }

    /** The same per restricted document. */
    public Map<UUID, FolderAccessLevel> documentGrants(UUID tenantId, UUID userId,
                                                       Supplier<Map<UUID, FolderAccessLevel>> load) {
        return get("document-grants:" + tenantId + ":" + userId, tenantId, () -> Map.copyOf(load.get()));
    }

    /** For tests and diagnostics. */
    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String key, UUID tenantId, Supplier<T> load) {
        if (ttl.isZero() || ttl.isNegative()) {
            return load.get();
        }
        // The generation first, then the data: what's stored is at least as new as its key.
        long generation = generation(tenantId);
        Instant now = clock.instant();
        synchronized (entries) {
            Cached hit = entries.get(key);
            if (hit != null && hit.generation() == generation && hit.expires().isAfter(now)) {
                return (T) hit.value();
            }
        }
        T value = load.get();
        if (mayStore()) {
            synchronized (entries) {
                entries.put(key, new Cached(generation, now.plus(ttl), value));
            }
        }
        return value;
    }

    private long generation(UUID tenantId) {
        List<Long> rows = jdbc.queryForList("select generation from access_generations where tenant_id = ?",
                Long.class, tenantId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** Not from a read-write transaction - it may be looking at its own changes, which could still roll back. */
    private static boolean mayStore() {
        return !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }

    private static Folder copy(Folder folder) {
        return folder.toBuilder().build();
    }
}
