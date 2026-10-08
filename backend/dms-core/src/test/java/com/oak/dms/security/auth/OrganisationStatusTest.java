package com.oak.dms.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.user.UserService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A suspended or closed organisation: its users' sessions stop working on the next request. */
class OrganisationStatusTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID keycloakId = UUID.randomUUID();
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OrganisationStatusGuard guard = new OrganisationStatusGuard(tenants);
    private final TenantContextFilter filter = new TenantContextFilter(users, mock(UserService.class), guard,
            new ObjectMapper());
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void tenant(Tenant.TenantStatus status, boolean deleted) {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(Tenant.builder().tenantId(tenantId)
                .status(status).deleted(deleted).build()));
    }

    private void signedIn(String... roles) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(keycloakId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
        when(users.findByKeycloakIdAndDeletedFalse(keycloakId)).thenReturn(Optional.of(User.builder()
                .userId(UUID.randomUUID()).tenantId(tenantId).keycloakId(keycloakId).verified(true).build()));
    }

    private MockHttpServletResponse request(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);
        return response;
    }

    @Test
    void anActiveOrganisation_goesThrough() throws Exception {
        tenant(Tenant.TenantStatus.active, false);
        signedIn();
        assertThat(request("/api/v1/documents").getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void aSuspendedOrClosedOrganisation_isRefusedWithItsReason() throws Exception {
        tenant(Tenant.TenantStatus.suspended, false);
        signedIn();
        MockHttpServletResponse response = request("/api/v1/documents");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORGANISATION_INACTIVE").contains("suspended");
        verify(chain, never()).doFilter(any(), any());

        tenant(Tenant.TenantStatus.active, true); // deactivated (deleted)
        assertThat(request("/api/v1/documents").getContentAsString()).contains("closed");
    }

    @Test
    void signingOut_andPlatformAdmins_stillGetThrough() throws Exception {
        tenant(Tenant.TenantStatus.archived, true);
        signedIn();
        assertThat(request("/api/v1/auth/logout").getStatus()).isEqualTo(200);
        signedIn("ROLE_PLATFORM_ADMIN");
        assertThat(request("/api/v1/tenants").getStatus()).isEqualTo(200);
    }

    @Test
    void theRoleInAFreshKeycloakToken_isRead() {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"realm_access\":{\"roles\":[\"MEMBER\",\"platform_admin\"]}}".getBytes());
        assertThat(AuthService.hasRealmRole("h." + payload + ".s", "PLATFORM_ADMIN")).isTrue();
        assertThat(AuthService.hasRealmRole("h." + payload + ".s", "TENANT_ADMIN")).isFalse();
        assertThat(AuthService.hasRealmRole("garbage", "PLATFORM_ADMIN")).isFalse();
    }
}
