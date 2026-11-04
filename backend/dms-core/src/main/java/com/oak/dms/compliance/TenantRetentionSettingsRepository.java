package com.oak.dms.compliance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TenantRetentionSettingsRepository extends JpaRepository<TenantRetentionSettings, UUID> {

    /** Organisations that have the automatic job switched off - everyone else (including no row) runs it. */
    @Query("select s.tenantId from TenantRetentionSettings s where s.automationEnabled = false")
    List<UUID> tenantsWithAutomationOff();
}
