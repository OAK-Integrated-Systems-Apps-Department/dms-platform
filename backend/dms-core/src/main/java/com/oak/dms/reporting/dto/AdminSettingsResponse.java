package com.oak.dms.reporting.dto;

import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;

/** The organisation's effective settings, and the platform ceilings they can never exceed - what the settings screen validates against. */
public record AdminSettingsResponse(EffectiveReportSettings settings, ReportSettingsService.Ceilings ceilings) {
}
