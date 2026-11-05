package com.oak.dms.preview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantPreviewPolicyRepository extends JpaRepository<TenantPreviewPolicy, UUID> {
}
