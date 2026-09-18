package com.oak.dms.security.rbac;

import com.oak.dms.audit.AuditActionCatalog;
import com.oak.dms.audit.AuditEvent;
import com.oak.dms.audit.AuditEventRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.dto.CreatePermissionRequest;
import com.oak.dms.security.rbac.dto.CreateRoleRequest;
import com.oak.dms.security.rbac.dto.GrantPermissionRequest;
import com.oak.dms.security.rbac.dto.PermissionResponse;
import com.oak.dms.security.rbac.dto.RolePermissionResponse;
import com.oak.dms.security.rbac.dto.RoleResponse;
import com.oak.dms.security.rbac.dto.UpdatePermissionRequest;
import com.oak.dms.security.rbac.dto.UpdateRoleRequest;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the audit trail's permission_code column reflects the real,
 * DB-resolved {@link Permission} a call actually acted on - never a value
 * hardcoded off the audit action name, and never something a request body
 * could forge: {@link GrantPermissionRequest} carries a permissionId
 * (resolved server-side through {@link PermissionRepository}), and
 * {@link UpdatePermissionRequest} carries no permission-code field at all
 * (see that DTO's own doc comment on why the code is immutable) - neither
 * DTO has any way to inject an arbitrary permission_code string into the
 * audit row. Also proves the column stays null for RBAC events that aren't
 * about one specific permission - creating/renaming/deleting a {@link Role}
 * grants no single permission by itself.
 */
@SpringBootTest
class PermissionCodeAuditIT {

    @Autowired
    private PermissionService permissionService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private RolePermissionService rolePermissionService;
    @Autowired
    private PermissionModuleRepository permissionModuleRepository;
    @Autowired
    private PermissionActionRepository permissionActionRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void setUp() {
        UUID tenantId = createTenant();
        // A real, locally-persisted User row (fabricated Keycloak id, same
        // as AccessDeniedAuditIT's pattern) - permissions.created_by and
        // roles.created_by both have a real FK to users, so TenantContext's
        // userId must resolve to an actual row. Neither PermissionService
        // nor RolePermissionService ever calls Keycloak for the CURRENT
        // caller, so no real Keycloak account is needed here (unlike
        // ImpersonationIT, which needs one for an unrelated pre-existing
        // Keycloak check elsewhere).
        UUID keycloakId = UUID.randomUUID();
        User admin = userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@permission-code-test.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Admin")
                .displayName("Test Admin")
                .status(User.UserStatus.active)
                .build());
        TenantContext.set(tenantId, admin.getUserId(), admin.getDisplayName(), keycloakId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void grantingAndRevokingARolePermission_recordTheActualPermissionCode() {
        UUID actionId = createCatalogAction();
        String code = "test.permission." + UUID.randomUUID();
        PermissionResponse permission = permissionService.createPermission(
                new CreatePermissionRequest(code, "Test Permission", null, actionId));
        assertThat(latest(AuditActionCatalog.PERMISSION_CREATED).getPermissionCode()).isEqualTo(code);

        RoleResponse role = createTestRole();

        RolePermissionResponse grant = rolePermissionService.grantPermission(
                role.roleId(), new GrantPermissionRequest(permission.permissionId(), null));
        AuditEvent assigned = latest(AuditActionCatalog.ROLE_PERMISSION_ASSIGNED);
        assertThat(assigned.getPermissionCode()).isEqualTo(code);
        assertThat(assigned.getObjectId()).isEqualTo(grant.rolePermissionId());

        rolePermissionService.revokePermission(role.roleId(), grant.rolePermissionId());
        AuditEvent revoked = latest(AuditActionCatalog.ROLE_PERMISSION_REVOKED);
        assertThat(revoked.getPermissionCode()).isEqualTo(code);
        assertThat(revoked.getObjectId()).isEqualTo(grant.rolePermissionId());
    }

    @Test
    void updatingAndDeletingAPermission_keepItsOwnUnchangeableCode() {
        UUID actionId = createCatalogAction();
        String code = "test.permission." + UUID.randomUUID();
        PermissionResponse permission = permissionService.createPermission(
                new CreatePermissionRequest(code, "Original Name", null, actionId));

        permissionService.updatePermission(permission.permissionId(),
                new UpdatePermissionRequest("Renamed", "Now with a description", actionId));
        assertThat(latest(AuditActionCatalog.PERMISSION_UPDATED).getPermissionCode()).isEqualTo(code);

        permissionService.deletePermission(permission.permissionId());
        assertThat(latest(AuditActionCatalog.PERMISSION_DELETED).getPermissionCode()).isEqualTo(code);
    }

    @Test
    void grantingTwoDifferentPermissions_eachAuditRowKeepsItsOwnDistinctCode() {
        UUID actionId = createCatalogAction();
        String codeA = "test.permission.a." + UUID.randomUUID();
        String codeB = "test.permission.b." + UUID.randomUUID();
        PermissionResponse a = permissionService.createPermission(new CreatePermissionRequest(codeA, "A", null, actionId));
        PermissionResponse b = permissionService.createPermission(new CreatePermissionRequest(codeB, "B", null, actionId));
        RoleResponse role = createTestRole();

        rolePermissionService.grantPermission(role.roleId(), new GrantPermissionRequest(a.permissionId(), null));
        assertThat(latest(AuditActionCatalog.ROLE_PERMISSION_ASSIGNED).getPermissionCode()).isEqualTo(codeA);

        rolePermissionService.grantPermission(role.roleId(), new GrantPermissionRequest(b.permissionId(), null));
        assertThat(latest(AuditActionCatalog.ROLE_PERMISSION_ASSIGNED).getPermissionCode()).isEqualTo(codeB);
    }

    @Test
    void roleManagementEvents_haveNoPermissionCode() {
        RoleResponse role = createTestRole();
        assertThat(latest(AuditActionCatalog.ROLE_CREATED).getPermissionCode()).isNull();

        roleService.updateRole(role.roleId(),
                new UpdateRoleRequest(role.roleName(), "updated description", UpdateRoleRequest.Status.active));
        assertThat(latest(AuditActionCatalog.ROLE_UPDATED).getPermissionCode()).isNull();

        roleService.deleteRole(role.roleId());
        assertThat(latest(AuditActionCatalog.ROLE_DELETED).getPermissionCode()).isNull();
    }

    private RoleResponse createTestRole() {
        return roleService.createRole(new CreateRoleRequest("Test Role " + UUID.randomUUID(), null, null));
    }

    private UUID createCatalogAction() {
        PermissionModule module = permissionModuleRepository.save(PermissionModule.builder()
                .moduleCode("TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .moduleName("Test Module")
                .build());
        PermissionAction action = permissionActionRepository.save(PermissionAction.builder()
                .moduleId(module.getModuleId())
                .actionCode("TEST_ACTION")
                .actionName("Test Action")
                .build());
        return action.getActionId();
    }

    private UUID createTenant() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Permission code test tenant " + UUID.randomUUID())
                .domain("permission-code-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        return tenant.getTenantId();
    }

    private AuditEvent latest(String action) {
        return auditEventRepository.findAll(byAction(action)).stream()
                .max((x, y) -> x.getOccurredAt().compareTo(y.getOccurredAt()))
                .orElseThrow();
    }

    private static Specification<AuditEvent> byAction(String action) {
        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }
}
