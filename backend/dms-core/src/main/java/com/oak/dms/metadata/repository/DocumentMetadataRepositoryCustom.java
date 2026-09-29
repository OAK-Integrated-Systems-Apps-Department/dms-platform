package com.oak.dms.metadata.repository;

import java.util.UUID;

/** Queries on {@code document_metadata} that can't be declared as a Spring Data {@code @Query}. */
public interface DocumentMetadataRepositoryCustom {

    /**
     * Whether any document in the tenant holds a value for the field (a
     * top-level key of {@code field_values}).
     */
    boolean existsByTenantIdAndFieldKey(UUID tenantId, String fieldId);
}
