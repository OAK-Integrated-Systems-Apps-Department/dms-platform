package com.oak.dms.metadata;

import com.oak.dms.common.exception.DuplicateDisplayOrderException;
import com.oak.dms.common.exception.DuplicateFieldMappingException;
import com.oak.dms.common.exception.InvalidMetadataFieldStateException;
import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.entity.DocumentTypeSchema;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.DocumentTypeSchemaRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.DocumentTypeService;
import com.oak.dms.metadata.service.MetadataFieldService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the {@code document_type_schemas} /
 * {@code document_metadata} storage model introduced by V36: one JSONB row
 * per (document type, schema version), and one JSONB row per document.
 * Response-shape parity with the pre-V36 implementation is covered
 * separately by {@link DocumentTypeFieldSnapshotIT}; the V36 backfill
 * itself by {@link V36MigrationIT}.
 */
@SpringBootTest
class DocumentTypeSchemaIT {

    @Autowired
    private DocumentTypeService documentTypeService;
    @Autowired
    private DocumentTypeFieldService documentTypeFieldService;
    @Autowired
    private MetadataFieldService metadataFieldService;
    @Autowired
    private MetadataValidationService metadataValidationService;
    @Autowired
    private DocumentTypeRepository documentTypeRepository;
    @Autowired
    private DocumentTypeSchemaRepository documentTypeSchemaRepository;
    @Autowired
    private DocumentMetadataRepository documentMetadataRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private EntityManager entityManager;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        tenantId = createTenant();
        UUID keycloakId = UUID.randomUUID();
        User admin = userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@schema-it.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Admin")
                .displayName("Test Admin")
                .status(User.UserStatus.active)
                .build());
        userId = admin.getUserId();
        TenantContext.set(tenantId, userId, admin.getDisplayName(), keycloakId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void schemaRow_roundTripsFieldMappingsAsJson() {
        DocumentType type = documentTypeRepository.saveAndFlush(DocumentType.builder()
                .tenantId(tenantId)
                .typeCode(uniqueTypeCode())
                .typeName("Round trip")
                .displayOrder(10)
                .build());
        List<DocumentTypeSchema.FieldMapping> fields = List.of(
                new DocumentTypeSchema.FieldMapping(UUID.randomUUID(), UUID.randomUUID(), true, false, true, 10, "G", null),
                new DocumentTypeSchema.FieldMapping(UUID.randomUUID(), UUID.randomUUID(), false, true, false, 20, null, "d"));
        documentTypeSchemaRepository.saveAndFlush(DocumentTypeSchema.builder()
                .tenantId(tenantId)
                .documentTypeId(type.getDocumentTypeId())
                .schemaVersion(1)
                .fields(fields)
                .publishedBy(userId)
                .build());

        DocumentTypeSchema loaded = documentTypeSchemaRepository
                .findByDocumentTypeIdAndTenantIdAndSchemaVersion(type.getDocumentTypeId(), tenantId, 1)
                .orElseThrow();
        assertThat(loaded.getFields()).containsExactlyElementsOf(fields);
        assertThat(documentTypeSchemaRepository.existsByField(fields.get(1).fieldId(), tenantId)).isTrue();
        assertThat(documentTypeSchemaRepository.existsByField(fields.get(1).fieldId(), UUID.randomUUID())).isFalse();
        assertThat(documentTypeSchemaRepository.countDistinctDocumentTypesByField(fields.get(0).fieldId(), tenantId))
                .isEqualTo(1);
    }

    @Test
    void createDocumentType_insertsEmptyActiveV1SchemaRow() {
        UUID typeId = createType();

        assertThat(schemaRowCount(typeId)).isEqualTo(1);
        DocumentTypeSchema v1 = schemaAt(typeId, 1);
        assertThat(v1.getStatus()).isEqualTo(DocumentTypeSchema.SchemaStatus.active);
        assertThat(v1.getFields()).isEmpty();
        assertThat(v1.getArchivedAt()).isNull();
        assertThat(v1.getPublishedBy()).isEqualTo(userId);
    }

    @Test
    void neverPublishedType_readsAsEmptyMapping() {
        UUID typeId = createType();

        assertThat(documentTypeFieldService.getCurrentFields(typeId)).isEmpty();
        assertThat(documentTypeFieldService.getFieldsAtVersion(typeId, 1)).isEmpty();
        assertThat(documentTypeFieldService.getSchema(typeId).fields()).isEmpty();
        assertThat(documentTypeFieldService.getSchema(typeId).schemaVersion()).isEqualTo(1);
        assertThat(metadataValidationService.validate(typeId, null, Map.of())).isEmpty();
    }

    @Test
    void typeWithNoSchemaRow_readsAsEmptyMappingNot404() {
        // A type created by a pod still on the previous release (no schema
        // row insert) during a rolling deploy.
        DocumentType legacy = documentTypeRepository.saveAndFlush(DocumentType.builder()
                .tenantId(tenantId)
                .typeCode(uniqueTypeCode())
                .typeName("Created by old release")
                .displayOrder(10)
                .build());
        UUID typeId = legacy.getDocumentTypeId();
        assertThat(schemaRowCount(typeId)).isZero();

        assertThat(documentTypeFieldService.getCurrentFields(typeId)).isEmpty();
        assertThat(documentTypeFieldService.getSchema(typeId).fields()).isEmpty();
        assertThat(metadataValidationService.validate(typeId, null, Map.of())).isEmpty();

        // ...and it can still be published: v2 is inserted with nothing to archive.
        UUID field = createField("text");
        documentTypeFieldService.replaceMapping(typeId, request(item(field, true, 10)));
        assertThat(schemaAt(typeId, 2).getFields()).hasSize(1);
    }

    @Test
    void publish_insertsExactlyOneRowAndArchivesThePreviousOne() {
        UUID typeId = createType();
        UUID a = createField("text");
        UUID b = createField("text");

        documentTypeFieldService.replaceMapping(typeId, request(item(a, false, 10), item(b, true, 20)));
        assertThat(schemaRowCount(typeId)).isEqualTo(2);
        DocumentTypeSchema v1 = schemaAt(typeId, 1);
        assertThat(v1.getStatus()).isEqualTo(DocumentTypeSchema.SchemaStatus.archived);
        assertThat(v1.getArchivedAt()).isNotNull();
        DocumentTypeSchema v2 = schemaAt(typeId, 2);
        assertThat(v2.getStatus()).isEqualTo(DocumentTypeSchema.SchemaStatus.active);
        assertThat(v2.getArchivedAt()).isNull();
        assertThat(v2.getFields()).extracting(DocumentTypeSchema.FieldMapping::fieldId).containsExactly(a, b);
        assertThat(documentTypeRepository.findById(typeId).orElseThrow().getCurrentSchemaVersion()).isEqualTo(2);

        List<DocumentTypeFieldResponse> v2Before = documentTypeFieldService.getFieldsAtVersion(typeId, 2);
        documentTypeFieldService.replaceMapping(typeId, request(item(b, true, 10)));
        assertThat(schemaRowCount(typeId)).isEqualTo(3);
        assertThat(schemaAt(typeId, 2).getStatus()).isEqualTo(DocumentTypeSchema.SchemaStatus.archived);
        assertThat(schemaAt(typeId, 3).getStatus()).isEqualTo(DocumentTypeSchema.SchemaStatus.active);

        // History is immutable: the archived version keeps its mapping ids and
        // placement; only status/archivedAt changed.
        List<DocumentTypeFieldResponse> v2After = documentTypeFieldService.getFieldsAtVersion(typeId, 2);
        assertThat(v2After).extracting(DocumentTypeFieldResponse::documentTypeFieldId)
                .containsExactlyElementsOf(v2Before.stream().map(DocumentTypeFieldResponse::documentTypeFieldId).toList());
        assertThat(v2After).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("archived");
            assertThat(r.archivedAt()).isNotNull();
            assertThat(r.schemaVersion()).isEqualTo(2);
        });
        assertThat(documentTypeFieldService.getCurrentFields(typeId)).singleElement()
                .satisfies(r -> assertThat(r.documentTypeFieldId())
                        .isNotIn(v2Before.stream().map(DocumentTypeFieldResponse::documentTypeFieldId).toList()));
    }

    @Test
    void publish_rejectsDuplicateFieldId() {
        UUID typeId = createType();
        UUID a = createField("text");

        assertThatThrownBy(() -> documentTypeFieldService.replaceMapping(typeId,
                request(item(a, false, 10), item(a, true, 20))))
                .isInstanceOf(DuplicateFieldMappingException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_FIELD_MAPPING");

        assertThat(schemaRowCount(typeId)).isEqualTo(1);
        assertThat(documentTypeRepository.findById(typeId).orElseThrow().getCurrentSchemaVersion()).isEqualTo(1);
    }

    @Test
    void publish_stillRejectsDuplicateDisplayOrder() {
        UUID typeId = createType();
        UUID a = createField("text");
        UUID b = createField("text");

        assertThatThrownBy(() -> documentTypeFieldService.replaceMapping(typeId,
                request(item(a, false, 10), item(b, false, 10))))
                .isInstanceOf(DuplicateDisplayOrderException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_DISPLAY_ORDER");

        assertThat(schemaRowCount(typeId)).isEqualTo(1);
    }

    @Test
    void publish_setsRequiresBackfillOnlyForNewlyMandatoryFields() {
        UUID typeId = createType();
        UUID becomesMandatory = createField("text");
        UUID alreadyMandatory = createField("text");
        UUID newMandatory = createField("text");
        UUID newOptional = createField("text");

        documentTypeFieldService.replaceMapping(typeId,
                request(item(becomesMandatory, false, 10), item(alreadyMandatory, true, 20)));
        List<DocumentTypeFieldResponse> v3 = documentTypeFieldService.replaceMapping(typeId, request(
                item(becomesMandatory, true, 10), item(alreadyMandatory, true, 20),
                item(newMandatory, true, 30), item(newOptional, false, 40)));

        Map<UUID, Boolean> backfill = new HashMap<>();
        v3.forEach(r -> backfill.put(r.fieldId(), r.requiresBackfill()));
        assertThat(backfill).containsExactlyInAnyOrderEntriesOf(Map.of(
                becomesMandatory, true,
                alreadyMandatory, false,
                newMandatory, true,
                newOptional, false));
    }

    @Test
    void deleteGuard_blocksFieldsMappedInCurrentOrArchivedVersions() {
        UUID typeId = createType();
        UUID mapped = createField("text");
        UUID kept = createField("text");
        UUID unmapped = createField("text");

        documentTypeFieldService.replaceMapping(typeId, request(item(mapped, false, 10), item(kept, false, 20)));
        assertThatThrownBy(() -> metadataFieldService.deleteField(mapped))
                .isInstanceOf(InvalidMetadataFieldStateException.class)
                .hasMessageContaining("referenced by 1 document type(s)");

        // Dropped from the current version - still referenced by archived v2.
        documentTypeFieldService.replaceMapping(typeId, request(item(kept, false, 10)));
        assertThatThrownBy(() -> metadataFieldService.deleteField(mapped))
                .isInstanceOf(InvalidMetadataFieldStateException.class);

        metadataFieldService.deleteField(unmapped);
    }

    @Test
    void documentMetadata_atomicFieldUpdatesExactNumbersAndDeleteGuard() {
        UUID typeId = createType();
        UUID amountField = createField("decimal");
        UUID tagsField = createField("multi_select");
        UUID otherTenantProbe = UUID.randomUUID();

        // Rolled back at the end: documents.latest_version_id is a deferred
        // FK to document_versions, which this test has no reason to build -
        // deferred constraints are never checked in a transaction that
        // doesn't commit.
        transactionTemplate.executeWithoutResult(tx -> {
            UUID documentId = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into documents (document_id, tenant_id, document_type_id, title, latest_version_id, owner_user_id)
                    values (?, ?, ?, 'Metadata test', ?, ?)""", documentId, tenantId, typeId, UUID.randomUUID(), userId);

            DocumentMetadataId id = new DocumentMetadataId(tenantId, documentId);
            documentMetadataRepository.saveAndFlush(DocumentMetadata.builder()
                    .id(id)
                    .documentTypeId(typeId)
                    .schemaVersion(1)
                    .fieldValues(new HashMap<>(Map.of(amountField.toString(),
                            new DocumentMetadata.FieldValue(new BigDecimal("0.1"), "manual", null))))
                    .createdBy(userId)
                    .build());
            entityManager.clear();

            DocumentMetadata.FieldValue amount = reload(id).getFieldValues().get(amountField.toString());
            assertThat(amount.v()).isInstanceOf(BigDecimal.class).isEqualTo(new BigDecimal("0.1"));

            // Set a second field atomically; the first stays untouched.
            assertThat(documentMetadataRepository.setFieldValue(tenantId, documentId, tagsField.toString(),
                    "{\"v\": [\"a\", \"b\"], \"src\": \"ocr\", \"conf\": 0.97}", userId)).isEqualTo(1);
            DocumentMetadata afterSet = reload(id);
            assertThat(afterSet.getFieldValues()).containsOnlyKeys(amountField.toString(), tagsField.toString());
            assertThat(afterSet.getFieldValues().get(tagsField.toString()).v()).isEqualTo(List.of("a", "b"));
            assertThat(afterSet.getFieldValues().get(tagsField.toString()).conf()).isEqualByComparingTo("0.97");
            assertThat(afterSet.getUpdatedBy()).isEqualTo(userId);

            // Overwrite with a number no double can hold exactly.
            documentMetadataRepository.setFieldValue(tenantId, documentId, amountField.toString(),
                    "{\"v\": 12345678901234567890.123456789, \"src\": \"import\"}", userId);
            assertThat(reload(id).getFieldValues().get(amountField.toString()).v())
                    .isEqualTo(new BigDecimal("12345678901234567890.123456789"));

            assertThat(documentMetadataRepository.existsByTenantIdAndFieldKey(tenantId, tagsField.toString())).isTrue();
            assertThat(documentMetadataRepository.existsByTenantIdAndFieldKey(otherTenantProbe, tagsField.toString())).isFalse();

            // A value on a document blocks deleting the (otherwise unmapped) field.
            assertThatThrownBy(() -> metadataFieldService.deleteField(tagsField))
                    .isInstanceOf(InvalidMetadataFieldStateException.class)
                    .hasMessageContaining("has existing document values");

            // Clearing removes just that key.
            assertThat(documentMetadataRepository.clearFieldValue(tenantId, documentId, tagsField.toString(), userId))
                    .isEqualTo(1);
            assertThat(reload(id).getFieldValues()).containsOnlyKeys(amountField.toString());
            assertThat(documentMetadataRepository.existsByTenantIdAndFieldKey(tenantId, tagsField.toString())).isFalse();

            tx.setRollbackOnly();
        });
    }

    @Test
    void validate_unknownExplicitVersionReturnsNoErrors() {
        UUID typeId = createType();
        UUID mandatory = createField("text");
        documentTypeFieldService.replaceMapping(typeId, request(item(mandatory, true, 10)));

        assertThat(metadataValidationService.validate(typeId, null, Map.of())).hasSize(1);
        assertThat(metadataValidationService.validate(typeId, 99, Map.of())).isEmpty();
        assertThat(metadataValidationService.validate(UUID.randomUUID(), 1, Map.of())).isEmpty();
    }

    private DocumentMetadata reload(DocumentMetadataId id) {
        entityManager.clear();
        return documentMetadataRepository.findById(id).orElseThrow();
    }

    private UUID createType() {
        return documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest(uniqueTypeCode(), "Schema IT type", null, 0, false, false))
                .documentTypeId();
    }

    private UUID createField(String dataType) {
        return metadataFieldService.createField(new CreateMetadataFieldRequest(
                "schema_it_" + UUID.randomUUID().toString().substring(0, 8), "Schema IT field", null,
                dataType, null, true, false)).fieldId();
    }

    private static DocumentTypeFieldMappingItem item(UUID fieldId, boolean mandatory, int displayOrder) {
        return new DocumentTypeFieldMappingItem(fieldId, mandatory, false, displayOrder, null, null);
    }

    private static ReplaceDocumentTypeFieldsRequest request(DocumentTypeFieldMappingItem... items) {
        return new ReplaceDocumentTypeFieldsRequest(Arrays.asList(items));
    }

    private DocumentTypeSchema schemaAt(UUID typeId, int version) {
        return documentTypeSchemaRepository.findByDocumentTypeIdAndTenantIdAndSchemaVersion(typeId, tenantId, version)
                .orElseThrow();
    }

    private int schemaRowCount(UUID typeId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from document_type_schemas where document_type_id = ?", Integer.class, typeId);
    }

    private static String uniqueTypeCode() {
        return "SCHEMA_IT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private UUID createTenant() {
        return tenantRepository.save(Tenant.builder()
                .tenantName("Schema IT tenant " + UUID.randomUUID())
                .domain("schema-it-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build()).getTenantId();
    }
}
