package com.oak.dms.tenant;

import com.oak.dms.security.auth.PasswordResetService;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * After an organisation is added, the platform administrator can see its administrator in the
 * organisation list, correct their details, and send the invitation again.
 */
@SpringBootTest
class TenantAdminDetailsIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;

    /** The TENANT_ADMIN role's members and profile updates live in Keycloak. */
    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoBean
    private PasswordResetService passwordResetService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpServletRequestBuilder asPlatformAdmin(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
    }

    private Tenant tenant() {
        String id = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder().tenantName("Admin details " + id)
                .domain("admin-details-" + id + ".invalid").subscriptionTier("standard")
                .status(Tenant.TenantStatus.active).build());
    }

    private User user(Tenant tenant, String name, boolean verified) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .tenantId(tenant.getTenantId()).keycloakId(UUID.randomUUID())
                .email(name + "-" + unique + "@example.invalid").username(name + "-" + unique)
                .firstName(name).lastName("Person").displayName(name + " Person")
                .status(User.UserStatus.active).verified(verified).build());
    }

    @Test
    void theTenantAdminIsListed_butOrdinaryUsersAndOtherOrganisationsAdminsAreNot() throws Exception {
        Tenant org = tenant();
        Tenant other = tenant();
        User admin = user(org, "Ada", false);
        User ordinary = user(org, "Olly", true);
        User otherAdmin = user(other, "Otto", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN"))
                .thenReturn(List.of(admin.getKeycloakId(), otherAdmin.getKeycloakId()));

        String body = mockMvc.perform(asPlatformAdmin(get("/api/v1/tenants/" + org.getTenantId() + "/admins")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(admin.getUserId().toString()))
                .andExpect(jsonPath("$[0].email").value(admin.getEmail()))
                .andExpect(jsonPath("$[0].verified").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(ordinary.getEmail()).doesNotContain(otherAdmin.getEmail());
    }

    @Test
    void correctingTheAdministratorsDetails_updatesTheirRowAndKeycloak() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", false);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        Mockito.when(keycloakUserService.emailTakenByAnotherAccount(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(false);
        String corrected = "corrected-" + UUID.randomUUID() + "@example.invalid";

        mockMvc.perform(asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId() + "/admins/" + admin.getUserId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Adelaide\",\"lastName\":\"Lovelace\",\"email\":\"" + corrected + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Adelaide Lovelace"))
                .andExpect(jsonPath("$.email").value(corrected));

        User saved = userRepository.findById(admin.getUserId()).orElseThrow();
        assertThat(saved.getEmail()).isEqualTo(corrected);
        assertThat(saved.getFirstName()).isEqualTo("Adelaide");
        Mockito.verify(keycloakUserService).updateProfile(admin.getKeycloakId(), "Adelaide", "Lovelace", corrected);
    }

    @Test
    void anEmailAnotherAccountHas_isRefused() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", false);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        Mockito.when(keycloakUserService.emailTakenByAnotherAccount(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(true);

        mockMvc.perform(asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId() + "/admins/" + admin.getUserId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Ada\",\"lastName\":\"Person\",\"email\":\"taken@example.invalid\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
    }

    @Test
    void aUserWhoIsNotTheTenantAdmin_cannotBeEditedThroughThisRoute() throws Exception {
        Tenant org = tenant();
        User ordinary = user(org, "Olly", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of());

        mockMvc.perform(asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId() + "/admins/" + ordinary.getUserId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Olly\",\"lastName\":\"Person\",\"email\":\"x@example.invalid\"}")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theInvitationCanBeSentAgain_onlyToAnAdministratorWhoHasNotSetUpYet() throws Exception {
        Tenant org = tenant();
        User pending = user(org, "Ada", false);
        User active = user(org, "Bea", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN"))
                .thenReturn(List.of(pending.getKeycloakId(), active.getKeycloakId()));

        mockMvc.perform(asPlatformAdmin(post("/api/v1/tenants/" + org.getTenantId() + "/admins/" + pending.getUserId()
                        + "/resend-invite")))
                .andExpect(status().isNoContent());
        Mockito.verify(passwordResetService).issueForNewUser(ArgumentMatchers.argThat(u -> u.getUserId().equals(pending.getUserId())));

        mockMvc.perform(asPlatformAdmin(post("/api/v1/tenants/" + org.getTenantId() + "/admins/" + active.getUserId()
                        + "/resend-invite")))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyPlatformAdminsMaySeeOrEditAnOrganisationsAdministrators() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(get("/api/v1/tenants/" + org.getTenantId() + "/admins")
                        .with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }
}
