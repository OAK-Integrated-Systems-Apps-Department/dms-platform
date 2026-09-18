package com.oak.dms.audit;

import com.oak.dms.security.rbac.PermissionController;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantController;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that a real denied request actually persists an
 * ACCESS_DENIED audit row, not just that the code compiles - and the
 * reason there are two audit call sites (see {@link com.oak.dms.audit.AccessDeniedAuditor})
 * instead of one, discovered by an earlier version of this test: a
 * Spring Security denial can surface from two different layers, only one
 * of which a {@code @RestControllerAdvice} can ever see.
 * <p>
 * MockMvc is built explicitly with {@code springSecurity()} rather than
 * relying on {@code @AutoConfigureMockMvc} to wire it automatically -
 * confirmed necessary on this Spring Boot 4.1 / spring-boot-webmvc-test
 * split: without it, mock authentication never reaches the filter chain
 * and every request comes back 401 (looks unauthenticated) instead of
 * 403 (authenticated, wrong role/permission).
 * <p>
 * Two endpoints, deliberately different in shape, both now covered:
 * <ul>
 * <li>{@link TenantController#listTenants} - {@code hasRole('PLATFORM_ADMIN')}
 * duplicated at BOTH the HTTP filter level (SecurityConfig's
 * {@code authorizeHttpRequests}) AND {@code @PreAuthorize}. The filter
 * level runs inside Spring Security's own filter chain, before
 * DispatcherServlet ever routes to the controller - a denial there never
 * reaches a {@code @RestControllerAdvice}, only
 * {@code AuditingAccessDeniedHandler} (registered directly on the
 * {@code SecurityFilterChain}). Only needs {@code @WithMockUser}: this
 * check reads authorities only, not TenantContext.</li>
 * <li>{@link PermissionController#getPermission} - {@code @authz.hasAnyPermission(...)}
 * only, no filter-level duplicate (custom bean logic can't be expressed
 * as a {@code requestMatchers(...)} rule) - a denial here really is
 * thrown from inside the DispatcherServlet's handler invocation, reaching
 * {@code GlobalExceptionHandler} instead. Needs a real {@code Jwt}
 * principal (not {@code @WithMockUser}, which TenantContextFilter can't
 * resolve a user from) backed by a real, permission-less {@link User}
 * row, since {@code AuthorizationService#effectivePermissions} is
 * DB-driven off {@code TenantContext}.</li>
 * </ul>
 */
@SpringBootTest
class AccessDeniedAuditIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void filterLevelDenialIsAudited() throws Exception {
        long before = countAccessDeniedEvents();

        mockMvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isForbidden());

        // SecurityConfig's requestMatchers(...) filter-level hasRole
        // check rejects this one before DispatcherServlet routes to
        // TenantController at all, so GlobalExceptionHandler never runs
        // for it - AuditingAccessDeniedHandler (registered directly on
        // the SecurityFilterChain's exceptionHandling(...)) is what
        // covers this path instead. Before that handler existed, this
        // assertion failed (after == before) - AccessDeniedAuditIT is
        // what surfaced the gap.
        long after = countAccessDeniedEvents();
        assertThat(after).isEqualTo(before + 1);

        AuditEvent latest = mostRecentAccessDenied();
        assertThat(latest.getObjectType()).isEqualTo(AuditObjectTypes.ENDPOINT);
        assertThat(latest.getOutcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(latest.getResourceName()).isEqualTo("GET /api/v1/tenants");
        assertThat(latest.getModule()).isEqualTo("ASM");
        assertThat(latest.getSeverity()).isEqualTo(AuditSeverity.MEDIUM);
    }

    @Test
    void preAuthorizeOnlyDenialIsAudited() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        UUID tenantId = createTenantAndPermissionlessUser(keycloakId);

        long before = countAccessDeniedEvents();

        String path = "/api/v1/permissions/" + UUID.randomUUID();
        mockMvc.perform(get(path).with(jwt()
                        .jwt(builder -> builder.subject(keycloakId.toString()).claim("tenant_id", tenantId.toString()))
                        .authorities()))
                .andExpect(status().isForbidden());

        long after = countAccessDeniedEvents();
        assertThat(after).isEqualTo(before + 1);

        AuditEvent latest = mostRecentAccessDenied();
        assertThat(latest.getObjectType()).isEqualTo(AuditObjectTypes.ENDPOINT);
        assertThat(latest.getOutcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(latest.getResourceName()).isEqualTo("GET " + path);
        assertThat(latest.getModule()).isEqualTo("ASM");
        assertThat(latest.getSeverity()).isEqualTo(AuditSeverity.MEDIUM);
        assertThat(latest.getTenantId()).isEqualTo(tenantId);
    }

    /** A real tenant + a real user with zero role/permission grants - no admin authorities, no ASM_ROLES_* codes. */
    private UUID createTenantAndPermissionlessUser(UUID keycloakId) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("ACCESS_DENIED test tenant " + keycloakId)
                .domain("access-denied-test-" + keycloakId + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@access-denied-test.invalid")
                .username(keycloakId.toString())
                .firstName("Access")
                .lastName("DeniedTest")
                .displayName("Access DeniedTest")
                .status(User.UserStatus.active)
                .build());

        return tenant.getTenantId();
    }

    private long countAccessDeniedEvents() {
        return auditEventRepository.findAll(byAction(AuditActionCatalog.ACCESS_DENIED)).size();
    }

    private AuditEvent mostRecentAccessDenied() {
        return auditEventRepository.findAll(byAction(AuditActionCatalog.ACCESS_DENIED)).stream()
                .max((a, b) -> a.getOccurredAt().compareTo(b.getOccurredAt()))
                .orElseThrow();
    }

    private static Specification<AuditEvent> byAction(String action) {
        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }
}
