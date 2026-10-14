package com.oak.dms.security.auth;

import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.entity.Permission;
import com.oak.dms.security.rbac.repository.PermissionRepository;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who passes the system-monitoring checks: platform admin, tenant admin, or a role carrying the code. */
class AuthorizationServiceGrantedPermissionTest {

    private static final String HEALTH = PermissionCodes.SHAD_VIEW_SYSTEM_HEALTH;
    private static final String LOGS = PermissionCodes.SHAD_VIEW_SYSTEM_LOGS;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private RolePermissionRepository rolePermissions;
    private PermissionRepository permissions;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        rolePermissions = mock(RolePermissionRepository.class);
        permissions = mock(PermissionRepository.class);
        service = new AuthorizationService(rolePermissions, permissions, mock(UserRoleRepository.class),
                mock(KeycloakUserService.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void platformAdmin_passesWithoutATenantOrARole() {
        authenticateWith("ROLE_PLATFORM_ADMIN");

        assertThat(service.hasPermission(HEALTH)).isTrue();
        assertThat(service.hasPermission(LOGS)).isTrue();
    }

    @Test
    void tenantAdmin_passesWithoutARole() {
        authenticateWith("ROLE_TENANT_ADMIN");
        TenantContext.set(tenantId, userId);

        assertThat(service.hasPermission(HEALTH)).isTrue();
        assertThat(service.hasPermission(LOGS)).isTrue();
    }

    @Test
    void tenantUser_passesOnlyWhenARoleCarriesIt() {
        authenticateWith("ROLE_MEMBER");
        TenantContext.set(tenantId, userId);
        when(rolePermissions.findEffectivePermissionCodes(userId, tenantId)).thenReturn(List.of(HEALTH));

        assertThat(service.hasPermission(HEALTH)).isTrue();
        assertThat(service.hasPermission(LOGS)).isFalse();
    }

    @Test
    void tenantAdminsPermissionList_includesMonitoring() {
        authenticateWith("ROLE_TENANT_ADMIN");
        TenantContext.set(tenantId, userId);
        when(permissions.findAllVisibleToTenant(any(UUID.class), any(Pageable.class)))
                .thenAnswer(i -> catalogue(PermissionCodes.OTUA_USERS_VIEW, HEALTH, LOGS));

        assertThat(service.myEffectivePermissionCodes())
                .containsExactly(PermissionCodes.OTUA_USERS_VIEW, HEALTH, LOGS);
    }

    private static Page<Permission> catalogue(String... codes) {
        List<Permission> all = java.util.Arrays.stream(codes).map(code -> {
            Permission p = new Permission();
            p.setPermissionCode(code);
            return p;
        }).toList();
        return new PageImpl<>(all);
    }

    private static void authenticateWith(String... authorities) {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user", "n/a", authorities));
    }
}
