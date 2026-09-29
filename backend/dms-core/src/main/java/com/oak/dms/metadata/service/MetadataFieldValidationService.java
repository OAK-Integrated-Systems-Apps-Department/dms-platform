package com.oak.dms.metadata.service;

import com.oak.dms.metadata.dto.CreateMetadataFieldValidationRequest;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldValidationRequest;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for the validation rules attached to a single metadata field.
 * Distinct from {@link MetadataValidationService}, the reusable engine that
 * actually evaluates these rules against a document type's submitted values.
 */
public interface MetadataFieldValidationService {

    MetadataFieldValidationResponse createValidation(UUID fieldId, CreateMetadataFieldValidationRequest request);

    List<MetadataFieldValidationResponse> listValidations(UUID fieldId);

    MetadataFieldValidationResponse updateValidation(UUID fieldId, UUID validationId,
                                                       UpdateMetadataFieldValidationRequest request);

    void deleteValidation(UUID fieldId, UUID validationId);
}
