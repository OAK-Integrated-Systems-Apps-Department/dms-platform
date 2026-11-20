package com.oak.dms.security.rbac;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.dto.GrantPermissionRequest;
import com.oak.dms.security.rbac.entity.Permission;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.entity.RolePermission;
import com.oak.dms.security.rbac.mapper.RolePermissionMapper;
import com.oak.dms.security.rbac.repository.PermissionRepository;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.service.impl.RolePermissionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRG default (V139): a role granted {@code docm.documents.view} also gets {@code crg.generate-custom-reports.run},
 * so the grant is not a one-off backfill. Everything else about granting stays exactly as before.
 */
class RolePermissionDefaultReportRunGrantTest {

    private static final String VIEW = "docm.documents.view";
    private static final String RUN = "crg.generate-custom-reports.run";

    private final UUID tenantId = UUID.randomUUID();
    private final UUID roleId = UUID.randomUUID();
    private final UUID viewId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    private RolePermissionRepository grants;
    private PermissionRepository permissions;
    private RolePermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        grants = mock(RolePermissionRepository.class);
        permissions = mock(PermissionRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        Role role = Role.builder().roleId(roleId).tenantId(tenantId).roleName("Reader").build();
        when(roles.findByRoleIdAndTenantIdAndDeletedFalse(roleId, tenantId)).thenReturn(Optional.of(role));
        when(grants.save(any(RolePermission.class))).thenAnswer(i -> {
            RolePermission g = i.getArgument(0);
            g.setRolePermissionId(UUID.randomUUID());
            return g;
        });
        when(permissions.findVisibleToTenant(viewId, tenantId)).thenReturn(Optional.of(permission(viewId, VIEW)));
        when(permissions.findVisibleToTenant(otherId, tenantId)).thenReturn(Optional.of(permission(otherId, "docm.documents.upload")));
        when(permissions.findByPermissionCodeAndTenantIdIsNullAndDeletedFalse(RUN)).thenReturn(Optional.of(permission(runId, RUN)));
        service = new RolePermissionServiceImpl(grants, roles, permissions, mock(RolePermissionMapper.class),
                mock(AuditService.class, RETURNS_DEEP_STUBS));
        TenantContext.set(tenantId, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Permission permission(UUID id, String code) {
        return Permission.builder().permissionId(id).permissionCode(code).name(code).build();
    }

    private List<RolePermission> saved() {
        ArgumentCaptor<RolePermission> captor = ArgumentCaptor.forClass(RolePermission.class);
        verify(grants, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void grantingDocumentView_alsoGrantsTheReportRun_withTheSameScope() {
        service.grantPermission(roleId, new GrantPermissionRequest(viewId, "FOLDER:abc"));

        List<RolePermission> saved = saved();
        assertThat(saved).extracting(RolePermission::getPermissionId).containsExactly(viewId, runId);
        assertThat(saved).extracting(RolePermission::getResourceScope).containsOnly("FOLDER:abc");
        assertThat(saved).extracting(RolePermission::getRoleId).containsOnly(roleId);
        assertThat(saved).extracting(RolePermission::getTenantId).containsOnly(tenantId);
    }

    @Test
    void aRoleThatAlreadyHasTheReportRun_isNotGivenItTwice() {
        when(grants.existsByRoleIdAndPermissionIdAndResourceScope(roleId, runId, null)).thenReturn(true);

        service.grantPermission(roleId, new GrantPermissionRequest(viewId, null));

        assertThat(saved()).extracting(RolePermission::getPermissionId).containsExactly(viewId);
    }

    @Test
    void grantingAnyOtherPermission_behavesExactlyAsBefore() {
        service.grantPermission(roleId, new GrantPermissionRequest(otherId, null));

        assertThat(saved()).extracting(RolePermission::getPermissionId).containsExactly(otherId);
        verify(permissions, never()).findByPermissionCodeAndTenantIdIsNullAndDeletedFalse(anyString());
    }

    @Test
    void ifTheReportRunPermissionIsNotSeeded_theViewGrantStillSucceeds() {
        when(permissions.findByPermissionCodeAndTenantIdIsNullAndDeletedFalse(RUN)).thenReturn(Optional.empty());

        service.grantPermission(roleId, new GrantPermissionRequest(viewId, null));

        assertThat(saved()).extracting(RolePermission::getPermissionId).containsExactly(viewId);
        verify(grants, times(1)).save(any());
    }
}
