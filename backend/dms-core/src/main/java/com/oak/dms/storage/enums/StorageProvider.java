package com.oak.dms.storage.enums;

/**
 * Which family of {@link com.oak.dms.storage.service.ObjectStorageService} a {@link com.oak.dms.storage.entity.StorageLocation}
 * resolves to, via {@link com.oak.dms.storage.service.ObjectStorageServiceFactory}. {@code S3} covers
 * AWS S3, MinIO, the SeaweedFS S3 gateway, and any other S3-compatible
 * provider - see {@link com.oak.dms.storage.adapter.S3ObjectStorageAdapter}'s own doc comment for why
 * these deliberately share one adapter rather than one each. Adding a new
 * provider family (e.g. Azure Blob) means adding a value here, a matching
 * {@code ck_storage_locations_provider} CHECK value in the next migration,
 * and a new adapter bean - never a change to {@code DocumentService} or any
 * other business logic.
 */
public enum StorageProvider {
    S3,
    FILE_SYSTEM
}
