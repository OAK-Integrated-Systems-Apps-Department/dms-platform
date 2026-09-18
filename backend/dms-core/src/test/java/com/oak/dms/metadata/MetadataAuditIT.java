package com.oak.dms.metadata;

import com.oak.dms.audit.AuditActionCatalog;
import com.oak.dms.audit.AuditEvent;
import com.oak.dms.audit.AuditEventRepository;
import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldOptionRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldValidationRequest;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.metadata.dto.UpdateDocumentTypeRequest;
import com.oak.dms.metadata.dto.UpdateMetadataFieldOptionRequest;
import com.oak.dms.metadata.dto.UpdateMetadataFieldRequest;
import com.oak.dms.metadata.dto.UpdateMetadataFieldValidationRequest;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves every mutating MDTM (Metadata &amp; Document-Type Management)
 * operation writes its own audit event - document types, the metadata field
 * catalog, field options, field validation rules, and publishing a new
 * field-mapping schema version. Same direct-service-call pattern as
 * {@link com.oak.dms.security.rbac.PermissionCodeAuditIT}: none of these
 * services call Keycloak for the current caller, so a real Keycloak account
 * isn't needed, just a real, locally-persisted {@link User} row (permissions/
 * roles-style FK requirements don't apply here, but TenantContext still
 * needs to resolve to something real for created_by/updated_by columns).
 */
@SpringBootTest
class MetadataAuditIT {

    @Autowired
    private DocumentTypeService documentTypeService;
    @Autowired
    private MetadataFieldService metadataFieldService;
    @Autowired
    private MetadataFieldOptionService metadataFieldOptionService;
    @Autowired
    private MetadataFieldValidationService metadataFieldValidationService;
    @Autowired
    private DocumentTypeFieldService documentTypeFieldService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void setUp() {
        UUID tenantId = createTenant();
        UUID keycloakId = UUID.randomUUID();
        User admin = userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@metadata-audit-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Admin")
                .displayName("Test Admin")
                .status(User.UserStatus.active)
                .build());
        TenantContext.set(tenantId, admin.getUserId(), admin.getDisplayName(), keycloakId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void documentTypeLifecycle_recordsCreateUpdateActivateDeactivate() {
        String typeCode = "TEST_TYPE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        DocumentTypeResponse created = documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest(typeCode, "Test Type", null, 0, false, false));
        AuditEvent createdEvent = latest(AuditActionCatalog.DOCUMENT_TYPE_CREATED);
        assertThat(createdEvent.getObjectId()).isEqualTo(created.documentTypeId());
        assertThat(createdEvent.getResourceName()).isEqualTo(typeCode);
        assertThat(createdEvent.getObjectVersion()).isEqualTo(1);

        documentTypeService.updateDocumentType(created.documentTypeId(),
                new UpdateDocumentTypeRequest("Renamed Type", "now with a description", 0, true, true));
        AuditEvent updatedEvent = latest(AuditActionCatalog.DOCUMENT_TYPE_UPDATED);
        assertThat(updatedEvent.getObjectId()).isEqualTo(created.documentTypeId());
        assertThat(updatedEvent.getObjectVersion()).isEqualTo(1);

        documentTypeService.deactivateDocumentType(created.documentTypeId());
        assertThat(latest(AuditActionCatalog.DOCUMENT_TYPE_DEACTIVATED).getObjectId())
                .isEqualTo(created.documentTypeId());

        documentTypeService.activateDocumentType(created.documentTypeId());
        assertThat(latest(AuditActionCatalog.DOCUMENT_TYPE_ACTIVATED).getObjectId())
                .isEqualTo(created.documentTypeId());
    }

    @Test
    void metadataFieldLifecycle_recordsCreateUpdateActivateDeactivateDeprecateDelete() {
        String fieldKey = "test_field_" + UUID.randomUUID().toString().substring(0, 8);
        MetadataFieldResponse created = metadataFieldService.createField(
                new CreateMetadataFieldRequest(fieldKey, "Test Field", null, "text", null, true, false));
        AuditEvent createdEvent = latest(AuditActionCatalog.METADATA_FIELD_CREATED);
        assertThat(createdEvent.getObjectId()).isEqualTo(created.fieldId());
        assertThat(createdEvent.getResourceName()).isEqualTo(fieldKey);

        metadataFieldService.updateField(created.fieldId(),
                new UpdateMetadataFieldRequest("Renamed Field", "help text", "default", false, true));
        assertThat(latest(AuditActionCatalog.METADATA_FIELD_UPDATED).getObjectId()).isEqualTo(created.fieldId());

        metadataFieldService.deactivateField(created.fieldId());
        assertThat(latest(AuditActionCatalog.METADATA_FIELD_DEACTIVATED).getObjectId()).isEqualTo(created.fieldId());

        metadataFieldService.activateField(created.fieldId());
        assertThat(latest(AuditActionCatalog.METADATA_FIELD_ACTIVATED).getObjectId()).isEqualTo(created.fieldId());

        metadataFieldService.deprecateField(created.fieldId());
        assertThat(latest(AuditActionCatalog.METADATA_FIELD_DEPRECATED).getObjectId()).isEqualTo(created.fieldId());

        // Deleting a deprecated-but-unreferenced field is still allowed -
        // deprecate only blocks new mappings, not deletion (see
        // MetadataFieldServiceImpl's own doc comment).
        metadataFieldService.deleteField(created.fieldId());
        AuditEvent deletedEvent = latest(AuditActionCatalog.METADATA_FIELD_DELETED);
        assertThat(deletedEvent.getObjectId()).isEqualTo(created.fieldId());
        assertThat(deletedEvent.getResourceName()).isEqualTo(fieldKey);
    }

    @Test
    void metadataFieldOptionLifecycle_recordsCreateUpdateDelete() {
        MetadataFieldResponse field = metadataFieldService.createField(new CreateMetadataFieldRequest(
                "test_dropdown_" + UUID.randomUUID().toString().substring(0, 8), "Test Dropdown", null,
                "dropdown", null, true, false));

        MetadataFieldOptionResponse option = metadataFieldOptionService.createOption(field.fieldId(),
                new CreateMetadataFieldOptionRequest("value_a", "Value A", null, 10));
        AuditEvent createdEvent = latest(AuditActionCatalog.METADATA_FIELD_OPTION_CREATED);
        assertThat(createdEvent.getObjectId()).isEqualTo(option.optionId());
        assertThat(createdEvent.getResourceName()).isEqualTo("value_a");

        metadataFieldOptionService.updateOption(field.fieldId(), option.optionId(),
                new UpdateMetadataFieldOptionRequest("value_a_renamed", "Value A Renamed", null, 10,
                        UpdateMetadataFieldOptionRequest.Status.active));
        AuditEvent updatedEvent = latest(AuditActionCatalog.METADATA_FIELD_OPTION_UPDATED);
        assertThat(updatedEvent.getObjectId()).isEqualTo(option.optionId());
        assertThat(updatedEvent.getResourceName()).isEqualTo("value_a_renamed");

        metadataFieldOptionService.deleteOption(field.fieldId(), option.optionId());
        assertThat(latest(AuditActionCatalog.METADATA_FIELD_OPTION_DELETED).getObjectId())
                .isEqualTo(option.optionId());
    }

    @Test
    void metadataFieldValidationLifecycle_recordsCreateUpdateDelete() {
        MetadataFieldResponse field = metadataFieldService.createField(new CreateMetadataFieldRequest(
                "test_validated_" + UUID.randomUUID().toString().substring(0, 8), "Test Validated Field", null,
                "text", null, true, false));

        MetadataFieldValidationResponse validation = metadataFieldValidationService.createValidation(field.fieldId(),
                new CreateMetadataFieldValidationRequest(MetadataFieldValidation.RuleType.max_length, "255",
                        "Too long", MetadataFieldValidation.Severity.error));
        AuditEvent createdEvent = latest(AuditActionCatalog.METADATA_FIELD_VALIDATION_CREATED);
        assertThat(createdEvent.getObjectId()).isEqualTo(validation.validationId());
        assertThat(createdEvent.getResourceName()).isEqualTo("max_length");

        metadataFieldValidationService.updateValidation(field.fieldId(), validation.validationId(),
                new UpdateMetadataFieldValidationRequest("500", "Still too long", MetadataFieldValidation.Severity.warning));
        assertThat(latest(AuditActionCatalog.METADATA_FIELD_VALIDATION_UPDATED).getObjectId())
                .isEqualTo(validation.validationId());

        metadataFieldValidationService.deleteValidation(field.fieldId(), validation.validationId());
        assertThat(latest(AuditActionCatalog.METADATA_FIELD_VALIDATION_DELETED).getObjectId())
                .isEqualTo(validation.validationId());
    }

    @Test
    void publishingFieldMapping_recordsSchemaPublished() {
        String typeCode = "TEST_TYPE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        DocumentTypeResponse documentType = documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest(typeCode, "Test Type", null, 0, false, false));
        MetadataFieldResponse field = metadataFieldService.createField(new CreateMetadataFieldRequest(
                "test_mapped_" + UUID.randomUUID().toString().substring(0, 8), "Test Mapped Field", null,
                "text", null, true, false));

        documentTypeFieldService.replaceMapping(documentType.documentTypeId(),
                new ReplaceDocumentTypeFieldsRequest(List.of(
                        new DocumentTypeFieldMappingItem(field.fieldId(), true, false, 10, null, null))));

        AuditEvent published = latest(AuditActionCatalog.DOCUMENT_TYPE_SCHEMA_PUBLISHED);
        assertThat(published.getObjectId()).isEqualTo(documentType.documentTypeId());
        assertThat(published.getResourceName()).isEqualTo(typeCode);
        assertThat(published.getObjectVersion()).isEqualTo(2);
    }

    private UUID createTenant() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Metadata audit test tenant " + UUID.randomUUID())
                .domain("metadata-audit-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        return tenant.getTenantId();
    }

    private AuditEvent latest(String action) {
        return auditEventRepository.findAll(byAction(action)).stream()
                .max((x, y) -> x.getOccurredAt().compareTo(y.getOccurredAt()))
                .orElseThrow();
    }

    private static Specification<AuditEvent> byAction(String action) {
        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }
}
