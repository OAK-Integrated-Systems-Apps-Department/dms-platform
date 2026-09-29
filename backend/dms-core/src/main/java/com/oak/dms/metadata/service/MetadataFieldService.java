package com.oak.dms.metadata.service;

import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Metadata field registry lifecycle within a tenant (A3).
 */
public interface MetadataFieldService {

    MetadataFieldResponse createField(CreateMetadataFieldRequest request);

    MetadataFieldResponse getField(UUID fieldId);

    Page<MetadataFieldResponse> listFields(String status, String dataType, String search, Pageable pageable);

    MetadataFieldResponse updateField(UUID fieldId, UpdateMetadataFieldRequest request);

    MetadataFieldResponse activateField(UUID fieldId);

    MetadataFieldResponse deactivateField(UUID fieldId);

    MetadataFieldResponse deprecateField(UUID fieldId);

    void deleteField(UUID fieldId);
}
