package com.oak.dms.metadata.service;

import com.oak.dms.metadata.dto.CreateMetadataFieldOptionRequest;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldOptionRequest;

import java.util.List;
import java.util.UUID;

public interface MetadataFieldOptionService {

    MetadataFieldOptionResponse createOption(UUID fieldId, CreateMetadataFieldOptionRequest request);

    List<MetadataFieldOptionResponse> listOptions(UUID fieldId);

    MetadataFieldOptionResponse updateOption(UUID fieldId, UUID optionId, UpdateMetadataFieldOptionRequest request);

    void deleteOption(UUID fieldId, UUID optionId);
}
