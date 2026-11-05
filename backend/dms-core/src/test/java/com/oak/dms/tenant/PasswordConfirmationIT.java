package com.oak.dms.tenant;

import com.oak.dms.security.auth.AuthService;
import com.oak.dms.security.auth.KeycloakAuthClient;
import com.oak.dms.security.auth.dto.KeycloakTokenResponse;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating, changing or removing an organisation needs the platform administrator's OWN password, checked against
 * Keycloak every time: without it - or with a wrong one - nothing happens.
 */
@SpringBootTest
class PasswordConfirmationIT {

    private static final String RIGHT = "correct horse battery";
    private static final String WRONG = "not my password";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KeycloakAuthClient keycloak;
    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoSpyBean
    private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Mockito.when(keycloak.passwordGrant(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenAnswer(call -> RIGHT.equals(call.getArgument(1))
                        ? new KeycloakAuthClient.GrantResult.Success(new KeycloakTokenResponse("access", 300, "refresh-token", 300, "Bearer"))
                        : new KeycloakAuthClient.GrantResult.Failure(false, "invalid_grant"));
        Mockito.doReturn(false).when(authService).isMfaRequired(ArgumentMatchers.anyString());
    }

    private MockHttpServletRequestBuilder asPlatformAdmin(MockHttpServletRequestBuilder request, String password) {
        if (password != null) {
            request.header("X-Confirm-Password", password);
        }
        return request.with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("preferred_username", "platform.admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
    }

    private Tenant tenant() {
        String id = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder().tenantName("Confirm " + id).domain("confirm-" + id + ".invalid")
                .subscriptionTier("standard").status(Tenant.TenantStatus.active).build());
    }

    private User admin(Tenant tenant) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder().tenantId(tenant.getTenantId()).keycloakId(UUID.randomUUID())
                .email("ada-" + unique + "@example.invalid").username("ada-" + unique).firstName("Ada").lastName("Admin")
                .displayName("Ada Admin").status(User.UserStatus.active).verified(true).build());
    }

    private static String updateBody(Tenant org, String name) {
        return "{\"tenantName\":\"" + name + "\",\"subscriptionTier\":\"standard\",\"status\":\"" + org.getStatus().name() + "\"}";
    }

    private static String provisionBody() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return "{\"tenantName\":\"Brand new " + tag + "\",\"domain\":\"brand-new-" + tag + ".invalid\",\"subscriptionTier\":\"standard\","
                + "\"adminEmail\":\"first-" + tag + "@example.invalid\",\"adminUsername\":\"first-" + tag + "\",\"adminFirstName\":\"First\"}";
    }

    private int failedConfirmations() {
        Integer n = jdbc.queryForObject("select count(*) from audit_events where action = 'PASSWORD_CONFIRMATION_FAILED'", Integer.class);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- without a password: nothing happens

    @Test
    void withoutAPassword_everyGuardedActionIsRefused_andNothingChanges() throws Exception {
        Tenant org = tenant();
        User ada = admin(org);
        Mockito.when(keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(ada.getKeycloakId()));

        String create = provisionBody();
        mockMvc.perform(asPlatformAdmin(post("/api/v1/tenants/provision").contentType(MediaType.APPLICATION_JSON).content(create), null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_REQUIRED"));
        mockMvc.perform(asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(org, "Renamed")), null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_REQUIRED"));
        mockMvc.perform(asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId() + "/admins/" + ada.getUserId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"X\",\"lastName\":\"Y\",\"email\":\"x@example.invalid\"}"), null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_REQUIRED"));
        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId()), null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_REQUIRED"));
        mockMvc.perform(asPlatformAdmin(post("/api/v1/tenants/" + org.getTenantId() + "/shred-keys").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmTenantName\":\"" + org.getTenantName() + "\",\"reason\":\"test\",\"acknowledgeUnencrypted\":false}"), null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_REQUIRED"));

        Tenant after = tenantRepository.findById(org.getTenantId()).orElseThrow();
        assertThat(after.getTenantName()).isEqualTo(org.getTenantName());
        assertThat(after.isDeleted()).isFalse();
        assertThat(userRepository.findById(ada.getUserId()).orElseThrow().getEmail()).isEqualTo(ada.getEmail());
        assertThat(tenantRepository.existsByDomainAndDeletedFalse(
                create.replaceAll(".*\"domain\":\"([^\"]+)\".*", "$1"))).isFalse();
        // Not even asked of Keycloak: an empty password is refused before anything is checked.
        Mockito.verify(keycloak, Mockito.never()).passwordGrant(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    // ---------------------------------------------------------------- a wrong password: nothing happens, and it is recorded

    @Test
    void aWrongPassword_refusesTheAction_isAudited_andTheStatusIsNeverA401() throws Exception {
        Tenant org = tenant();
        int before = failedConfirmations();

        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId()), WRONG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_INCORRECT"));
        mockMvc.perform(asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(org, "Renamed")), WRONG))
                .andExpect(status().isForbidden());

        assertThat(tenantRepository.findById(org.getTenantId()).orElseThrow().isDeleted()).isFalse();
        assertThat(tenantRepository.findById(org.getTenantId()).orElseThrow().getTenantName()).isEqualTo(org.getTenantName());
        assertThat(failedConfirmations()).isEqualTo(before + 2);
        // The password is never written down - not in the audit record, not anywhere in the log table.
        Integer leaked = jdbc.queryForObject("select count(*) from audit_events where new_value::text like ?", Integer.class, "%" + WRONG + "%");
        assertThat(leaked).isZero();
    }

    @Test
    void theAnswerForAWrongPasswordAndAWrongCode_isTheSame() throws Exception {
        Tenant org = tenant();

        String body = mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId())
                        .header("X-Confirm-Totp", "000000"), WRONG))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("PASSWORD_INCORRECT").doesNotContain(WRONG);
    }

    // ---------------------------------------------------------------- the right password: it works

    @Test
    void theRightPassword_letsEachActionThrough_andLeavesNoSessionBehind() throws Exception {
        Tenant org = tenant();

        mockMvc.perform(asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(org, "Renamed " + org.getTenantId())), RIGHT))
                .andExpect(status().isOk());
        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId()), RIGHT))
                .andExpect(status().isNoContent());

        assertThat(tenantRepository.findById(org.getTenantId()).orElseThrow().getTenantName()).startsWith("Renamed");
        assertThat(tenantRepository.findById(org.getTenantId()).orElseThrow().isDeleted()).isTrue();
        // Confirming is not signing in: each check's session was ended at once.
        Mockito.verify(keycloak, Mockito.times(2)).revokeRefreshToken("refresh-token");
    }

    @Test
    void theCheckIsAgainstTheSignedInPersonsOwnAccount() throws Exception {
        Tenant org = tenant();

        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId()), RIGHT)).andExpect(status().isNoContent());

        Mockito.verify(keycloak).passwordGrant(ArgumentMatchers.eq("platform.admin"), ArgumentMatchers.eq(RIGHT), ArgumentMatchers.isNull());
    }

    // ---------------------------------------------------------------- accounts with an authenticator app

    @Test
    void anAccountWithAnAuthenticatorApp_mustSendTheCodeToo_andItIsPassedOn() throws Exception {
        Tenant org = tenant();
        Mockito.doReturn(true).when(authService).isMfaRequired("platform.admin");

        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId()), RIGHT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_OTP_REQUIRED"));
        assertThat(tenantRepository.findById(org.getTenantId()).orElseThrow().isDeleted()).isFalse();

        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId()).header("X-Confirm-Totp", "123456"), RIGHT))
                .andExpect(status().isNoContent());
        Mockito.verify(keycloak).passwordGrant("platform.admin", RIGHT, "123456");
    }

    // ---------------------------------------------------------------- when the check can't be made

    @Test
    void whenKeycloakCantBeReached_theActionIsRefused_notAllowed() throws Exception {
        Tenant org = tenant();
        Mockito.when(keycloak.passwordGrant(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(new KeycloakAuthClient.GrantResult.Failure(false, "keycloak_unreachable"));

        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId()), RIGHT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHECK_UNAVAILABLE"));
        assertThat(tenantRepository.findById(org.getTenantId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void aSessionThatCantSayWhoItIs_cantConfirm() throws Exception {
        Tenant org = tenant();

        mockMvc.perform(delete("/api/v1/tenants/" + org.getTenantId()).header("X-Confirm-Password", RIGHT)
                        .with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHECK_UNAVAILABLE"));
    }

    // ---------------------------------------------------------------- allowing online editing for an organisation

    private boolean editingAllowed(Tenant org) {
        List<Boolean> rows = jdbc.queryForList("select online_editing_allowed from tenant_platform_settings where tenant_id = ?",
                Boolean.class, org.getTenantId());
        return !rows.isEmpty() && rows.get(0);
    }

    private MockHttpServletRequestBuilder allowEditing(Tenant org, boolean allowed, String password) {
        return asPlatformAdmin(put("/api/v1/tenants/" + org.getTenantId() + "/online-editing")
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowed\":" + allowed + "}"), password);
    }

    @Test
    void allowingOnlineEditing_needsThePlatformAdministratorsOwnPassword_andNothingChangesWithoutIt() throws Exception {
        Tenant org = tenant();

        mockMvc.perform(allowEditing(org, true, null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_REQUIRED"));
        mockMvc.perform(allowEditing(org, true, WRONG))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_INCORRECT"));

        assertThat(editingAllowed(org)).isFalse();
        Integer audited = jdbc.queryForObject("select count(*) from audit_events where action = 'TENANT_ONLINE_EDITING_CHANGED' "
                + "and object_id = ?", Integer.class, org.getTenantId());
        assertThat(audited).isZero();
    }

    @Test
    void theRightPassword_allowsAndStopsOnlineEditing_eachTimeAudited_withTheOldAndNewValue() throws Exception {
        Tenant org = tenant();

        mockMvc.perform(allowEditing(org, true, RIGHT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(org.getTenantId().toString()))
                .andExpect(jsonPath("$.allowed").value(true));
        assertThat(editingAllowed(org)).isTrue();

        mockMvc.perform(allowEditing(org, false, RIGHT)).andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
        assertThat(editingAllowed(org)).isFalse();

        List<String> recorded = jdbc.queryForList("select previous_value::text || ' => ' || new_value::text from audit_events "
                + "where action = 'TENANT_ONLINE_EDITING_CHANGED' and object_id = ? order by occurred_at", String.class, org.getTenantId());
        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0)).contains("false").contains("true");
        assertThat(recorded.get(1)).contains("true").contains("false");
    }

    @Test
    void theListOfAllowedOrganisations_holdsOnlyThoseAllowed() throws Exception {
        Tenant allowed = tenant();
        Tenant notAllowed = tenant();
        mockMvc.perform(allowEditing(allowed, true, RIGHT)).andExpect(status().isOk());

        String body = mockMvc.perform(asPlatformAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/tenants/online-editing"), null))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains(allowed.getTenantId().toString()).doesNotContain(notAllowed.getTenantId().toString());
    }

    @Test
    void anOrganisationThatDoesntExist_cantBeAllowed() throws Exception {
        Tenant ghost = Tenant.builder().tenantId(UUID.randomUUID()).tenantName("Nobody").build();

        mockMvc.perform(allowEditing(ghost, true, RIGHT)).andExpect(status().isNotFound());
    }

    @Test
    void anOrganisationsOwnAdministrator_cantAllowOnlineEditingForThemselves() throws Exception {
        Tenant org = tenant();
        User ada = admin(org);

        mockMvc.perform(put("/api/v1/tenants/" + org.getTenantId() + "/online-editing").header("X-Confirm-Password", RIGHT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"allowed\":true}")
                        .with(jwt().jwt(b -> b.subject(ada.getKeycloakId().toString()).claim("tenant_id", org.getTenantId().toString())
                                .claim("preferred_username", ada.getUsername()))
                                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))))
                .andExpect(status().isForbidden());

        assertThat(editingAllowed(org)).isFalse();
    }

    // ---------------------------------------------------------------- who is NOT asked

    @Test
    void anOrganisationsOwnAdministrator_editingTheirOwnOrganisation_isNotAskedForAPlatformPassword() throws Exception {
        Tenant org = tenant();
        User ada = admin(org);

        mockMvc.perform(put("/api/v1/tenants/" + org.getTenantId()).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(org, "Our own new name"))
                        .with(jwt().jwt(b -> b.subject(ada.getKeycloakId().toString()).claim("tenant_id", org.getTenantId().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))))
                .andExpect(status().isOk());

        assertThat(tenantRepository.findById(org.getTenantId()).orElseThrow().getTenantName()).isEqualTo("Our own new name");
        Mockito.verify(keycloak, Mockito.never()).passwordGrant(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void anOrdinaryUser_isStillRefusedOutright_beforeAnyPasswordIsLookedAt() throws Exception {
        Tenant org = tenant();

        mockMvc.perform(delete("/api/v1/tenants/" + org.getTenantId()).header("X-Confirm-Password", RIGHT)
                        .with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("preferred_username", "someone"))))
                .andExpect(status().isForbidden());
        Mockito.verify(keycloak, Mockito.never()).passwordGrant(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }
}
