package com.oak.dms.signature.repository;

import com.oak.dms.signature.entity.UserSigningKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSigningKeyRepository extends JpaRepository<UserSigningKey, UUID> {

    Optional<UserSigningKey> findByTenantIdAndUserIdAndStatus(UUID tenantId, UUID userId, UserSigningKey.Status status);

    /** Highest generation this user has ever had, active or revoked - the next key's generation is one more. */
    Optional<UserSigningKey> findFirstByTenantIdAndUserIdOrderByGenerationDesc(UUID tenantId, UUID userId);
}
