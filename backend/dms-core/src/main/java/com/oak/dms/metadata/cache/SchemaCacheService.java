package com.oak.dms.metadata.cache;

import java.util.Optional;
import java.util.UUID;

/**
 * Cache-aside store for a document type's resolved validation schema (see
 * {@code MetadataValidationServiceImpl}), backed by Redis.
 * <p>
 * Explicit eviction via {@link #evictTenant(UUID)} is the primary
 * correctness mechanism, not the TTL: every write that can change a
 * resolved schema (option create/update/activate/deactivate/delete,
 * validation rule create/update/delete, field update/status change, mapping
 * publish) must call it, so a resolved schema is never served after the
 * data it was built from has changed. The TTL applied in {@link #put} is
 * only a backstop for a missed or failed eviction, not the primary
 * invalidation path.
 * <p>
 * Every method must degrade gracefully when Redis is unreachable: a read
 * returns empty (the caller falls through to the database), a write/evict
 * is a silent no-op, and both log a warning - the application must keep
 * serving requests even when the cache is completely down.
 */
public interface SchemaCacheService {

    Optional<Object> get(UUID tenantId, UUID documentTypeId, int schemaVersion);

    void put(UUID tenantId, UUID documentTypeId, int schemaVersion, Object value);

    /**
     * Deletes every cached schema for the tenant, regardless of which
     * document type or version it belongs to. Deliberately a full flush
     * rather than a targeted per-field/per-document-type eviction: schema
     * edits are rare administrative actions, so the cost of over-flushing
     * is negligible, and a reverse lookup from field/option/rule to the
     * document types it affects is exactly the kind of tracking that drifts
     * out of sync and reintroduces stale data.
     */
    void evictTenant(UUID tenantId);
}
