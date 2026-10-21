package com.oak.dms.signature.dto;

import java.util.UUID;

/** The new, stamped version - sign-and-approve (called right after this) signs exactly this version. */
public record PlaceSignatureResponse(UUID versionId, int versionNumber) {
}
