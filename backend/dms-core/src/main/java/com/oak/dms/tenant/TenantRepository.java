package com.oak.dms.tenant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Tenant}. Lookups are soft-delete aware
 * (*AndDeletedFalse) since tenants are archived, never hard-deleted.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByTenantIdAndDeletedFalse(UUID tenantId);

    Optional<Tenant> findByDomainAndDeletedFalse(String domain);

    boolean existsByDomainAndDeletedFalse(String domain);

    @Query("select t from Tenant t where t.deleted = false")
    Page<Tenant> findAllActive(Pageable pageable);
}
