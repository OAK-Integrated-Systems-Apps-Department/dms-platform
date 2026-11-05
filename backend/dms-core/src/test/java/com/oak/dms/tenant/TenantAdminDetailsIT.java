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
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** The TENANT_ADMIN role's members and profile updates live in Keycloak. */
    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoBean
    private PasswordResetService passwordResetService;

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
        Mockito.verify(keycloakUserService).updateProfile(admin.getKeycloakId(), "Adelaide", "Lovelace", corrected, null);
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

    // ------------------------------------------------------------------ the username can be changed too

    private String body(String first, String email, String username) {
        return "{\"firstName\":\"" + first + "\",\"lastName\":\"Person\",\"email\":\"" + email + "\""
                + (username == null ? "" : ",\"username\":\"" + username + "\"") + "}";
    }

    private MockHttpServletRequestBuilder edit(Tenant org, User admin, String json) {
        return asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId() + "/admins/" + admin.getUserId())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void theUsernameCanBeChanged_inTheirRowAndInKeycloak_keptInLowerCaseAsKeycloakKeepsIt() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        Mockito.when(keycloakUserService.usernameTakenByAnotherAccount(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(false);
        String wanted = "Ada.Lovelace-" + UUID.randomUUID().toString().substring(0, 6);

        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), wanted)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(wanted.toLowerCase()));

        assertThat(userRepository.findById(admin.getUserId()).orElseThrow().getUsername()).isEqualTo(wanted.toLowerCase());
        Mockito.verify(keycloakUserService).updateProfile(admin.getKeycloakId(), "Ada", "Person", null, wanted.toLowerCase());
    }

    @Test
    void aUsernameAnotherAdministratorOfTheOrganisationHas_isRefused_inAnyLetterCase() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", true);
        User other = user(org, "Bea", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN"))
                .thenReturn(List.of(admin.getKeycloakId(), other.getKeycloakId()));
        Mockito.when(keycloakUserService.usernameTakenByAnotherAccount(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(false);

        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), other.getUsername().toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_IN_USE"));

        assertThat(userRepository.findById(admin.getUserId()).orElseThrow().getUsername()).isEqualTo(admin.getUsername());
        Mockito.verify(keycloakUserService, Mockito.never()).updateProfile(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void aUsernameAnotherAccountAnywhereHas_orAnotherAccountsEmail_isRefused() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        // Keycloak's one realm covers every organisation: this says another account (elsewhere) has it.
        Mockito.when(keycloakUserService.usernameTakenByAnotherAccount(ArgumentMatchers.eq("taken.elsewhere"), ArgumentMatchers.any()))
                .thenReturn(true);

        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), "taken.elsewhere")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_IN_USE"));

        assertThat(userRepository.findById(admin.getUserId()).orElseThrow().getUsername()).isEqualTo(admin.getUsername());
    }

    @Test
    void ifKeycloakRefusesTheNameAfterAll_theRowIsNotChangedEither() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        Mockito.when(keycloakUserService.usernameTakenByAnotherAccount(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(false);
        // Someone took it between the check and the change.
        Mockito.doThrow(new com.oak.dms.common.exception.KeycloakUserAlreadyExistsException(null, "raced.name"))
                .when(keycloakUserService).updateProfile(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.eq("raced.name"));

        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), "raced.name")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_IN_USE"));

        assertThat(userRepository.findById(admin.getUserId()).orElseThrow().getUsername()).isEqualTo(admin.getUsername());
    }

    @Test
    void aChangeOfLetterCaseAlone_isNoRename_andLeavingTheUsernameOutKeepsIt() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));

        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), admin.getUsername().toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(admin.getUsername()));
        mockMvc.perform(edit(org, admin, body("Adelaide", admin.getEmail(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(admin.getUsername()));

        Mockito.verify(keycloakUserService, Mockito.never()).usernameTakenByAnotherAccount(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(keycloakUserService, Mockito.never()).updateProfile(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.isNotNull());
    }

    @Test
    void aUsernameWithCharactersItCantHave_orTooShort_isRefused() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));

        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), "has space")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), "ab")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), "x@y.com")))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findById(admin.getUserId()).orElseThrow().getUsername()).isEqualTo(admin.getUsername());
    }

    @Test
    void renamingIsAudited_withTheOldAndNewUsername() throws Exception {
        Tenant org = tenant();
        User admin = user(org, "Ada", true);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        Mockito.when(keycloakUserService.usernameTakenByAnotherAccount(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(false);
        String wanted = "audited-" + UUID.randomUUID().toString().substring(0, 6);

        mockMvc.perform(edit(org, admin, body("Ada", admin.getEmail(), wanted))).andExpect(status().isOk());

        String recorded = jdbc.queryForObject("select previous_value::text || ' => ' || new_value::text from audit_events "
                + "where action = 'USER_UPDATED' and object_id = ? order by occurred_at desc limit 1", String.class, admin.getUserId());
        assertThat(recorded).contains(admin.getUsername()).contains(wanted);
    }
}
