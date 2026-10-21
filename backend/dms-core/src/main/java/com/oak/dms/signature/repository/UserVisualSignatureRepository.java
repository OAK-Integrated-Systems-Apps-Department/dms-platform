package com.oak.dms.signature.repository;

import com.oak.dms.signature.entity.UserVisualSignature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserVisualSignatureRepository extends JpaRepository<UserVisualSignature, UUID> {

    Optional<UserVisualSignature> findByTenantIdAndUserId(UUID tenantId, UUID userId);
}
