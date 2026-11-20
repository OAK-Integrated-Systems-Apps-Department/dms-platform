package com.oak.dms.reporting.dto;

import java.util.List;

/** Replaces ALL of the organisation's field overrides at once: a field not listed goes back to the code's defaults. */
public record AdminFieldsUpdate(List<FieldOverride> overrides) {
}
