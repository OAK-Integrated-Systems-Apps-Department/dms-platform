package com.oak.dms.metadata.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * See {@link SchemaCacheService}. Live keys for a tenant are tracked in a
 * Redis SET ({@code dms:schema:keys:{tenantId}}) rather than enumerated via
 * {@code KEYS}/{@code SCAN} over the whole keyspace at eviction time - O(this
 * tenant's own key count) instead of O(every key in the database), and never
 * blocks the Redis event loop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSchemaCacheService implements SchemaCacheService {

    private static final String ENTRY_KEY_PREFIX = "dms:schema:";
    private static final String TENANT_KEY_SET_PREFIX = "dms:schema:keys:";

    /**
     * Extra headroom on the tracking set's TTL over each entry's TTL, so the
     * set outlives the entries it names. If the set expired first, a
     * concurrent put() would simply recreate it - but any entry it lost
     * track of in that gap would never be reachable by evictTenant() again
     * until its own TTL separately expired it.
     */
    private static final Duration TENANT_SET_TTL_HEADROOM = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> schemaRedisTemplate;
    private final SchemaCacheProperties properties;

    @Override
    public Optional<Object> get(UUID tenantId, UUID documentTypeId, int schemaVersion) {
        String key = entryKey(tenantId, documentTypeId, schemaVersion);
        try {
            return Optional.ofNullable(schemaRedisTemplate.opsForValue().get(key));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable reading schema cache key {} - falling back to database: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID tenantId, UUID documentTypeId, int schemaVersion, Object value) {
        String key = entryKey(tenantId, documentTypeId, schemaVersion);
        String tenantSetKey = tenantKeySet(tenantId);
        Duration ttl = Duration.ofSeconds(properties.getSchemaCacheTtlSeconds());
        try {
            schemaRedisTemplate.opsForValue().set(key, value, ttl);
            schemaRedisTemplate.opsForSet().add(tenantSetKey, key);
            schemaRedisTemplate.expire(tenantSetKey, ttl.plus(TENANT_SET_TTL_HEADROOM));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable writing schema cache key {} - skipping cache population: {}", key, e.getMessage());
        }
    }

    @Override
    public void evictTenant(UUID tenantId) {
        String tenantSetKey = tenantKeySet(tenantId);
        try {
            Set<Object> keys = schemaRedisTemplate.opsForSet().members(tenantSetKey);
            if (keys != null && !keys.isEmpty()) {
                schemaRedisTemplate.delete(keys.stream().map(String::valueOf).collect(Collectors.toSet()));
            }
            schemaRedisTemplate.delete(tenantSetKey);
        } catch (RuntimeException e) {
            // Deliberately swallowed: a failed eviction must never roll back
            // the write that triggered it. Each entry's own TTL bounds how
            // long a stale schema can be served if this happens.
            log.warn("Redis unavailable flushing schema cache for tenant {} - stale entries may serve until TTL expiry: {}",
                    tenantId, e.getMessage());
        }
    }

    private String entryKey(UUID tenantId, UUID documentTypeId, int schemaVersion) {
        return ENTRY_KEY_PREFIX + tenantId + ":" + documentTypeId + ":" + schemaVersion;
    }

    private String tenantKeySet(UUID tenantId) {
        return TENANT_KEY_SET_PREFIX + tenantId;
    }
}
