package com.oak.dms.watermark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.security.auth.TenantContext;
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
import com.oak.dms.watermark.dto.WatermarkTemplateRequest;
import com.oak.dms.watermark.repository.WatermarkTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code @PreAuthorize} rules on the template endpoints, exercised
 * through the real filter chain with a real JWT rather than by calling the
 * service directly - because the service has no idea these rules exist, and
 * an annotation that silently stops matching a renamed constant would never
 * show up in a service-level test.
 * <p>
 * Three callers, three outcomes: a signed-in user with nothing granted, the
 * same user once a role carrying the {@code dw.configure-watermark-policy.*}
 * code is assigned, and a TENANT_ADMIN - who bypasses every fine-grained
 * check via {@code AuthorizationService#hasFullAccess}, and so is the one
 * caller most likely to slip past a rule written as a permission. That is
 * precisely why "a system template is read-only" is NOT written as one, and
 * the last test here is what proves the distinction holds.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkTemplateAuthorizationIT {

    private static final String PATH = "/api/v1/watermark-templates";
    private static final UUID SYSTEM_CONFIDENTIAL = UUID.fromString("d0cc0000-0000-4000-8000-000000000001");
    private static final String TENANT_ADMIN = "ROLE_TENANT_ADMIN";

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
    private WatermarkTemplateRepository templateRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void withoutTheCreateCode_creatingATemplateIsForbidden() throws Exception {
        Caller caller = caller();
        mockMvc.perform(as(caller, post(PATH)).content(body("Denied")))
                .andExpect(status().isForbidden());
        assertThat(templateRepository.findVisibleForTenant(caller.tenantId(), true))
                .extracting(t -> t.getLabel()).doesNotContain("Denied");
    }

    @Test
    void withTheCreateCode_creatingATemplateSucceeds() throws Exception {
        Caller caller = caller();
        grant(caller, PermissionCodes.DW_WATERMARK_TEMPLATE_CREATE);

        mockMvc.perform(as(caller, post(PATH)).content(body("Allowed")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Allowed"))
                .andExpect(jsonPath("$.systemTemplate").value(false))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void theCreateCodeAloneDoesNotAllowEditingOrDeleting() throws Exception {
        // The two codes are separate rows in V10's catalogue, so holding one
        // must not quietly confer the other.
        Caller caller = caller();
        grant(caller, PermissionCodes.DW_WATERMARK_TEMPLATE_CREATE);
        UUID templateId = createAs(caller, "Mine to make");

        mockMvc.perform(as(caller, put(PATH + "/" + templateId)).content(body("Renamed")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(caller, delete(PATH + "/" + templateId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theUpdateCodeAllowsBothEditingAndDeleting() throws Exception {
        // Deleting reuses the update code - V10 has no delete-template
        // permission. If that ever changes, this test is where it shows.
        Caller caller = caller();
        grant(caller, PermissionCodes.DW_WATERMARK_TEMPLATE_CREATE);
        grant(caller, PermissionCodes.DW_WATERMARK_TEMPLATE_UPDATE);
        UUID templateId = createAs(caller, "Editable");

        mockMvc.perform(as(caller, put(PATH + "/" + templateId)).content(body("Renamed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Renamed"));
        mockMvc.perform(as(caller, delete(PATH + "/" + templateId)))
                .andExpect(status().isNoContent());
    }

    @Test
    void anyoneWhoCanViewDocumentsCanListTemplates_becausePickingOneIsPartOfWatermarking() throws Exception {
        Caller caller = caller();
        grant(caller, PermissionCodes.DOCM_DOCUMENTS_VIEW);

        mockMvc.perform(as(caller, get(PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label == 'CONFIDENTIAL')]").exists());
    }

    @Test
    void withNoRelevantCodeAtAll_listingIsForbidden() throws Exception {
        mockMvc.perform(as(caller(), get(PATH))).andExpect(status().isForbidden());
    }

    @Test
    void aTenantAdminNeedsNoGrantsAtAll() throws Exception {
        // hasFullAccess(): the realm role satisfies every fine-grained check
        // without a single role_permissions row.
        Caller admin = caller();
        mockMvc.perform(as(admin, get(PATH), TENANT_ADMIN)).andExpect(status().isOk());
        mockMvc.perform(as(admin, post(PATH), TENANT_ADMIN).content(body("Admin made this")))
                .andExpect(status().isCreated());
    }

    @Test
    void aTenantAdminStillCannotEditASystemTemplate() throws Exception {
        // The one that matters. A TENANT_ADMIN bypasses @PreAuthorize
        // entirely, so if "system templates are read-only" had been
        // expressed as a permission it would be no rule at all for exactly
        // this caller. It is enforced in WatermarkTemplateService instead,
        // and this is what holds that decision in place.
        Caller admin = caller();

        mockMvc.perform(as(admin, put(PATH + "/" + SYSTEM_CONFIDENTIAL), TENANT_ADMIN).content(body("Hijacked")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WATERMARK_TEMPLATE_READ_ONLY"));
        mockMvc.perform(as(admin, delete(PATH + "/" + SYSTEM_CONFIDENTIAL), TENANT_ADMIN))
                .andExpect(status().isForbidden());

        assertThat(templateRepository.findById(SYSTEM_CONFIDENTIAL).orElseThrow().getLabel())
                .isEqualTo("CONFIDENTIAL");
    }

    @Test
    void aTenantAdminStillCannotReachAnotherTenantsTemplate() throws Exception {
        Caller owner = caller();
        grant(owner, PermissionCodes.DW_WATERMARK_TEMPLATE_CREATE);
        UUID templateId = createAs(owner, "Not yours");

        // Full access inside their OWN tenant is not access across tenants.
        mockMvc.perform(as(caller(), put(PATH + "/" + templateId), TENANT_ADMIN).content(body("Stolen")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnauthenticatedCallerGetsNowhere() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------- fixtures

    private UUID createAs(Caller caller, String label) throws Exception {
        String json = mockMvc.perform(as(caller, post(PATH), TENANT_ADMIN).content(body(label)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("templateId").asText());
    }

    private MockHttpServletRequestBuilder as(Caller caller, MockHttpServletRequestBuilder request,
                                             String... authorities) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .with(jwt()
                        .jwt(builder -> builder.subject(caller.keycloakId().toString())
                                .claim("tenant_id", caller.tenantId().toString()))
                        .authorities(java.util.Arrays.stream(authorities)
                                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                                .toArray(org.springframework.security.core.GrantedAuthority[]::new)));
    }

    private String body(String label) throws Exception {
        return objectMapper.writeValueAsString(new WatermarkTemplateRequest(
                label, label.toUpperCase(java.util.Locale.ROOT), null, null, null, null, null, null, null));
    }

    /** Gives the caller a tenant role carrying exactly one permission code. */
    private void grant(Caller caller, String permissionCode) {
        UUID permissionId = jdbc.queryForObject(
                "select permission_id from permissions where permission_code = ?", UUID.class, permissionCode);
        Role role = roleRepository.save(Role.builder()
                .tenantId(caller.tenantId())
                .roleName("wm-test-" + UUID.randomUUID())
                .description("Watermark authorization test role")
                .status(Role.RoleStatus.active)
                .build());
        rolePermissionRepository.save(RolePermission.builder()
                .tenantId(caller.tenantId())
                .roleId(role.getRoleId())
                .permissionId(permissionId)
                .build());
        userRoleRepository.save(UserRole.builder()
                .tenantId(caller.tenantId())
                .userId(caller.userId())
                .roleId(role.getRoleId())
                .build());
    }

    private Caller caller() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Watermark authz tenant " + UUID.randomUUID())
                .domain("watermark-authz-" + UUID.randomUUID() + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());

        UUID keycloakId = UUID.randomUUID();
        User user = userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@watermark-authz.invalid")
                .username(keycloakId.toString())
                .firstName("Test")
                .lastName("Caller")
                .displayName("Test Caller")
                .status(User.UserStatus.active)
                .build());

        return new Caller(tenant.getTenantId(), user.getUserId(), keycloakId);
    }

    private record Caller(UUID tenantId, UUID userId, UUID keycloakId) {
    }
}
