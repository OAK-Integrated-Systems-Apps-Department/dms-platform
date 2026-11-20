package com.oak.dms.security.auth;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * SEC-11 end to end: only an {@code active} account may use the API. Real PostgreSQL, real repositories, the real Spring
 * Security filter chain ({@code BearerTokenAuthenticationFilter}, {@code TenantContextFilter},
 * {@code ImpersonationContextFilter}, authorisation) - MockMvc is built with {@code springSecurity()} by
 * {@link WorkflowApiSupport}, as in {@code AccessDeniedAuditIT}.
 * <p>
 * <b>What is mocked, and why.</b> {@link KeycloakUserService} (the admin client; nothing here needs a Keycloak server -
 * {@code getPlatformRealmRoles} answers an empty list, so no one is a tenant admin through Keycloak); {@link RabbitTemplate}
 * (nothing here needs a broker); and {@link JwtDecoder}, used ONLY by the tests that send a real token (the session cookie
 * and the Authorization header) so that the real bearer-token filter and {@code CookieBearerTokenResolver} run without a
 * Keycloak server to fetch signing keys from. Every other request authenticates with the spring-security-test
 * {@code jwt()} principal that the other ITs use ({@code WorkflowFixtures#jwtFor}).
 * <p>
 * <b>Status changes are real database updates</b> made with JDBC between requests ({@code update users set status = ...}), so
 * neither a persistence context nor a first-level cache can hide them; every request is a separate MockMvc request with its
 * own session and EntityManager. Each test builds its own tenant, so the tests are independent and repeatable.
 * <p>
 * <b>Not testable without a real Keycloak:</b> {@code ImpersonationService#start}/{@code stop} (they create and read Keycloak
 * accounts - see {@code ImpersonationIT}, which needs Keycloak); that disabling an account in Keycloak stops NEW logins and
 * refreshes; and that a token Keycloak issued before the change is still accepted by the resource server (here the decoder is
 * a stand-in that accepts the token, which is exactly the situation SEC-11 describes). The impersonation GRANT is issued
 * directly with {@link ImpersonationTokenCodec}, so the filters are exercised exactly as on a real request.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class UserAccountStatusIT extends WorkflowApiSupport {

    /** Self-service, authenticated-only: answers 200 to any ACTIVE signed-in user, and needs no permission or Keycloak. */
    private static final String PREFS = "/api/v1/users/me/preferences";
    private static final String COOKIE_TOKEN = "sec11-session-cookie-token";
    private static final String HEADER_TOKEN = "sec11-bearer-header-token";

    @MockitoBean
    private KeycloakUserService keycloak;
    @MockitoBean
    private RabbitTemplate rabbitTemplate;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private ImpersonationTokenCodec grants;

    private UUID tenantId;
    private User alice;
    private User admin;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        alice = fx.user(tenantId, "Alice");
        admin = fx.user(tenantId, "Admin");
    }

    // ------------------------------------------------------------------ helpers

    private void status(User user, String status) {
        int rows = fx.jdbc().update("update users set status = ? where user_id = ?", status, user.getUserId());
        assertThat(rows).as("the status update reached the database").isEqualTo(1);
    }

    private void tenantStatus(String status) {
        fx.jdbc().update("update tenants set status = ? where tenant_id = ?", status, tenantId);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, JwtRequestPostProcessor as) throws Exception {
        return mvc.perform(request.with(as)).andReturn().getResponse();
    }

    private MockHttpServletResponse getPrefs(User as) throws Exception {
        return send(get(PREFS), fx.jwtFor(as));
    }

    private static void assertAccountInactive(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCOUNT_INACTIVE").doesNotContain("ORGANISATION_INACTIVE");
    }

    private JwtRequestPostProcessor platformAdminJwtFor(User user) {
        return jwt().jwt(b -> b.subject(user.getKeycloakId().toString()).claim("tenant_id", user.getTenantId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }

    private Jwt realToken(String value, User user) {
        return Jwt.withTokenValue(value).header("alg", "none").subject(user.getKeycloakId().toString())
                .claim("tenant_id", user.getTenantId().toString())
                .claim("realm_access", Map.of("roles", List.of()))
                .issuedAt(Instant.now().minusSeconds(120)).expiresAt(Instant.now().plusSeconds(1500)).build();
    }

    private Cookie impersonationCookie(User impersonator, User target) {
        return new Cookie(AuthCookieService.IMPERSONATION_COOKIE, grants.issue(impersonator.getUserId(), target.getUserId(),
                tenantId, Instant.now().minusSeconds(5), Instant.now().plusSeconds(600)));
    }

    private Long preferenceRows(User user) {
        return fx.jdbc().queryForObject("select count(*) from user_preferences where user_id = ?", Long.class, user.getUserId());
    }

    // ------------------------------------------------------------------ 1-4: the contract

    @Test
    void anActiveUser_withAValidJwt_reachesAnAuthorizedEndpoint() throws Exception {
        MockHttpServletResponse response = getPrefs(alice);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("editorLayout");
    }

    @Test
    void aLockedUser_withAnOtherwiseValidJwt_getsHttp403_accountInactive() throws Exception {
        assertThat(getPrefs(alice).getStatus()).as("before the lock").isEqualTo(200);
        status(alice, "locked");

        MockHttpServletResponse response = getPrefs(alice);

        assertAccountInactive(response);
        assertThat(response.getContentAsString()).containsIgnoringCase("locked");
    }

    @Test
    void aDisabledUser_withAPreviouslyIssuedJwt_getsHttp403_accountInactive() throws Exception {
        assertThat(getPrefs(alice).getStatus()).as("the token worked while the account was active").isEqualTo(200);
        status(alice, "disabled");

        // the same, unexpired token: nothing about it changed - only the local account status did
        MockHttpServletResponse response = getPrefs(alice);

        assertAccountInactive(response);
        assertThat(response.getContentAsString()).containsIgnoringCase("disabled");
    }

    @Test
    void reactivatingTheSameUser_allowsAccessOnTheNextRequest() throws Exception {
        status(alice, "locked");
        assertAccountInactive(getPrefs(alice));
        status(alice, "active");
        assertThat(getPrefs(alice).getStatus()).isEqualTo(200);

        status(alice, "disabled");
        assertAccountInactive(getPrefs(alice));
        status(alice, "active");
        assertThat(getPrefs(alice).getStatus()).isEqualTo(200);
    }

    @Test
    void anAdministratorWhoLocksAndReactivatesThroughTheApi_takesEffectOnTheVeryNextRequest() throws Exception {
        Map<String, Object> lock = Map.of("firstName", "Alice", "lastName", "Tester", "mfaEnabled", false, "status", "locked");
        Map<String, Object> reactivate = Map.of("firstName", "Alice", "lastName", "Tester", "mfaEnabled", false, "status", "active");
        assertThat(getPrefs(alice).getStatus()).isEqualTo(200);

        call(put("/api/v1/users/" + alice.getUserId()), fx.tenantAdminJwtFor(admin), lock, 200);
        assertAccountInactive(getPrefs(alice));

        call(put("/api/v1/users/" + alice.getUserId()), fx.tenantAdminJwtFor(admin), reactivate, 200);
        assertThat(getPrefs(alice).getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 5: organisation precedence

    @Test
    void aSuspendedOrganisation_returnsItsOwnCode_beforeTheAccountStatus() throws Exception {
        tenantStatus("suspended");

        MockHttpServletResponse active = getPrefs(alice);
        assertThat(active.getStatus()).isEqualTo(403);
        assertThat(active.getContentAsString()).contains("ORGANISATION_INACTIVE");

        status(alice, "locked");
        MockHttpServletResponse locked = getPrefs(alice);
        assertThat(locked.getStatus()).isEqualTo(403);
        assertThat(locked.getContentAsString()).contains("ORGANISATION_INACTIVE").doesNotContain("ACCOUNT_INACTIVE");

        tenantStatus("active");
        assertAccountInactive(getPrefs(alice));
    }

    // ------------------------------------------------------------------ 6-7: other endpoints, and signing out

    @Test
    void lockedAndDisabledUsers_cannotReachAnyOtherProtectedEndpoint_whateverTheMethod() throws Exception {
        String[] paths = {PREFS, "/api/v1/users/me", "/api/v1/documents", "/api/v1/folders", "/api/v1/users",
                "/api/v1/workflow/instances", "/api/v1/notifications"};
        for (String state : new String[] {"locked", "disabled"}) {
            status(alice, state);
            for (String path : paths) {
                assertAccountInactive(send(get(path), fx.jwtFor(alice)));
                assertAccountInactive(send(post(path).contentType("application/json").content("{}"), fx.jwtFor(alice)));
                assertAccountInactive(send(put(path).contentType("application/json").content("{}"), fx.jwtFor(alice)));
                assertAccountInactive(send(delete(path), fx.jwtFor(alice)));
            }
        }
    }

    @Test
    void signingOut_remainsAvailableToAnInactiveUser_andUnlocksNothingElse() throws Exception {
        status(alice, "locked");

        MockHttpServletResponse logout = send(post("/api/v1/auth/logout"), fx.jwtFor(alice));
        assertThat(logout.getStatus()).as("signing out is the one open path").isBetween(200, 299);

        assertAccountInactive(getPrefs(alice));
        assertAccountInactive(send(put(PREFS).contentType("application/json").content("{\"editorLayout\":\"fullpage\"}"),
                fx.jwtFor(alice)));
        assertThat(preferenceRows(alice)).as("nothing was written by the refused requests").isZero();
    }

    // ------------------------------------------------------------------ 8: platform administrators

    @Test
    void aLockedPlatformAdministrator_getsTheSameAccountRefusal() throws Exception {
        // Platform administrators normally have no local row; this one does, and it is locked. The role is no exemption.
        assertThat(send(get("/api/v1/tenants"), platformAdminJwtFor(admin)).getStatus()).as("active").isEqualTo(200);
        status(admin, "locked");

        assertAccountInactive(send(get("/api/v1/tenants"), platformAdminJwtFor(admin)));
    }

    // ------------------------------------------------------------------ 9-11: impersonation

    @Test
    void aLockedAdministrator_cannotUseAnExistingImpersonationGrant() throws Exception {
        status(admin, "locked");

        MockHttpServletResponse response = mvc.perform(put(PREFS).contentType("application/json")
                        .content("{\"editorLayout\":\"fullpage\"}").cookie(impersonationCookie(admin, alice))
                        .with(fx.tenantAdminJwtFor(admin))).andReturn().getResponse();

        assertAccountInactive(response);
        assertThat(preferenceRows(alice)).as("nothing was done as the target").isZero();
        assertThat(preferenceRows(admin)).as("nor as the administrator").isZero();
    }

    @Test
    void aLockedImpersonationTarget_cannotContinueToBeImpersonated() throws Exception {
        status(alice, "locked");

        MockHttpServletResponse response = mvc.perform(put(PREFS).contentType("application/json")
                        .content("{\"editorLayout\":\"fullpage\"}").cookie(impersonationCookie(admin, alice))
                        .with(fx.tenantAdminJwtFor(admin))).andReturn().getResponse();

        assertThat(response.getStatus()).as("the request carries on as the administrator's own").isEqualTo(200);
        assertThat(preferenceRows(alice)).as("nothing was saved for the locked target").isZero();
        assertThat(preferenceRows(admin)).as("it ran as the administrator").isEqualTo(1);
        assertThat(response.getHeaders("Set-Cookie")).as("the dead grant is cleared")
                .anySatisfy(c -> assertThat(c).contains(AuthCookieService.IMPERSONATION_COOKIE + "=").contains("Max-Age=0"));
    }

    @Test
    void aDisabledImpersonationTarget_alsoEndsTheGrant() throws Exception {
        status(alice, "disabled");

        MockHttpServletResponse response = mvc.perform(put(PREFS).contentType("application/json")
                        .content("{\"editorLayout\":\"fullpage\"}").cookie(impersonationCookie(admin, alice))
                        .with(fx.tenantAdminJwtFor(admin))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(preferenceRows(alice)).isZero();
        assertThat(preferenceRows(admin)).isEqualTo(1);
    }

    @Test
    void anActiveImpersonation_behavesAsBefore_actingAsTheTarget() throws Exception {
        MockHttpServletResponse response = mvc.perform(put(PREFS).contentType("application/json")
                        .content("{\"editorLayout\":\"fullpage\"}").cookie(impersonationCookie(admin, alice))
                        .with(fx.tenantAdminJwtFor(admin))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(preferenceRows(alice)).as("saved for the person being acted as").isEqualTo(1);
        assertThat(preferenceRows(admin)).isZero();
    }

    // ------------------------------------------------------------------ 12-13: the refusal itself

    @Test
    void theRefusalBody_exposesNoIdentifiersOrPersonalData() throws Exception {
        status(alice, "locked");

        String body = getPrefs(alice).getContentAsString();

        assertThat(body).contains("\"status\":403").contains("Forbidden").contains(PREFS)
                .doesNotContain(alice.getUserId().toString()).doesNotContain(alice.getKeycloakId().toString())
                .doesNotContain(tenantId.toString()).doesNotContain(alice.getEmail())
                .doesNotContainIgnoringCase("sql").doesNotContainIgnoringCase("exception").doesNotContain("stackTrace");
    }

    @Test
    void aRefusedRequest_writesNothing_noRowNoVerifiedFlagNoAuditEvent() throws Exception {
        fx.jdbc().update("update users set verified = false where user_id = ?", alice.getUserId());
        status(alice, "locked");
        Map<String, Object> before = fx.jdbc().queryForMap("select verified, updated_at, status from users where user_id = ?",
                alice.getUserId());
        long auditBefore = fx.count("select count(*) from audit_events where tenant_id = ?", tenantId);

        assertAccountInactive(getPrefs(alice));
        assertAccountInactive(send(put(PREFS).contentType("application/json").content("{\"editorLayout\":\"fullpage\"}"),
                fx.jwtFor(alice)));

        assertThat(fx.jdbc().queryForMap("select verified, updated_at, status from users where user_id = ?", alice.getUserId()))
                .as("the user row is untouched (not marked verified, not updated)").isEqualTo(before);
        assertThat(preferenceRows(alice)).isZero();
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ?", tenantId))
                .as("a refusal by the filter is not an audited business action").isEqualTo(auditBefore);
    }

    // ------------------------------------------------------------------ 14: both ways a token can arrive

    @Test
    void theSessionCookie_isRefusedForALockedUser_andAcceptedForAnActiveOne() throws Exception {
        when(jwtDecoder.decode(COOKIE_TOKEN)).thenReturn(realToken(COOKIE_TOKEN, alice));
        MockHttpServletRequestBuilder request = get(PREFS).cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE, COOKIE_TOKEN));

        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).as("active").isEqualTo(200);

        status(alice, "locked");
        assertAccountInactive(mvc.perform(get(PREFS).cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE, COOKIE_TOKEN)))
                .andReturn().getResponse());

        status(alice, "active");
        assertThat(mvc.perform(get(PREFS).cookie(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE, COOKIE_TOKEN)))
                .andReturn().getResponse().getStatus()).as("reactivated").isEqualTo(200);
    }

    @Test
    void anAuthorizationBearerHeader_isRefusedForADisabledUser_andAcceptedForAnActiveOne() throws Exception {
        when(jwtDecoder.decode(HEADER_TOKEN)).thenReturn(realToken(HEADER_TOKEN, alice));

        assertThat(mvc.perform(get(PREFS).header("Authorization", "Bearer " + HEADER_TOKEN)).andReturn().getResponse()
                .getStatus()).as("active").isEqualTo(200);

        status(alice, "disabled");
        assertAccountInactive(mvc.perform(get(PREFS).header("Authorization", "Bearer " + HEADER_TOKEN)).andReturn().getResponse());
    }

    // ------------------------------------------------------------------ unchanged behaviour

    @Test
    void aSoftDeletedUser_isStillGivenNoTenantContext_exactlyAsBefore() throws Exception {
        fx.jdbc().update("update users set is_deleted = true, deleted_at = now(), status = 'disabled' where user_id = ?",
                alice.getUserId());

        MockHttpServletResponse response = getPrefs(alice);

        assertThat(response.getStatus()).as("no tenant context -> the existing 401, not the new 403").isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("ACCOUNT_INACTIVE");
    }

    @Test
    void anUnknownLocalUser_keepsTheSelfProvisioningBehaviour() throws Exception {
        UUID newKeycloakId = UUID.randomUUID();
        JwtRequestPostProcessor newcomer = jwt().jwt(b -> b.subject(newKeycloakId.toString())
                        .claim("tenant_id", tenantId.toString()).claim("email", newKeycloakId + "@sec11.invalid")
                        .claim("preferred_username", "sec11-" + newKeycloakId))
                .authorities();

        MockHttpServletResponse response = send(post("/api/v1/users/me/provision").contentType("application/json")
                .content("{\"firstName\":\"New\",\"lastName\":\"Person\"}"), newcomer);

        assertThat(response.getStatus()).as("the provisioning endpoint is still reachable with no local row").isEqualTo(201);
        assertThat(fx.count("select count(*) from users where keycloak_id = ? and status = 'active'", newKeycloakId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ SEC-11: signing out must not write to an inactive user's record

    private static final String USER_ROW = "select verified, verified_at, status, updated_at, first_name, last_name, display_name,"
            + " email, mfa_enabled, is_deleted from users where user_id = ?";

    private void unverified(User user) {
        fx.jdbc().update("update users set verified = false, verified_at = null where user_id = ?", user.getUserId());
    }

    private void assertLogoutLeavesTheRecordAlone(String state) throws Exception {
        unverified(alice);
        status(alice, state);
        Map<String, Object> before = fx.jdbc().queryForMap(USER_ROW, alice.getUserId());
        long logoutsBefore = fx.count("select count(*) from audit_events where tenant_id = ? and action = ?", tenantId,
                com.oak.dms.audit.catalog.AuditActionCatalog.LOGOUT);
        long auditBefore = fx.count("select count(*) from audit_events where tenant_id = ?", tenantId);

        MockHttpServletResponse logout = send(post("/api/v1/auth/logout"), fx.jwtFor(alice));

        assertThat(logout.getStatus()).as("signing out still works for a " + state + " user").isBetween(200, 299);
        Map<String, Object> after = fx.jdbc().queryForMap(USER_ROW, alice.getUserId());
        assertThat(after).as("the user record is identical: not verified, not unlocked, nothing updated").isEqualTo(before);
        assertThat(after.get("verified")).isEqualTo(false);
        assertThat(after.get("verified_at")).isNull();
        assertThat(after.get("status")).isEqualTo(state);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = ?", tenantId,
                com.oak.dms.audit.catalog.AuditActionCatalog.LOGOUT)).as("the logout itself is still audited").isEqualTo(logoutsBefore + 1);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ?", tenantId))
                .as("and nothing else was recorded").isEqualTo(auditBefore + 1);
        assertThat(preferenceRows(alice)).isZero();

        assertAccountInactive(getPrefs(alice));   // signed out or not, the account is still refused everywhere else
        assertThat(fx.jdbc().queryForMap(USER_ROW, alice.getUserId())).as("and still unchanged afterwards").isEqualTo(before);
    }

    @Test
    void aLockedUnverifiedUser_logsOut_withoutBecomingVerified_orUnlocked() throws Exception {
        assertLogoutLeavesTheRecordAlone("locked");
    }

    @Test
    void aDisabledUnverifiedUser_logsOut_withoutBecomingVerified_orReenabled() throws Exception {
        assertLogoutLeavesTheRecordAlone("disabled");
    }

    @Test
    void anActiveUnverifiedUser_stillBecomesVerified_onTheirRequests_includingLogout() throws Exception {
        unverified(alice);

        assertThat(send(post("/api/v1/auth/logout"), fx.jwtFor(alice)).getStatus()).isBetween(200, 299);

        Map<String, Object> row = fx.jdbc().queryForMap(USER_ROW, alice.getUserId());
        assertThat(row.get("verified")).as("existing behaviour for an active account is unchanged").isEqualTo(true);
        assertThat(row.get("verified_at")).isNotNull();
        assertThat(row.get("status")).isEqualTo("active");
    }

    @Test
    void anInactiveUsersRequests_neverChangeTheirVerificationOrStatus_onAnyEndpoint() throws Exception {
        for (String state : new String[] {"locked", "disabled"}) {
            unverified(alice);
            status(alice, state);
            Map<String, Object> before = fx.jdbc().queryForMap(USER_ROW, alice.getUserId());

            assertAccountInactive(getPrefs(alice));
            assertAccountInactive(send(put(PREFS).contentType("application/json").content("{\"editorLayout\":\"fullpage\"}"),
                    fx.jwtFor(alice)));
            send(post("/api/v1/auth/logout"), fx.jwtFor(alice));

            assertThat(fx.jdbc().queryForMap(USER_ROW, alice.getUserId())).as(state).isEqualTo(before);
        }
    }

    @Test
    void aSuspendedOrganisation_stillGivesItsOwnCode_andALockedUnverifiedUsersLogoutStillWritesNothing() throws Exception {
        unverified(alice);
        status(alice, "locked");
        tenantStatus("suspended");
        Map<String, Object> before = fx.jdbc().queryForMap(USER_ROW, alice.getUserId());

        MockHttpServletResponse refused = getPrefs(alice);
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).contains("ORGANISATION_INACTIVE").doesNotContain("ACCOUNT_INACTIVE");

        assertThat(send(post("/api/v1/auth/logout"), fx.jwtFor(alice)).getStatus()).isBetween(200, 299);
        assertThat(fx.jdbc().queryForMap(USER_ROW, alice.getUserId())).isEqualTo(before);
    }
}
