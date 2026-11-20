package com.oak.dms.reporting.repository;

import com.oak.dms.reporting.entity.TenantReportSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantReportSettingsRepository extends JpaRepository<TenantReportSettings, UUID> {
}
