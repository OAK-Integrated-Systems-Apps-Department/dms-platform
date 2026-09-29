package com.oak.dms.security.auth;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.entity.AuditEvent;
import com.oak.dms.audit.repository.AuditEventRepository;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.entity.RolePermission;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.entity.UserRole;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the "Act as User" impersonation feature against a
 * real database - same reasoning as {@link com.oak.dms.audit.AccessDeniedAuditIT}
 * (a real, permission-less DB user + a real JWT via
 * {@code SecurityMockMvcRequestPostProcessors.jwt()}, not {@code @WithMockUser},
 * since {@link TenantContextFilter} needs a real {@code Jwt} principal to
 * resolve a user from).
 * <p>
 * Coarse (TENANT_ADMIN-style) authority checks during impersonation hit a
 * live Keycloak Admin API call for the target's platform roles (see
 * {@link AuthorizationService#hasFullAccess}) - deliberately NOT exercised
 * here, since none of these fixtures have real Keycloak accounts (same
 * fabricated-keycloakId pattern as {@code AccessDeniedAuditIT}). Every test
 * below instead uses fine-grained, DB-driven permissions
 * ({@link RolePermissionRepository}/{@link UserRoleRepository}), which is
 * also the more common real-world case this feature exists for.
 */
@SpringBootTest
class ImpersonationIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AuditEventRepository auditEventRepository;
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
    @Autowired
    private KeycloakUserService keycloakUserService;

    private MockMvc mockMvc;
    /**
     * Real Keycloak accounts created by {@link #createUser}, cleaned up
     * after each test. Unlike {@code AccessDeniedAuditIT}'s fabricated-
     * keycloakId fixtures, these need to be real: {@code UserServiceImpl}'s
     * pre-existing {@code requireVisibleToCaller}/{@code isTenantAdminAccount}
     * check calls Keycloak's Admin API for the CURRENT caller's own account
     * on every profile lookup (not just an impersonation target's), so a
     * fabricated id 404s there regardless of anything impersonation-related.
     */
    private final List<UUID> createdKeycloakIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        for (UUID keycloakId : createdKeycloakIds) {
            keycloakUserService.deleteUser(keycloakId);
        }
        createdKeycloakIds.clear();
    }

    @Test
    void normalUserActivity_isNeverAttributedToAnImpersonator() throws Exception {
        UUID tenantId = createTenant();
        User target = createUser(tenantId, "Impersonation Target");

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwtFor(target))
                        .contentType("application/json")
                        .content("{\"firstName\":\"Renamed\",\"lastName\":\"" + target.getLastName() + "\"}"))
                .andExpect(status().isOk());

        AuditEvent event = latest(AuditActionCatalog.USER_UPDATED);
        assertThat(event.getActorId()).isEqualTo(target.getUserId());
        assertThat(event.getImpersonatedBy()).isNull();
    }

    @Test
    void adminActivity_withoutImpersonating_isAlsoNeverAttributedToAnImpersonator() throws Exception {
        UUID tenantId = createTenant();
        User admin = createUser(tenantId, "Real Admin");
        User target = createUser(tenantId, "Some User");

        mockMvc.perform(post("/api/v1/users/" + target.getUserId() + "/reset-password")
                        .with(tenantAdminJwtFor(admin)))
                .andExpect(status().isAccepted());

        AuditEvent event = latest(AuditActionCatalog.PASSWORD_RESET_REQUESTED);
        assertThat(event.getActorId()).isEqualTo(admin.getUserId());
        assertThat(event.getImpersonatedBy()).isNull();
    }

    @Test
    void adminImpersonatingTarget_meReflectsTargetWithAdminRecordedAsImpersonator() throws Exception {
        UUID tenantId = createTenant();
        User admin = createUser(tenantId, "Real Admin");
        User target = createUser(tenantId, "Impersonation Target");

        Cookie grant = startImpersonation(admin, target);

        mockMvc.perform(get("/api/v1/users/me").with(tenantAdminJwtFor(admin)).cookie(grant))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.userId").value(target.getUserId().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.impersonating").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.impersonatorDisplayName").value(admin.getDisplayName()));
    }

    @Test
    void actionsDuringImpersonation_recordTheOriginalAdminAsImpersonatedBy() throws Exception {
        UUID tenantId = createTenant();
        User admin = createUser(tenantId, "Real Admin");
        User target = createUser(tenantId, "Impersonation Target");
        Cookie grant = startImpersonation(admin, target);

        mockMvc.perform(put("/api/v1/users/me")
                        .with(tenantAdminJwtFor(admin))
                        .cookie(grant)
                        .contentType("application/json")
                        .content("{\"firstName\":\"Renamed\",\"lastName\":\"" + target.getLastName() + "\"}"))
                .andExpect(status().isOk());

        AuditEvent event = latest(AuditActionCatalog.USER_UPDATED);
        assertThat(event.getActorId()).isEqualTo(target.getUserId());
        assertThat(event.getImpersonatedBy()).isEqualTo(admin.getUserId());
    }

    @Test
    void authorizationUsesTargetsCurrentPermissions_andRevocationTakesEffectOnTheNextRequest() throws Exception {
        UUID tenantId = createTenant();
        User admin = createUser(tenantId, "Real Admin");
        User target = createUser(tenantId, "Impersonation Target");
        Cookie grant = startImpersonation(admin, target);

        // Target has no role_permissions of its own yet - the fine-grained
        // check must deny, even though the REAL admin is a full-access
        // TENANT_ADMIN. Proves hasFullAccess() isn't leaking the admin's
        // own authority into the impersonated request.
        mockMvc.perform(get("/api/v1/audit-events").with(tenantAdminJwtFor(admin)).cookie(grant))
                .andExpect(status().isForbidden());

        // Grant it directly to the target mid-session.
        UUID userRoleId = grantPermission(tenantId, target.getUserId(), PermissionCodes.ABDR_VIEW_AUDIT_HISTORY);

        mockMvc.perform(get("/api/v1/audit-events").with(tenantAdminJwtFor(admin)).cookie(grant))
                .andExpect(status().isOk());

        // Revoke it again, same session, no new grant issued - the very
        // next request must reflect the revocation immediately.
        userRoleRepository.deleteById(userRoleId);

        mockMvc.perform(get("/api/v1/audit-events").with(tenantAdminJwtFor(admin)).cookie(grant))
                .andExpect(status().isForbidden());
    }

    @Test
    void sameTenantEnforcement_rejectsACrossTenantTarget() throws Exception {
        UUID tenantAId = createTenant();
        UUID tenantBId = createTenant();
        User admin = createUser(tenantAId, "Real Admin");
        User foreignUser = createUser(tenantBId, "Someone Else's User");

        mockMvc.perform(post("/api/v1/impersonation/start")
                        .with(tenantAdminJwtFor(admin))
                        .contentType("application/json")
                        .content("{\"targetUserId\":\"" + foreignUser.getUserId() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void inactiveTarget_isRejected() throws Exception {
        UUID tenantId = createTenant();
        User admin = createUser(tenantId, "Real Admin");
        User inactiveTarget = createUser(tenantId, "Disabled User");
        inactiveTarget.setStatus(User.UserStatus.disabled);
        userRepository.save(inactiveTarget);

        mockMvc.perform(post("/api/v1/impersonation/start")
                        .with(tenantAdminJwtFor(admin))
                        .contentType("application/json")
                        .content("{\"targetUserId\":\"" + inactiveTarget.getUserId() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void adminWithoutThePermission_cannotImpersonate() throws Exception {
        UUID tenantId = createTenant();
        User plainAdmin = createUser(tenantId, "No Permission Admin");
        User target = createUser(tenantId, "Impersonation Target");

        mockMvc.perform(post("/api/v1/impersonation/start")
                        .with(jwtFor(plainAdmin))
                        .contentType("application/json")
                        .content("{\"targetUserId\":\"" + target.getUserId() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nestedImpersonation_isRejected() throws Exception {
        UUID tenantId = createTenant();
        User admin = createUser(tenantId, "Real Admin");
        User target1 = createUser(tenantId, "First Target");
        User target2 = createUser(tenantId, "Second Target");
        // Grant target1 the impersonate permission too, so the nested
        // attempt reaches ImpersonationService#start's own explicit
        // "already impersonating" guard rather than incidentally failing
        // its @PreAuthorize check for an unrelated reason.
        grantPermission(tenantId, target1.getUserId(), PermissionCodes.OTUA_USERS_IMPERSONATE);

        Cookie grant = startImpersonation(admin, target1);

        mockMvc.perform(post("/api/v1/impersonation/start")
                        .with(tenantAdminJwtFor(admin))
                        .cookie(grant)
                        .contentType("application/json")
                        .content("{\"targetUserId\":\"" + target2.getUserId() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void startAndStop_bothProduceTheirOwnAuditEvents() throws Exception {
        UUID tenantId = createTenant();
        User admin = createUser(tenantId, "Real Admin");
        User target = createUser(tenantId, "Impersonation Target");

        Cookie grant = startImpersonation(admin, target);
        AuditEvent started = latest(AuditActionCatalog.IMPERSONATION_STARTED);
        assertThat(started.getActorId()).isEqualTo(admin.getUserId());
        assertThat(started.getImpersonatedBy()).isNull();
        assertThat(started.getObjectId()).isEqualTo(target.getUserId());

        MvcResult stopResult = mockMvc.perform(post("/api/v1/impersonation/stop")
                        .with(tenantAdminJwtFor(admin))
                        .cookie(grant))
                .andExpect(status().isNoContent())
                .andReturn();

        AuditEvent ended = latest(AuditActionCatalog.IMPERSONATION_ENDED);
        assertThat(ended.getActorId()).isEqualTo(target.getUserId());
        assertThat(ended.getImpersonatedBy()).isEqualTo(admin.getUserId());
        // The whole point of this snapshot column: the audit log must be
        // readable without a live join back to a users row that may since
        // have been renamed or deleted.
        assertThat(ended.getImpersonatedByDisplayName()).isEqualTo(admin.getDisplayName());

        // The response clears the cookie (Max-Age=0) - the browser stops
        // sending it on any later request, restoring the admin's own
        // identity from then on with no separate "restore" step needed.
        Cookie cleared = stopResult.getResponse().getCookie(AuthCookieService.IMPERSONATION_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isEqualTo(0);
    }

    @Test
    void stoppingWithoutAnActiveSession_isRejected() throws Exception {
        UUID tenantId = createTenant();
        User admin = createUser(tenantId, "Real Admin");

        mockMvc.perform(post("/api/v1/impersonation/stop").with(tenantAdminJwtFor(admin)))
                .andExpect(status().isConflict());
    }

    private Cookie startImpersonation(User admin, User target) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/impersonation/start")
                        .with(tenantAdminJwtFor(admin))
                        .contentType("application/json")
                        .content("{\"targetUserId\":\"" + target.getUserId() + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(AuthCookieService.IMPERSONATION_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private UUID createTenant() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Impersonation test tenant " + UUID.randomUUID())
                .domain("impersonation-test-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        return tenant.getTenantId();
    }

    private User createUser(UUID tenantId, String displayName) {
        String username = "impersonation-test-" + UUID.randomUUID();
        String email = username + "@impersonation-test.invalid";
        UUID keycloakId = keycloakUserService.createUser(username, email, displayName, "Test", tenantId);
        createdKeycloakIds.add(keycloakId);
        return userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(email)
                .username(username)
                .firstName(displayName)
                .lastName("Test")
                .displayName(displayName)
                .status(User.UserStatus.active)
                .build());
    }

    /** Grants a single permission code directly to a user via a fresh throwaway role. Returns the user_role row's id, for the revocation test. */
    private UUID grantPermission(UUID tenantId, UUID userId, String permissionCode) {
        Role role = roleRepository.save(Role.builder()
                .tenantId(tenantId)
                .roleName("Test role " + UUID.randomUUID())
                .status(Role.RoleStatus.active)
                .build());

        UUID permissionId = jdbcTemplate.queryForObject(
                "SELECT permission_id FROM permissions WHERE permission_code = ? AND tenant_id IS NULL",
                UUID.class, permissionCode);

        rolePermissionRepository.save(RolePermission.builder()
                .tenantId(tenantId)
                .roleId(role.getRoleId())
                .permissionId(permissionId)
                .build());

        UserRole userRole = userRoleRepository.save(UserRole.builder()
                .tenantId(tenantId)
                .userId(userId)
                .roleId(role.getRoleId())
                .build());
        return userRole.getUserRoleId();
    }

    private AuditEvent latest(String action) {
        return auditEventRepository.findAll(byAction(action)).stream()
                .max((a, b) -> a.getOccurredAt().compareTo(b.getOccurredAt()))
                .orElseThrow();
    }

    private static Specification<AuditEvent> byAction(String action) {
        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }

    /** A real, permission-less local user, matching against a real Jwt principal - no coarse Keycloak authorities. */
    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(User user) {
        return jwt().jwt(builder -> builder.subject(user.getKeycloakId().toString())
                        .claim("tenant_id", user.getTenantId().toString()))
                .authorities();
    }

    /** Same real Jwt principal, but with the ROLE_TENANT_ADMIN authority JwtAuthConverter would have derived from a real Keycloak realm_access.roles claim - full access via AuthorizationService#hasFullAccess. */
    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor tenantAdminJwtFor(User user) {
        return jwt().jwt(builder -> builder.subject(user.getKeycloakId().toString())
                        .claim("tenant_id", user.getTenantId().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));
    }
}
