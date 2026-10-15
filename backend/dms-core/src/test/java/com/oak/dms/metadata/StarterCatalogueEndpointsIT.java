package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.repository.MetadataFieldRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.DocumentTypeService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.tenant.TenantService;
import com.oak.dms.tenant.dto.CreateTenantRequest;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a tenant created through {@link TenantService} serves its own
 * starter catalogue through the services behind
 * {@code GET /document-types/{id}/fields}, {@code GET .../schema} and
 * {@code POST .../validate} - and never another tenant's copy.
 */
@SpringBootTest
class StarterCatalogueEndpointsIT {

    @Autowired
    private TenantService tenantService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MetadataFieldRepository metadataFieldRepository;
    @Autowired
    private DocumentTypeService documentTypeService;
    @Autowired
    private DocumentTypeFieldService documentTypeFieldService;
    @Autowired
    private MetadataValidationService metadataValidationService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void newTenant_servesItsOwnStarterCatalogue() {
        UUID otherTenantId = createTenant();
        UUID tenantId = createTenant();
        bindUserIn(tenantId);

        Set<UUID> ownFieldIds = fieldIds(tenantId);
        assertThat(ownFieldIds).hasSize(24).doesNotContainAnyElementsOf(fieldIds(otherTenantId));

        UUID contractId = documentTypeService.listDocumentTypes(Pageable.unpaged()).stream()
                .filter(t -> t.typeCode().equals("CONTRACT"))
                .map(DocumentTypeResponse::documentTypeId)
                .findFirst().orElseThrow();

        assertThat(documentTypeFieldService.getCurrentFields(contractId))
                .hasSize(12)
                .allSatisfy(f -> assertThat(ownFieldIds).contains(f.fieldId()))
                .extracting(DocumentTypeFieldResponse::fieldKey)
                .startsWith("contract_party", "contract_value", "contract_start_date", "contract_end_date");

        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(contractId);
        assertThat(schema.schemaVersion()).isEqualTo(1);
        assertThat(schema.fields()).hasSize(12)
                .allSatisfy(f -> assertThat(ownFieldIds).contains(f.fieldId()));

        assertThat(metadataValidationService.validate(contractId, null, Map.of()))
                .filteredOn(e -> e.errorCode().equals("MANDATORY_FIELD_MISSING"))
                .extracting(ValidationError::fieldKey)
                .containsExactlyInAnyOrder("contract_party", "contract_value", "contract_start_date",
                        "contract_end_date", "retention_class");
    }

    private Set<UUID> fieldIds(UUID tenantId) {
        return metadataFieldRepository.findAllByTenantIdAndDeletedFalse(tenantId).stream()
                .map(MetadataField::getFieldId)
                .collect(Collectors.toSet());
    }

    private UUID createTenant() {
        String suffix = UUID.randomUUID().toString();
        return tenantService.createTenant(new CreateTenantRequest("Catalogue endpoints tenant " + suffix,
                "catalogue-endpoints-" + suffix + ".invalid", "standard")).tenantId();
    }

    private void bindUserIn(UUID tenantId) {
        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@catalogue-endpoints-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("User")
                .displayName("Test User")
                .status(User.UserStatus.active)
                .build());
        TenantContext.set(tenantId, user.getUserId(), user.getDisplayName(), keycloakId);
    }
}
