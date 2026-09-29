package com.oak.dms.security.auth;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.repository.PermissionRepository;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthorizationService#myRoleNames()} - what My Profile's "Role(s)"
 * shows: platform roles from the caller's token, then applicable tenant roles.
 */
class AuthorizationServiceRoleNamesTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private UserRoleRepository userRoleRepository;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        userRoleRepository = mock(UserRoleRepository.class);
        service = new AuthorizationService(mock(RolePermissionRepository.class), mock(PermissionRepository.class),
                userRoleRepository, mock(KeycloakUserService.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void platformRolesFirst_thenTenantRoles_ignoringNonPlatformAuthorities() {
        authenticateWith("ROLE_TENANT_ADMIN", "ROLE_offline_access", "SCOPE_profile");
        TenantContext.set(tenantId, userId);
        when(userRoleRepository.findActiveRoleNames(userId, tenantId))
                .thenReturn(List.of("Compliance Officer", "Records Clerk"));

        assertThat(service.myRoleNames())
                .containsExactly("TENANT_ADMIN", "Compliance Officer", "Records Clerk");
    }

    @Test
    void userWithNoTenantRoles_stillShowsPlatformRole() {
        authenticateWith("ROLE_MEMBER");
        TenantContext.set(tenantId, userId);
        when(userRoleRepository.findActiveRoleNames(userId, tenantId)).thenReturn(List.of());

        assertThat(service.myRoleNames()).containsExactly("MEMBER");
    }

    @Test
    void tenantRolesAreQueriedForTheCallerOnly() {
        authenticateWith("ROLE_PLATFORM_ADMIN");
        TenantContext.set(tenantId, userId);
        when(userRoleRepository.findActiveRoleNames(userId, tenantId)).thenReturn(List.of());

        assertThat(service.myRoleNames()).containsExactly("PLATFORM_ADMIN");
        verify(userRoleRepository).findActiveRoleNames(userId, tenantId);
    }

    @Test
    void withoutABoundUser_failsLoudlyLikeTheOtherMyMethods() {
        authenticateWith("ROLE_MEMBER");

        assertThatThrownBy(service::myRoleNames).isInstanceOf(IllegalStateException.class);
        verify(userRoleRepository, never()).findActiveRoleNames(any(), any());
    }

    private static void authenticateWith(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, authorities));
    }
}
