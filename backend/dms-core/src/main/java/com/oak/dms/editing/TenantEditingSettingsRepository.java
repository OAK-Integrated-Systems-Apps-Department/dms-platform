package com.oak.dms.editing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantEditingSettingsRepository extends JpaRepository<TenantEditingSettings, UUID> {
}
