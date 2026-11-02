package com.oak.dms.ownership.dto;

import java.util.List;
import java.util.UUID;

/** Documents left out of a transfer for one reason. Ids are listed only where the caller may know them (never for hidden documents). */
public record SkippedDocuments(String reason, int count, List<UUID> documentIds) {

    public static final String NOT_FOUND = "not_found";
    public static final String PURGED = "purged";
    public static final String ALREADY_OWNER = "already_owner";
    public static final String OUT_OF_SCOPE = "out_of_scope";
}
