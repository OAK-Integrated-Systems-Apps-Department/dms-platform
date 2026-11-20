package com.oak.dms.sharing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantSharingSettingsRepository extends JpaRepository<TenantSharingSettings, UUID> {
}
