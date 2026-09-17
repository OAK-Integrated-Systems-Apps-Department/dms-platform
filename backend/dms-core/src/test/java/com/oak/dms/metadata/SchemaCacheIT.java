package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldOptionRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.metadata.dto.ValidationError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the bug the Redis migration fixes: the old
 * {@code ConcurrentHashMap} cache in {@code MetadataValidationServiceImpl}
 * was never evicted, so editing a dropdown option (or any other
 * schema-affecting write) kept serving a stale resolved schema until the
 * server restarted. These tests exercise the real cache-aside path against
 * real Redis and Postgres containers.
 */
class SchemaCacheIT extends AbstractMetadataIntegrationTest {

    private static final String ENTRY_KEY_PREFIX = "dms:schema:";

    @Autowired
    private DocumentTypeService documentTypeService;
    @Autowired
    private MetadataFieldService metadataFieldService;
    @Autowired
    private MetadataFieldOptionService metadataFieldOptionService;
    @Autowired
    private DocumentTypeFieldService documentTypeFieldService;
    @Autowired
    private MetadataValidationService metadataValidationService;
    @Autowired
    private RedisTemplate<String, Object> schemaRedisTemplate;

    @Test
    void addingAnOptionFlushesTheCacheAndTheNewValueValidatesOnTheNextCall() {
        UUID documentTypeId = createDocumentType("CLASSIFIED_DOC").documentTypeId();
        UUID fieldId = createDropdownField("classification_level").fieldId();
        publishMandatoryMapping(documentTypeId, fieldId);

        // First call: zero active options on a mandatory dropdown field ->
        // NO_ACTIVE_OPTIONS, and this resolved schema (with an empty option
        // list) gets cached.
        List<ValidationError> beforeAdd = metadataValidationService.validate(documentTypeId, null, Map.of());
        assertThat(beforeAdd).hasSize(1);
        assertThat(beforeAdd.get(0).errorCode()).isEqualTo("NO_ACTIVE_OPTIONS");

        // Adding the option must flush the tenant's cache. Without that
        // flush, the resolved schema above (empty options) would keep being
        // served from cache indefinitely - exactly the bug being fixed.
        metadataFieldOptionService.createOption(fieldId,
                new CreateMetadataFieldOptionRequest("restricted", "Restricted", null, 10));

        List<ValidationError> afterAdd = metadataValidationService.validate(
                documentTypeId, null, Map.of("classification_level", "restricted"));
        assertThat(afterAdd).isEmpty();
    }

    @Test
    void flushClearsSiblingDocumentTypesKeysTooAndTheyRebuildCorrectly() {
        UUID fieldId = createDropdownField("shared_field").fieldId();
        UUID typeA = createDocumentType("TYPE_A").documentTypeId();
        UUID typeB = createDocumentType("TYPE_B").documentTypeId();
        publishMandatoryMapping(typeA, fieldId);
        publishMandatoryMapping(typeB, fieldId);

        metadataValidationService.validate(typeA, null, Map.of());
        metadataValidationService.validate(typeB, null, Map.of());

        String keyA = ENTRY_KEY_PREFIX + tenantId + ":" + typeA + ":2";
        String keyB = ENTRY_KEY_PREFIX + tenantId + ":" + typeB + ":2";
        assertThat(schemaRedisTemplate.opsForValue().get(keyA)).isNotNull();
        assertThat(schemaRedisTemplate.opsForValue().get(keyB)).isNotNull();

        // A write against typeA's field triggers a tenant-wide flush - both
        // typeA's AND typeB's cached keys must be gone, even though typeB
        // wasn't touched directly.
        metadataFieldOptionService.createOption(fieldId,
                new CreateMetadataFieldOptionRequest("v1", "Value 1", null, 10));

        assertThat(schemaRedisTemplate.opsForValue().get(keyA)).isNull();
        assertThat(schemaRedisTemplate.opsForValue().get(keyB)).isNull();

        // Both rebuild correctly (and independently) on the next call.
        List<ValidationError> resultA = metadataValidationService.validate(typeA, null, Map.of("shared_field", "v1"));
        List<ValidationError> resultB = metadataValidationService.validate(typeB, null, Map.of("shared_field", "v1"));
        assertThat(resultA).isEmpty();
        assertThat(resultB).isEmpty();
        assertThat(schemaRedisTemplate.opsForValue().get(keyA)).isNotNull();
        assertThat(schemaRedisTemplate.opsForValue().get(keyB)).isNotNull();
    }

    @Test
    void cacheMissFallsBackToDatabaseAndReturnsIdenticalResults() {
        UUID documentTypeId = createDocumentType("FALLBACK_DOC").documentTypeId();
        UUID fieldId = createDropdownField("fallback_field").fieldId();
        publishMandatoryMapping(documentTypeId, fieldId);

        List<ValidationError> firstCall = metadataValidationService.validate(documentTypeId, null, Map.of());

        // Bypass the application entirely and flush Redis directly, forcing
        // a genuine cache miss on the next call.
        schemaRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        List<ValidationError> secondCall = metadataValidationService.validate(documentTypeId, null, Map.of());

        assertThat(secondCall).usingRecursiveFieldByFieldElementComparator().isEqualTo(firstCall);
    }

    private DocumentTypeResponse createDocumentType(String typeCode) {
        return documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest(typeCode, typeCode, null, 0, false, false));
    }

    private MetadataFieldResponse createDropdownField(String fieldKey) {
        return metadataFieldService.createField(
                new CreateMetadataFieldRequest(fieldKey, fieldKey, null, "dropdown", null, false, true, false));
    }

    private void publishMandatoryMapping(UUID documentTypeId, UUID fieldId) {
        documentTypeFieldService.replaceMapping(documentTypeId, new ReplaceDocumentTypeFieldsRequest(List.of(
                new DocumentTypeFieldMappingItem(fieldId, true, false, 10, null, null))));
    }
}
