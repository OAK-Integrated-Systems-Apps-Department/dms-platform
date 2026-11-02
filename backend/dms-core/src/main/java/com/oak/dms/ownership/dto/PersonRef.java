package com.oak.dms.ownership.dto;

import java.util.UUID;

/** A person as ownership screens show them. {@code status} is active, locked, disabled, deactivated, or null when the account no longer exists. */
public record PersonRef(UUID userId, String name, String status) {
}
