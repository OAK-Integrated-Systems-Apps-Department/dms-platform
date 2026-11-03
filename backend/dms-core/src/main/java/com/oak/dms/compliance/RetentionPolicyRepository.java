package com.oak.dms.compliance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicy, UUID> {

    List<RetentionPolicy> findByTenantIdAndDeletedFalseOrderByNameAsc(UUID tenantId);

    List<RetentionPolicy> findByTenantIdAndDeletedFalseAndEnabledTrue(UUID tenantId);

    Optional<RetentionPolicy> findByPolicyIdAndTenantIdAndDeletedFalse(UUID policyId, UUID tenantId);

    boolean existsByTenantIdAndScopeTypeAndScopeIdAndDeletedFalse(UUID tenantId, String scopeType, UUID scopeId);

    boolean existsByTenantIdAndScopeTypeAndScopeIdIsNullAndDeletedFalse(UUID tenantId, String scopeType);
}
