package com.oak.dms.reporting.repository;

import com.oak.dms.reporting.entity.ReportFieldSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReportFieldSettingRepository extends JpaRepository<ReportFieldSetting, ReportFieldSetting.Key> {

    List<ReportFieldSetting> findAllByTenantId(UUID tenantId);

    /** Every override of the organisation - the first half of a full replacement. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ReportFieldSetting s where s.tenantId = :tenant")
    int deleteAllByTenant(@Param("tenant") UUID tenantId);
}
