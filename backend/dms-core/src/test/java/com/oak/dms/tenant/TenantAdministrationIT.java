package com.oak.dms.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Organisations screen's API: deactivated organisations listed, reactivated - but never after their keys are gone. */
@SpringBootTest
class TenantAdministrationIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;

    /** Platform actions ask for the caller's own password; here Keycloak always accepts it. */
    @MockitoBean
    private com.oak.dms.security.auth.KeycloakAuthClient keycloakAuthClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        org.mockito.Mockito.when(keycloakAuthClient.passwordGrant(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.oak.dms.security.auth.KeycloakAuthClient.GrantResult.Success(
                        new com.oak.dms.security.auth.dto.KeycloakTokenResponse("a", 300, "r", 300, "Bearer")));
    }

    private MockHttpServletRequestBuilder asPlatformAdmin(MockHttpServletRequestBuilder request) {
        return request.header("X-Confirm-Password", "correct horse")
                .with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("preferred_username", "platform.admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
    }

    private Tenant tenant() {
        String id = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder().tenantName("Admin IT " + id).domain("admin-it-" + id + ".invalid")
                .subscriptionTier("standard").status(Tenant.TenantStatus.active).build());
    }

    @Test
    void aDeactivatedOrganisation_isListed_andCanBeReactivated() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId())))
                .andExpect(status().isNoContent());

        String listed = mockMvc.perform(asPlatformAdmin(get("/api/v1/tenants").param("includeDeactivated", "true")
                        .param("size", "1000")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listed).contains(org.getTenantId().toString()).contains("\"deactivated\":true");
        String activeOnly = mockMvc.perform(asPlatformAdmin(get("/api/v1/tenants").param("size", "1000")))
                .andReturn().getResponse().getContentAsString();
        assertThat(activeOnly).doesNotContain(org.getTenantId().toString());

        mockMvc.perform(asPlatformAdmin(post("/api/v1/tenants/" + org.getTenantId() + "/reactivate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivated").value(false))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void anOrganisationWhoseKeysWereDestroyed_staysGone() throws Exception {
        Tenant org = tenant();
        org.setDeleted(true);
        org.setDeletedAt(OffsetDateTime.now());
        org.setKeysShreddedAt(OffsetDateTime.now());
        tenantRepository.save(org);

        mockMvc.perform(asPlatformAdmin(post("/api/v1/tenants/" + org.getTenantId() + "/reactivate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_KEYS_SHREDDED"));
    }

    @Test
    void onlyPlatformAdmins() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(post("/api/v1/tenants/" + org.getTenantId() + "/reactivate")
                        .with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    // ---- PUT /api/v1/tenants/{id}: who may update an organisation, and what a tenant admin may change (SEC-01) ----

    /** A signed-in user of {@code org} holding the given realm role (none = an ordinary member). */
    private MockHttpServletRequestBuilder signedInTo(Tenant org, String realmRole,
                                                     MockHttpServletRequestBuilder request) {
        UUID keycloakId = UUID.randomUUID();
        userRepository.save(User.builder().tenantId(org.getTenantId()).keycloakId(keycloakId)
                .email(keycloakId + "@sec01.invalid").username(keycloakId.toString())
                .firstName("Sec").lastName("01").displayName("Sec 01").status(User.UserStatus.active).build());
        var jwt = jwt().jwt(b -> b.subject(keycloakId.toString()).claim("tenant_id", org.getTenantId().toString()));
        return request.with(realmRole == null ? jwt.authorities()
                : jwt.authorities(new SimpleGrantedAuthority("ROLE_" + realmRole)));
    }

    private static String body(String name, String tier, String status) {
        return "{\"tenantName\":\"" + name + "\",\"subscriptionTier\":\"" + tier + "\",\"status\":\"" + status + "\"}";
    }

    private MockHttpServletRequestBuilder putTenant(Tenant org, String json) {
        return put("/api/v1/tenants/" + org.getTenantId()).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private void assertUnchanged(Tenant before) {
        Tenant after = tenantRepository.findById(before.getTenantId()).orElseThrow();
        assertThat(after.getTenantName()).isEqualTo(before.getTenantName());
        assertThat(after.getSubscriptionTier()).isEqualTo(before.getSubscriptionTier());
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
    }

    @Test
    void anOrdinaryMemberOfTheOrganisation_cannotUpdateIt() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(signedInTo(org, null, putTenant(org, body("Hijacked", "enterprise", "suspended"))))
                .andExpect(status().isForbidden());
        assertUnchanged(org);
    }

    @Test
    void theAdministratorOfAnotherOrganisation_cannotUpdateIt() throws Exception {
        Tenant org = tenant();
        Tenant other = tenant();
        mockMvc.perform(signedInTo(other, "TENANT_ADMIN", putTenant(org, body("Hijacked", "standard", "active"))))
                .andExpect(status().isForbidden());
        assertUnchanged(org);
    }

    @Test
    void theOrganisationsOwnAdministrator_canRenameIt() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(signedInTo(org, "TENANT_ADMIN",
                        putTenant(org, body("Renamed by its admin", org.getSubscriptionTier(), "active"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantName").value("Renamed by its admin"));
        Tenant after = tenantRepository.findById(org.getTenantId()).orElseThrow();
        assertThat(after.getTenantName()).isEqualTo("Renamed by its admin");
        assertThat(after.getSubscriptionTier()).isEqualTo(org.getSubscriptionTier());
        assertThat(after.getStatus()).isEqualTo(Tenant.TenantStatus.active);
    }

    @Test
    void theOrganisationsOwnAdministrator_cannotChangeItsStatus() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(signedInTo(org, "TENANT_ADMIN",
                        putTenant(org, body("Renamed anyway", org.getSubscriptionTier(), "suspended"))))
                .andExpect(status().isForbidden());
        assertUnchanged(org); // nothing partially applied - not even the new name
    }

    @Test
    void theOrganisationsOwnAdministrator_cannotChangeItsSubscriptionTier() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(signedInTo(org, "TENANT_ADMIN", putTenant(org, body("Renamed anyway", "enterprise", "active"))))
                .andExpect(status().isForbidden());
        assertUnchanged(org);
    }

    @Test
    void aPlatformAdministrator_canStillChangeEveryField_withTheirPassword() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(asPlatformAdmin(putTenant(org, body("Renamed by platform", "enterprise", "suspended"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantName").value("Renamed by platform"))
                .andExpect(jsonPath("$.subscriptionTier").value("enterprise"))
                .andExpect(jsonPath("$.status").value("suspended"));
    }

    @Test
    void aPlatformAdministrator_isStillAskedForTheirPassword() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(put("/api/v1/tenants/" + org.getTenantId()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("No password", "standard", "active"))
                        .with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("preferred_username", "platform.admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().is4xxClientError());
        assertUnchanged(org);
    }

    @Test
    void anOrdinaryMember_canStillReadTheirOwnOrganisation() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(signedInTo(org, null, get("/api/v1/tenants/" + org.getTenantId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantName").value(org.getTenantName()));
    }
}
