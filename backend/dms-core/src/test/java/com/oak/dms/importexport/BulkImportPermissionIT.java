package com.oak.dms.importexport;

import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.entity.RolePermission;
import com.oak.dms.security.rbac.entity.UserRole;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the bulk import endpoints that start or feed a batch (create, upload
 * file, submit) need DOCM_DOCUMENTS_CREATE on top of FIE_BULK_IMPORT_UPLOAD_FILE,
 * while reading and cancelling stay FIE-only. Same setup as
 * {@link com.oak.dms.audit.AccessDeniedAuditIT}: a real DB user with a fabricated
 * keycloakId and a real {@code Jwt} principal, with permissions granted through
 * DB roles (as in {@code ImpersonationIT}), since {@code AuthorizationService}
 * resolves them off {@code TenantContext}.
 * <p>
 * "Allowed" means "not 403": the batch ids are random and the template is junk,
 * so the service rejects them - which only happens once the permission check
 * has passed.
 */
@SpringBootTest
class BulkImportPermissionIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createUploadAndSubmitAreDeniedWithoutDocumentCreate() throws Exception {
        User user = createUserWith(PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE);

        for (RequestBuilder request : batchWritingRequests(user)) {
            assertThat(statusOf(request)).isEqualTo(403);
        }
    }

    @Test
    void createUploadAndSubmitAreAllowedWithBothPermissions() throws Exception {
        User user = createUserWith(PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE, PermissionCodes.DOCM_DOCUMENTS_CREATE);

        for (RequestBuilder request : batchWritingRequests(user)) {
            assertThat(statusOf(request)).isNotEqualTo(403);
        }
    }

    @Test
    void activeAndCancelStayAvailableWithoutDocumentCreate() throws Exception {
        User user = createUserWith(PermissionCodes.FIE_BULK_IMPORT_UPLOAD_FILE);

        assertThat(statusOf(get("/api/v1/bulk-imports/active").with(jwtFor(user)))).isNotEqualTo(403);
        assertThat(statusOf(post("/api/v1/bulk-imports/" + UUID.randomUUID() + "/cancel").with(jwtFor(user))))
                .isNotEqualTo(403);
    }

    private List<RequestBuilder> batchWritingRequests(User user) {
        MockMultipartFile template = new MockMultipartFile("template", "template.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3});
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[] {1, 2, 3});
        return List.of(
                multipart("/api/v1/bulk-imports").file(template).with(jwtFor(user)),
                multipart("/api/v1/bulk-imports/" + UUID.randomUUID() + "/files").file(file).with(jwtFor(user)),
                post("/api/v1/bulk-imports/" + UUID.randomUUID() + "/submit").with(jwtFor(user)));
    }

    private int statusOf(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** A real tenant + user (fabricated keycloakId, no Keycloak account) granted exactly these permission codes via one throwaway role. */
    private User createUserWith(String... permissionCodes) {
        UUID keycloakId = UUID.randomUUID();
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Bulk import permission test tenant " + keycloakId)
                .domain("bulk-import-permission-test-" + keycloakId + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        UUID tenantId = tenant.getTenantId();

        User user = userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@bulk-import-permission-test.invalid")
                .username(keycloakId.toString())
                .firstName("BulkImport")
                .lastName("PermissionTest")
                .displayName("BulkImport PermissionTest")
                .status(User.UserStatus.active)
                .build());

        Role role = roleRepository.save(Role.builder()
                .tenantId(tenantId)
                .roleName("Test role " + UUID.randomUUID())
                .status(Role.RoleStatus.active)
                .build());
        for (String permissionCode : permissionCodes) {
            UUID permissionId = jdbcTemplate.queryForObject(
                    "SELECT permission_id FROM permissions WHERE permission_code = ? AND tenant_id IS NULL",
                    UUID.class, permissionCode);
            rolePermissionRepository.save(RolePermission.builder()
                    .tenantId(tenantId)
                    .roleId(role.getRoleId())
                    .permissionId(permissionId)
                    .build());
        }
        userRoleRepository.save(UserRole.builder()
                .tenantId(tenantId)
                .userId(user.getUserId())
                .roleId(role.getRoleId())
                .build());
        return user;
    }

    /** A real Jwt principal for the user, with no coarse Keycloak authorities. */
    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(User user) {
        return jwt().jwt(builder -> builder.subject(user.getKeycloakId().toString())
                        .claim("tenant_id", user.getTenantId().toString()))
                .authorities();
    }
}
