package com.oak.dms.watermark.repository;

import com.oak.dms.watermark.entity.TenantWatermarkSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Data access for {@link TenantWatermarkSettings} - one row per tenant, keyed by tenant_id. */
public interface TenantWatermarkSettingsRepository extends JpaRepository<TenantWatermarkSettings, UUID> {
}
