package com.oak.dms.metadata.dto;

import java.util.UUID;

public record MetadataFieldOptionResponse(
        UUID optionId,
        UUID fieldId,
        UUID parentOptionId,
        String optionValue,
        String optionLabel,
        int displayOrder,
        String status
) {
}
