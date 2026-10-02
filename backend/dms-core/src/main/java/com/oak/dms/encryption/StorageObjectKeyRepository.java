package com.oak.dms.encryption;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageObjectKeyRepository extends JpaRepository<StorageObjectKey, UUID> {

    Optional<StorageObjectKey> findByStorageLocationIdAndObjectKey(UUID storageLocationId, String objectKey);

    @Modifying
    @Transactional
    @Query("delete from StorageObjectKey k where k.storageLocationId = :locationId and k.objectKey = :objectKey")
    int deleteObject(@Param("locationId") UUID storageLocationId, @Param("objectKey") String objectKey);

    boolean existsByKeyProvider(String keyProvider);

    /** One wrapped key per (scope, version) a provider has made, as [scope, wrappedKey] - the startup check. */
    @Query(value = "select distinct on (key_scope, key_version) key_scope, wrapped_key from storage_object_keys "
            + "where key_provider = :provider order by key_scope, key_version, created_at", nativeQuery = true)
    List<Object[]> sampleWrappedKeys(@Param("provider") String provider);

    /** A tenant's objects already on its own key with the primary service, as [keyVersion, count]. */
    @Query("select k.keyVersion, count(k) from StorageObjectKey k where k.tenantId = :tenantId "
            + "and k.keyProvider = :provider and k.keyScope = :scope group by k.keyVersion order by k.keyVersion")
    List<Object[]> countByVersion(@Param("tenantId") UUID tenantId, @Param("provider") String provider,
                                  @Param("scope") String scope);

    long countByTenantId(UUID tenantId);

    /**
     * A tenant's keys that aren't yet on its own latest master key with the primary service - older
     * version, another scope (the pre-V57 platform key) or another service. Oldest first.
     */
    @Query("select k from StorageObjectKey k where k.tenantId = :tenantId and (k.keyProvider <> :provider "
            + "or k.keyScope <> :scope or k.keyVersion < :version) order by k.createdAt, k.storageObjectKeyId")
    List<StorageObjectKey> findToRewrap(@Param("tenantId") UUID tenantId, @Param("provider") String provider,
                                        @Param("scope") String scope, @Param("version") int version,
                                        Pageable batch);

    @Query("select count(k) from StorageObjectKey k where k.tenantId = :tenantId and (k.keyProvider <> :provider "
            + "or k.keyScope <> :scope or k.keyVersion < :version)")
    long countToRewrap(@Param("tenantId") UUID tenantId, @Param("provider") String provider,
                       @Param("scope") String scope, @Param("version") int version);
}
