package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldOptionRequest;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.DocumentTypeFieldMappingItem;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Redis outage must never fail a request, for both the read path
 * ({@code SchemaCacheService#get}) and the write/evict path
 * ({@code SchemaCacheService#evictTenant}). This class owns dedicated
 * Postgres/Redis containers (rather than extending
 * {@link AbstractMetadataIntegrationTest}) specifically so it can stop
 * Redis mid-test without affecting the containers other integration tests
 * in this module share.
 */
@SpringBootTest
@Testcontainers
class SchemaCacheResilienceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("dms_test_resilience")
            .withUsername("dms_test")
            .withPassword("dms_test");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        // Short timeouts so a stopped Redis container fails fast instead of
        // hanging on Lettuce's default 60s command timeout for every call
        // this test makes after REDIS.stop().
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
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

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUpTenantContext() {
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into tenants (tenant_id, tenant_name, domain, subscription_tier) values (?, ?, ?, ?)",
                tenantId, "Test Tenant " + tenantId, "test-" + tenantId + ".example.com", "basic");
        jdbcTemplate.update(
                "insert into users (user_id, tenant_id, keycloak_id, email, display_name, first_name, last_name, username) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                userId, tenantId, UUID.randomUUID(), "test-" + userId + "@example.com", "Test User", "Test", "User",
                "test-" + userId);
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void redisOutageDoesNotFailReadsOrEvictions() {
        DocumentTypeResponse documentType = documentTypeService.createDocumentType(
                new CreateDocumentTypeRequest("RESILIENT_DOC", "RESILIENT_DOC", null, 0, false, false));
        MetadataFieldResponse field = metadataFieldService.createField(
                new CreateMetadataFieldRequest("resilient_field", "resilient_field", null, "dropdown", null, false, true, false));
        documentTypeFieldService.replaceMapping(documentType.documentTypeId(), new ReplaceDocumentTypeFieldsRequest(List.of(
                new DocumentTypeFieldMappingItem(field.fieldId(), true, false, 10, null, null))));

        // Populate the cache once while Redis is still up, then take it down.
        metadataValidationService.validate(documentType.documentTypeId(), null, Map.of());
        REDIS.stop();

        // Read path: a cache lookup against a dead connection must fall
        // through to the database rather than throwing.
        assertThatCode(() -> {
            List<ValidationError> errors = metadataValidationService.validate(documentType.documentTypeId(), null, Map.of());
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).errorCode()).isEqualTo("NO_ACTIVE_OPTIONS");
        }).doesNotThrowAnyException();

        // Write/evict path: creating an option triggers evictTenant(), which
        // must swallow the connection failure rather than rolling back the
        // option that was just persisted in Postgres.
        assertThatCode(() -> {
            MetadataFieldOptionResponse option = metadataFieldOptionService.createOption(field.fieldId(),
                    new CreateMetadataFieldOptionRequest("v1", "Value 1", null, 10));
            assertThat(option.optionId()).isNotNull();
        }).doesNotThrowAnyException();

        List<MetadataFieldOptionResponse> options = metadataFieldOptionService.listOptions(field.fieldId());
        assertThat(options).hasSize(1);
    }
}
