package com.oak.dms.workflow.dto;

import java.util.UUID;

/** One pickable user / role / document type for the stage builder. hint: a user's email, else null. */
public record LookupOptionResponse(UUID id, String label, String hint) {
}
