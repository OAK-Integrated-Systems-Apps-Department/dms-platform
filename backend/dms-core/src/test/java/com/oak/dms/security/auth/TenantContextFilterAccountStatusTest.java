package com.oak.dms.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-11: only an {@code active} account may use the API. The JWT is validated offline, so a token issued before an
 * administrator locked or disabled the account is still valid - the status has to be read from the local user row on
 * every request. No database, Keycloak or Spring context is used: the repositories are mocks, and one test group runs the
 * real {@link BearerTokenAuthenticationFilter} with the real {@link CookieBearerTokenResolver} in front of the filter, so the
 * cookie path and the header path are both exercised.
 */
class TenantContextFilterAccountStatusTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID otherTenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID keycloakId = UUID.randomUUID();

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final TenantContextFilter filter = new TenantContextFilter(users, userService,
            new OrganisationStatusGuard(tenants), new ObjectMapper());

    /** What the "controller" at the end of the chain saw. */
    private final AtomicReference<UUID> seenTenant = new AtomicReference<>();
    private final AtomicReference<UUID> seenUser = new AtomicReference<>();
    private final AtomicReference<UUID> seenImpersonator = new AtomicReference<>();
    private boolean reachedEnd;
    private boolean noContextAtEnd;

    private final HttpServlet endpoint = new HttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            reachedEnd = true;
            seenTenant.set(TenantContext.getTenantIdOrNull());
            seenUser.set(TenantContext.getKeycloakIdOrNull() == null ? null : TenantContext.getUserId());
            seenImpersonator.set(TenantContext.getImpersonatorIdOrNull());
            try {
                TenantContext.getTenantId();
            } catch (IllegalStateException e) {
                noContextAtEnd = true;
            }
        }
    };

    @BeforeEach
    void setUp() {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(Tenant.builder().tenantId(tenantId)
                .status(Tenant.TenantStatus.active).deleted(false).build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // ---- helpers

    private User user(User.UserStatus status) {
        return User.builder().userId(userId).tenantId(tenantId).keycloakId(keycloakId).verified(true).status(status).build();
    }

    private void localUserIs(User.UserStatus status) {
        when(users.findByKeycloakIdAndDeletedFalse(keycloakId)).thenReturn(Optional.of(user(status)));
    }

    private Jwt token() {
        return Jwt.withTokenValue("tok").header("alg", "none").subject(keycloakId.toString())
                .claim("tenant_id", otherTenantId.toString())        // a claim naming ANOTHER tenant must never be believed
                .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(1500)).build();
    }

    private void signedIn(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token(),
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    /** The filter alone, with the user already authenticated (what BearerTokenAuthenticationFilter leaves behind). */
    private MockHttpServletResponse call(String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, new MockFilterChain(endpoint));
        return response;
    }

    /** The real bearer-token filter and cookie resolver, then the status filter, then the endpoint. */
    private MockHttpServletResponse viaTokenFilters(MockHttpServletRequest request, jakarta.servlet.Filter... after)
            throws Exception {
        AuthenticationManager manager = authentication -> {
            BearerTokenAuthenticationToken bearer = (BearerTokenAuthenticationToken) authentication;
            assertThat(bearer.getToken()).isEqualTo("tok");
            return new JwtAuthenticationToken(token(), java.util.List.of());
        };
        BearerTokenAuthenticationFilter bearerFilter = new BearerTokenAuthenticationFilter(manager);
        bearerFilter.setBearerTokenResolver(new CookieBearerTokenResolver());
        jakarta.servlet.Filter[] chain = new jakarta.servlet.Filter[2 + after.length];
        chain[0] = bearerFilter;
        chain[1] = filter;
        System.arraycopy(after, 0, chain, 2, after.length);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(endpoint, chain).doFilter(request, response);
        return response;
    }

    private static MockHttpServletRequest withBearerHeader(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer tok");
        return request;
    }

    private static MockHttpServletRequest withSessionCookie(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setCookies(new Cookie(AuthCookieService.ACCESS_TOKEN_COOKIE, "tok"));
        return request;
    }

    // ---- the contract

    @Test
    void anActiveUser_withAValidToken_isAllowed_andBoundToTheTenantOfItsOwnRow() throws Exception {
        localUserIs(User.UserStatus.active);
        signedIn();

        MockHttpServletResponse response = call("GET", "/api/v1/documents");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(reachedEnd).isTrue();
        assertThat(seenTenant.get()).isEqualTo(tenantId);
        assertThat(seenUser.get()).isEqualTo(userId);
    }

    @Test
    void aLockedUser_withAValidToken_isRefusedWith403_andNothingIsBoundOrWritten() throws Exception {
        localUserIs(User.UserStatus.locked);
        signedIn();

        MockHttpServletResponse response = call("GET", "/api/v1/documents");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCOUNT_INACTIVE").contains("locked");
        assertThat(reachedEnd).as("the request never reaches the controller").isFalse();
        verify(userService, never()).markVerified(any());
        assertThat(TenantContext.getTenantIdOrNull()).as("nothing stays bound").isNull();
    }

    @Test
    void aDisabledUser_withATokenIssuedBeforeTheyWereDisabled_isRefused() throws Exception {
        localUserIs(User.UserStatus.disabled);
        signedIn();   // the token is unexpired and correctly signed: only the local status says no

        MockHttpServletResponse response = call("POST", "/api/v1/documents");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ACCOUNT_INACTIVE").contains("disabled");
        assertThat(reachedEnd).isFalse();
    }

    @Test
    void everyMethodAndEveryProtectedPath_isRefused_butSigningOutIsNot() throws Exception {
        localUserIs(User.UserStatus.locked);
        for (String method : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE"}) {
            signedIn();
            assertThat(call(method, "/api/v1/users/me").getStatus()).as(method + " /users/me").isEqualTo(403);
            signedIn();
            assertThat(call(method, "/api/v1/documents/" + UUID.randomUUID()).getStatus()).isEqualTo(403);
        }
        reachedEnd = false;
        signedIn();
        assertThat(call("POST", "/api/v1/auth/logout").getStatus()).isEqualTo(200);
        assertThat(reachedEnd).as("signing out still works for a locked user").isTrue();
    }

    @Test
    void theRefusal_namesNoTableConstraintOrId_andIsTheSameShapeAsTheOrganisationRefusal() throws Exception {
        localUserIs(User.UserStatus.locked);
        signedIn();

        String body = call("GET", "/api/v1/documents").getContentAsString();

        assertThat(body).contains("\"status\":403").contains("Forbidden").contains("/api/v1/documents")
                .doesNotContain(userId.toString()).doesNotContain(keycloakId.toString()).doesNotContain(tenantId.toString())
                .doesNotContainIgnoringCase("sql").doesNotContainIgnoringCase("exception");
    }

    @Test
    void aLockedUser_isNotMarkedVerified_andNothingIsWritten() throws Exception {
        when(users.findByKeycloakIdAndDeletedFalse(keycloakId)).thenReturn(Optional.of(User.builder().userId(userId)
                .tenantId(tenantId).keycloakId(keycloakId).verified(false).status(User.UserStatus.locked).build()));
        signedIn();

        call("GET", "/api/v1/documents");

        verify(userService, never()).markVerified(any());
        verify(users, never()).save(any());
    }

    @Test
    void aReactivatedUser_isAllowedAgain_onTheNextRequest() throws Exception {
        localUserIs(User.UserStatus.locked);
        signedIn();
        assertThat(call("GET", "/api/v1/documents").getStatus()).isEqualTo(403);

        localUserIs(User.UserStatus.active);   // an administrator sets the status back to active
        signedIn();
        reachedEnd = false;
        assertThat(call("GET", "/api/v1/documents").getStatus()).isEqualTo(200);
        assertThat(reachedEnd).isTrue();
    }

    @Test
    void aPlatformAdminRole_isNotAnExemption_forTheirOwnLockedAccount() throws Exception {
        localUserIs(User.UserStatus.locked);
        signedIn("ROLE_PLATFORM_ADMIN");

        assertThat(call("GET", "/api/v1/tenants").getStatus()).isEqualTo(403);
        assertThat(reachedEnd).isFalse();
    }

    // ---- the users the filter has never bound

    @Test
    void aSoftDeletedUser_isNotFound_soNoContextIsBound_exactlyAsBefore() throws Exception {
        when(users.findByKeycloakIdAndDeletedFalse(keycloakId)).thenReturn(Optional.empty());   // deactivate sets is_deleted
        signedIn();

        MockHttpServletResponse response = call("GET", "/api/v1/documents");

        assertThat(response.getStatus()).as("the filter itself does not refuse").isEqualTo(200);
        assertThat(reachedEnd).isTrue();
        assertThat(noContextAtEnd).as("any tenant-scoped call then fails fast with no tenant context (401)").isTrue();
        assertThat(seenTenant.get()).isNull();
    }

    @Test
    void anUnknownLocalUser_keepsTheExistingProvisioningBehaviour() throws Exception {
        when(users.findByKeycloakIdAndDeletedFalse(keycloakId)).thenReturn(Optional.empty());
        signedIn();

        MockHttpServletResponse response = call("POST", "/api/v1/users/me/provision");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(reachedEnd).as("the provisioning endpoint is still reachable with no local row").isTrue();
        assertThat(noContextAtEnd).isTrue();
    }

    @Test
    void aTokenWithoutAUsableSubject_isStillTreatedAsNoLocalUser_notAnError() throws Exception {
        Jwt noSubject = Jwt.withTokenValue("tok").header("alg", "none").claim("x", "y")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(noSubject));

        assertThat(call("GET", "/api/v1/documents").getStatus()).isEqualTo(200);
        assertThat(noContextAtEnd).isTrue();
    }

    // ---- tenant isolation and the organisation check are unchanged

    @Test
    void theTenantIsAlwaysTheOneOnTheUsersRow_neverTheOneInATokenClaimOrAHeader() throws Exception {
        localUserIs(User.UserStatus.active);
        signedIn();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/documents");
        request.addHeader("X-Tenant-Id", otherTenantId.toString());
        request.setParameter("tenantId", otherTenantId.toString());

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain(endpoint));

        assertThat(seenTenant.get()).isEqualTo(tenantId).isNotEqualTo(otherTenantId);
    }

    @Test
    void aSuspendedOrganisation_stillGetsItsOwnCode_beforeTheAccountStatusIsLookedAt() throws Exception {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(Tenant.builder().tenantId(tenantId)
                .status(Tenant.TenantStatus.suspended).deleted(false).build()));
        localUserIs(User.UserStatus.locked);
        signedIn();

        String body = call("GET", "/api/v1/documents").getContentAsString();

        assertThat(body).contains(OrganisationStatusGuard.CODE).doesNotContain("ACCOUNT_INACTIVE");
    }

    // ---- both authentication paths: Authorization header and the httpOnly session cookie

    @Test
    void aLockedUser_isRefused_whetherTheTokenArrivesAsABearerHeaderOrAsTheSessionCookie() throws Exception {
        localUserIs(User.UserStatus.locked);

        MockHttpServletResponse header = viaTokenFilters(withBearerHeader("/api/v1/documents"));
        assertThat(header.getStatus()).isEqualTo(403);
        assertThat(reachedEnd).isFalse();

        SecurityContextHolder.clearContext();
        MockHttpServletResponse cookie = viaTokenFilters(withSessionCookie("/api/v1/documents"));
        assertThat(cookie.getStatus()).isEqualTo(403);
        assertThat(cookie.getContentAsString()).contains("ACCOUNT_INACTIVE");
        assertThat(reachedEnd).isFalse();
    }

    @Test
    void anActiveUser_isAllowed_onBothAuthenticationPaths() throws Exception {
        localUserIs(User.UserStatus.active);

        assertThat(viaTokenFilters(withBearerHeader("/api/v1/documents")).getStatus()).isEqualTo(200);
        assertThat(reachedEnd).isTrue();
        assertThat(seenTenant.get()).isEqualTo(tenantId);

        reachedEnd = false;
        SecurityContextHolder.clearContext();
        assertThat(viaTokenFilters(withSessionCookie("/api/v1/documents")).getStatus()).isEqualTo(200);
        assertThat(reachedEnd).isTrue();
    }

    // ---- impersonation cannot be used to get round the status check

    @Test
    void anAdminWhoIsLocked_cannotActAsAnyone_theGrantIsNeverReached() throws Exception {
        localUserIs(User.UserStatus.locked);
        ImpersonationTokenCodec codec = mock(ImpersonationTokenCodec.class);
        ImpersonationContextFilter impersonation = new ImpersonationContextFilter(codec, mock(AuthCookieService.class),
                mock(AuthorizationService.class), users);
        MockHttpServletRequest request = withBearerHeader("/api/v1/documents");
        request.setCookies(new Cookie(AuthCookieService.IMPERSONATION_COOKIE, "grant"));

        MockHttpServletResponse response = viaTokenFilters(request, impersonation);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(reachedEnd).isFalse();
        verify(codec, never()).verify(anyString());
    }

    @Test
    void anImpersonationTarget_whoIsLocked_endsTheGrant_andTheRequestStaysTheAdminsOwn() throws Exception {
        localUserIs(User.UserStatus.active);   // the admin
        UUID targetId = UUID.randomUUID();
        ImpersonationTokenCodec codec = mock(ImpersonationTokenCodec.class);
        when(codec.verify("grant")).thenReturn(Optional.of(new ImpersonationTokenCodec.Grant(userId, targetId, tenantId,
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(600))));
        AuthorizationService authz = mock(AuthorizationService.class);
        when(authz.hasPermission(PermissionCodes.OTUA_USERS_IMPERSONATE)).thenReturn(true);
        AuthCookieService cookies = mock(AuthCookieService.class);
        when(users.findByUserIdAndTenantIdAndDeletedFalse(targetId, tenantId)).thenReturn(Optional.of(User.builder()
                .userId(targetId).tenantId(tenantId).keycloakId(UUID.randomUUID()).status(User.UserStatus.locked).build()));
        ImpersonationContextFilter impersonation = new ImpersonationContextFilter(codec, cookies, authz, users);
        MockHttpServletRequest request = withBearerHeader("/api/v1/documents");
        request.setCookies(new Cookie(AuthCookieService.IMPERSONATION_COOKIE, "grant"));

        MockHttpServletResponse response = viaTokenFilters(request, impersonation);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(cookies).clearImpersonationCookie(any());
        assertThat(seenImpersonator.get()).as("the locked target was NOT impersonated").isNull();
        assertThat(seenUser.get()).isEqualTo(userId);
    }

    @Test
    void anImpersonationTarget_whoIsActive_isStillImpersonated_soTheCheckDoesNotBreakTheFeature() throws Exception {
        localUserIs(User.UserStatus.active);
        UUID targetId = UUID.randomUUID();
        ImpersonationTokenCodec codec = mock(ImpersonationTokenCodec.class);
        when(codec.verify("grant")).thenReturn(Optional.of(new ImpersonationTokenCodec.Grant(userId, targetId, tenantId,
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(600))));
        AuthorizationService authz = mock(AuthorizationService.class);
        when(authz.hasPermission(PermissionCodes.OTUA_USERS_IMPERSONATE)).thenReturn(true);
        when(users.findByUserIdAndTenantIdAndDeletedFalse(targetId, tenantId)).thenReturn(Optional.of(User.builder()
                .userId(targetId).tenantId(tenantId).keycloakId(UUID.randomUUID()).status(User.UserStatus.active).build()));
        ImpersonationContextFilter impersonation = new ImpersonationContextFilter(codec, mock(AuthCookieService.class),
                authz, users);
        MockHttpServletRequest request = withBearerHeader("/api/v1/documents");
        request.setCookies(new Cookie(AuthCookieService.IMPERSONATION_COOKIE, "grant"));

        assertThat(viaTokenFilters(request, impersonation).getStatus()).isEqualTo(200);
        assertThat(seenImpersonator.get()).isEqualTo(userId);
    }

    @Test
    void theStatusCheck_failsClosedOnAnUnexpectedStatusValue_viaTheEnumContract() {
        // The only values are active, locked, disabled; anything that is not active is refused, never allowed by default.
        assertThat(User.UserStatus.values()).containsExactlyInAnyOrder(User.UserStatus.active, User.UserStatus.locked,
                User.UserStatus.disabled);
        assertThatThrownBy(() -> User.UserStatus.valueOf("suspended")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- SEC-11: signing out must not write to an inactive user's record

    private User unverified(User.UserStatus status) {
        return User.builder().userId(userId).tenantId(tenantId).keycloakId(keycloakId).verified(false).status(status).build();
    }

    private User unverifiedUserIs(User.UserStatus status) {
        User user = unverified(status);
        when(users.findByKeycloakIdAndDeletedFalse(keycloakId)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void aLockedUnverifiedUser_logsOut_withoutBecomingVerified() throws Exception {
        User user = unverifiedUserIs(User.UserStatus.locked);
        signedIn();

        MockHttpServletResponse response = call("POST", "/api/v1/auth/logout");

        assertThat(response.getStatus()).as("signing out still works").isEqualTo(200);
        assertThat(reachedEnd).isTrue();
        assertThat(seenTenant.get()).as("the logout is still attributable: the context is bound").isEqualTo(tenantId);
        verify(userService, never()).markVerified(any());
        verify(users, never()).save(any());
        assertThat(user.isVerified()).isFalse();
        assertThat(user.getVerifiedAt()).isNull();
        assertThat(user.getStatus()).as("logging out does not unlock").isEqualTo(User.UserStatus.locked);
    }

    @Test
    void aDisabledUnverifiedUser_logsOut_withoutBecomingVerified() throws Exception {
        User user = unverifiedUserIs(User.UserStatus.disabled);
        signedIn();

        assertThat(call("POST", "/api/v1/auth/logout").getStatus()).isEqualTo(200);

        verify(userService, never()).markVerified(any());
        verify(users, never()).save(any());
        assertThat(user.isVerified()).isFalse();
        assertThat(user.getStatus()).isEqualTo(User.UserStatus.disabled);
    }

    @Test
    void anActiveUnverifiedUser_keepsTheExistingVerificationBehaviour_onAnyRequestIncludingLogout() throws Exception {
        unverifiedUserIs(User.UserStatus.active);
        signedIn();
        assertThat(call("GET", "/api/v1/documents").getStatus()).isEqualTo(200);
        verify(userService).markVerified(userId);

        signedIn();
        assertThat(call("POST", "/api/v1/auth/logout").getStatus()).isEqualTo(200);
        verify(userService, org.mockito.Mockito.times(2)).markVerified(userId);
    }

    @Test
    void anAlreadyVerifiedUser_isNeverWritten_whateverTheStatusOrPath() throws Exception {
        for (User.UserStatus status : User.UserStatus.values()) {
            localUserIs(status);   // verified = true
            signedIn();
            call("POST", "/api/v1/auth/logout");
            signedIn();
            call("GET", "/api/v1/documents");
        }

        verify(userService, never()).markVerified(any());
        verify(users, never()).save(any());
    }

    @Test
    void aLockedOrDisabledUser_isStillBlockedEverywhereElse_afterLoggingOut() throws Exception {
        for (User.UserStatus status : new User.UserStatus[] {User.UserStatus.locked, User.UserStatus.disabled}) {
            unverifiedUserIs(status);
            signedIn();
            assertThat(call("POST", "/api/v1/auth/logout").getStatus()).isEqualTo(200);

            reachedEnd = false;
            signedIn();
            MockHttpServletResponse after = call("GET", "/api/v1/documents");
            assertThat(after.getStatus()).isEqualTo(403);
            assertThat(after.getContentAsString()).contains("ACCOUNT_INACTIVE");
            assertThat(reachedEnd).isFalse();
            signedIn();
            assertThat(call("POST", "/api/v1/auth/logout/extra").getStatus()).as("only the exact logout path is open").isEqualTo(403);
        }
    }

    @Test
    void aSuspendedOrganisation_stillTakesPrecedence_andALockedUnverifiedUserIsStillNotWritten() throws Exception {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(Tenant.builder().tenantId(tenantId)
                .status(Tenant.TenantStatus.suspended).deleted(false).build()));
        unverifiedUserIs(User.UserStatus.locked);

        signedIn();
        MockHttpServletResponse protectedPath = call("GET", "/api/v1/documents");
        assertThat(protectedPath.getStatus()).isEqualTo(403);
        assertThat(protectedPath.getContentAsString()).contains(OrganisationStatusGuard.CODE).doesNotContain("ACCOUNT_INACTIVE");

        signedIn();
        assertThat(call("POST", "/api/v1/auth/logout").getStatus()).as("signing out is open for both refusals").isEqualTo(200);
        verify(userService, never()).markVerified(any());
        verify(users, never()).save(any());
    }
}
