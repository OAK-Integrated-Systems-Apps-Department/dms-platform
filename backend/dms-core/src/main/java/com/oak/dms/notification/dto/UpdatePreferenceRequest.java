package com.oak.dms.notification.dto;

import jakarta.validation.constraints.NotNull;

/** Turn a standard/optional category's email on or off for the caller (3.2, N12). */
public record UpdatePreferenceRequest(@NotNull Boolean emailEnabled) {
}
