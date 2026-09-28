package com.oak.dms.tenant;

import com.oak.dms.metadata.seed.TenantStarterCatalogService;
import com.oak.dms.security.auth.PasswordResetService;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.tenant.dto.CreateTenantRequest;
import com.oak.dms.tenant.dto.ProvisionTenantRequest;
import com.oak.dms.tenant.dto.TenantProvisioningResponse;
import com.oak.dms.tenant.dto.TenantResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static com.oak.dms.metadata.support.StarterCatalogAssertions.assertExactStarterCatalog;
import static com.oak.dms.metadata.support.StarterCatalogAssertions.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves both tenant-creation paths install the starter metadata catalogue
 * in the tenant's own transaction: a new tenant starts with it, and a
 * catalogue failure rolls the tenant (and its audit event) back. Keycloak is
 * mocked; nothing here depends on a running Keycloak.
 */
@SpringBootTest
class TenantCatalogueProvisioningIT {

    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantProvisioningService tenantProvisioningService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private DataSource dataSource;
    @MockitoSpyBean
    private TenantStarterCatalogService starterCatalogService;
    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoBean
    private PasswordResetService passwordResetService;

    @Test
    void createTenant_installsTheStarterCatalogue() throws Exception {
        TenantResponse tenant = tenantService.createTenant(createRequest());

        try (Connection c = dataSource.getConnection()) {
            assertExactStarterCatalog(c, tenant.tenantId(), null);
        }
    }

    @Test
    void createTenant_rollsBackTheTenantWhenTheCatalogueFails() throws Exception {
        failAfterInstalling();
        CreateTenantRequest request = createRequest();
        long fieldsBefore = totalMetadataFields();

        assertThatThrownBy(() -> tenantService.createTenant(request)).hasMessage("catalogue failed");

        assertThat(tenantRepository.existsByDomainAndDeletedFalse(request.domain())).isFalse();
        assertThat(totalMetadataFields()).isEqualTo(fieldsBefore);
        try (Connection c = dataSource.getConnection()) {
            assertThat(count(c, "select count(*) from audit_events where action = 'TENANT_CREATED' and resource_name = ?",
                    request.tenantName())).isZero();
        }
    }

    @Test
    void provisionTenant_installsTheStarterCatalogue() throws Exception {
        when(keycloakUserService.createUser(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        TenantProvisioningResponse response = tenantProvisioningService.provisionTenant(provisionRequest());

        try (Connection c = dataSource.getConnection()) {
            assertExactStarterCatalog(c, response.tenant().tenantId(), null);
        }
    }

    @Test
    void provisionTenant_rollsBackBeforeTouchingKeycloakWhenTheCatalogueFails() throws Exception {
        failAfterInstalling();
        ProvisionTenantRequest request = provisionRequest();
        long fieldsBefore = totalMetadataFields();

        assertThatThrownBy(() -> tenantProvisioningService.provisionTenant(request)).hasMessage("catalogue failed");

        assertThat(tenantRepository.existsByDomainAndDeletedFalse(request.domain())).isFalse();
        assertThat(totalMetadataFields()).isEqualTo(fieldsBefore);
        verify(keycloakUserService, never()).createUser(any(), any(), any(), any(), any());
    }

    /** The real install runs and writes its rows, then fails - so the rollback has something to undo. */
    private void failAfterInstalling() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("catalogue failed");
        }).when(starterCatalogService).install(any());
    }

    private long totalMetadataFields() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            return count(c, "select count(*) from metadata_fields");
        }
    }

    private static CreateTenantRequest createRequest() {
        String suffix = UUID.randomUUID().toString();
        return new CreateTenantRequest("Catalogue test tenant " + suffix, "catalogue-test-" + suffix + ".invalid",
                "standard");
    }

    private static ProvisionTenantRequest provisionRequest() {
        String suffix = UUID.randomUUID().toString();
        return new ProvisionTenantRequest("Catalogue provisioning tenant " + suffix,
                "catalogue-provisioning-" + suffix + ".invalid", "standard",
                "admin-" + suffix + "@catalogue-provisioning.invalid", "admin-" + suffix, "Test", "Admin");
    }
}
