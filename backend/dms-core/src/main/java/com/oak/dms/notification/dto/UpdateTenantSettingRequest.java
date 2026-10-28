package com.oak.dms.notification.dto;

import jakarta.validation.constraints.NotNull;

/** Set a standard/optional category's tenant email default (3.2, N12). */
public record UpdateTenantSettingRequest(@NotNull Boolean emailEnabledDefault) {
}
