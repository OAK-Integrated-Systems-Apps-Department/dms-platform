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
    void createTenant_installsTheRelationshipTypes() throws Exception {
        TenantResponse tenant = tenantService.createTenant(createRequest());

        try (Connection c = dataSource.getConnection()) {
            assertThat(count(c, "select count(*) from relationship_types where tenant_id = ?::uuid and is_system"
                    + " and is_enabled", tenant.tenantId().toString())).isEqualTo(10);
        }
    }

    @Test
    void provisionTenant_installsTheRelationshipTypes() throws Exception {
        when(keycloakUserService.createUser(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        TenantProvisioningResponse response = tenantProvisioningService.provisionTenant(provisionRequest());

        try (Connection c = dataSource.getConnection()) {
            assertThat(count(c, "select count(*) from relationship_types where tenant_id = ?::uuid and is_system",
                    response.tenant().tenantId().toString())).isEqualTo(10);
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

    @Test
    void provisionTenant_whenTheInvitationEmailCannotBeSent_saysSoClearly_andUndoesEverything() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        when(keycloakUserService.createUser(any(), any(), any(), any(), any())).thenReturn(keycloakId);
        // What a bad SMTP password looks like (Gmail: 535 Username and Password not accepted).
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailAuthenticationException("Authentication failed"))
                .when(passwordResetService).issueForNewUser(any());
        ProvisionTenantRequest request = provisionRequest();

        // Not the generic "An unexpected error occurred": the same MAIL_DELIVERY_FAILED answer an ordinary invite gets.
        assertThatThrownBy(() -> tenantProvisioningService.provisionTenant(request))
                .isInstanceOf(com.oak.dms.common.exception.MailDeliveryException.class)
                .hasMessageContaining("mail settings");

        // Nothing is left behind: no organisation, and the Keycloak account made for its first admin is removed.
        assertThat(tenantRepository.existsByDomainAndDeletedFalse(request.domain())).isFalse();
        verify(keycloakUserService).deleteUser(keycloakId);
    }

    @Test
    void provisionTenant_canCreateSeveralAdministratorsAtOnce() throws Exception {
        when(keycloakUserService.createUser(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> UUID.randomUUID());
        ProvisionTenantRequest request = provisionRequestWithExtraAdmins(2);

        TenantProvisioningResponse response = tenantProvisioningService.provisionTenant(request);

        assertThat(response.admin().email()).isEqualTo(request.adminEmail());
        assertThat(response.additionalAdmins()).extracting(admin -> admin.email())
                .containsExactlyElementsOf(request.additionalAdmins().stream()
                        .map(ProvisionTenantRequest.AdditionalAdmin::email).toList());
        // Three accounts, each made a TENANT_ADMIN and invited.
        verify(keycloakUserService, org.mockito.Mockito.times(3)).createUser(any(), any(), any(), any(), any());
        verify(keycloakUserService, org.mockito.Mockito.times(3)).assignPlatformRealmRole(any(), org.mockito.ArgumentMatchers.eq("TENANT_ADMIN"));
        verify(passwordResetService, org.mockito.Mockito.times(3)).issueForNewUser(any());
    }

    @Test
    void provisionTenant_refusesTwoAdministratorsWithTheSameUsername_beforeCreatingAnyAccount() throws Exception {
        ProvisionTenantRequest base = provisionRequestWithExtraAdmins(1);
        ProvisionTenantRequest clash = new ProvisionTenantRequest(base.tenantName(), base.domain(), base.subscriptionTier(),
                base.adminEmail(), base.adminUsername(), base.adminFirstName(), base.adminLastName(),
                java.util.List.of(new ProvisionTenantRequest.AdditionalAdmin("other-" + base.adminEmail(),
                        base.adminUsername().toUpperCase(), "Other", "Admin")));

        assertThatThrownBy(() -> tenantProvisioningService.provisionTenant(clash))
                .isInstanceOf(com.oak.dms.common.exception.DuplicateResourceException.class);

        assertThat(tenantRepository.existsByDomainAndDeletedFalse(clash.domain())).isFalse();
        verify(keycloakUserService, never()).createUser(any(), any(), any(), any(), any());
    }

    @Test
    void provisionTenant_whenALaterAdministratorFails_removesEveryAccountAlreadyCreated() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(keycloakUserService.createUser(any(), any(), any(), any(), any()))
                .thenReturn(first).thenReturn(second)
                .thenThrow(new com.oak.dms.common.exception.KeycloakUserAlreadyExistsException("taken@example.invalid", "taken"));
        ProvisionTenantRequest request = provisionRequestWithExtraAdmins(2);

        assertThatThrownBy(() -> tenantProvisioningService.provisionTenant(request))
                .isInstanceOf(com.oak.dms.common.exception.KeycloakUserAlreadyExistsException.class);

        assertThat(tenantRepository.existsByDomainAndDeletedFalse(request.domain())).isFalse();
        verify(keycloakUserService).deleteUser(first);
        verify(keycloakUserService).deleteUser(second);
    }

    private static ProvisionTenantRequest provisionRequestWithExtraAdmins(int extra) {
        ProvisionTenantRequest base = provisionRequest();
        java.util.List<ProvisionTenantRequest.AdditionalAdmin> admins = new java.util.ArrayList<>();
        for (int i = 1; i <= extra; i++) {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            admins.add(new ProvisionTenantRequest.AdditionalAdmin("extra" + i + "-" + tag + "@catalogue-provisioning.invalid",
                    "extra" + i + "-" + tag, "Extra" + i, "Admin"));
        }
        return new ProvisionTenantRequest(base.tenantName(), base.domain(), base.subscriptionTier(), base.adminEmail(),
                base.adminUsername(), base.adminFirstName(), base.adminLastName(), admins);
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
                "admin-" + suffix + "@catalogue-provisioning.invalid", "admin-" + suffix, "Test", "Admin", null);
    }
}
