package com.oak.dms.editing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TenantPlatformSettingsRepository extends JpaRepository<TenantPlatformSettings, UUID> {

    /** Every organisation the platform administrator has allowed online editing - for the organisations list. */
    @Query("select s.tenantId from TenantPlatformSettings s where s.onlineEditingAllowed = true")
    List<UUID> tenantsAllowedOnlineEditing();
}
