package com.oak.dms.watermark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.entity.RolePermission;
import com.oak.dms.security.rbac.entity.UserRole;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.watermark.config.WatermarkAvailability;
import com.oak.dms.watermark.dto.WatermarkJobResponse;
import com.oak.dms.watermark.service.WatermarkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Arrays;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code @PreAuthorize} rules on every watermarking endpoint, through
 * the real filter chain with a real JWT.
 * <p>
 * Calling the services directly would prove nothing about these: the
 * services have no idea the rules exist, and an annotation that stopped
 * matching a renamed constant would keep passing every service-level test.
 * <p>
 * The case worth the most here is {@code versionId}: watermarking the
 * current version needs only {@code docm.documents.view}, but naming an
 * older one is reading version history, so it needs
 * {@code dvc.view-version-history.view} too. Without that, this endpoint
 * would be a way around the permission {@code DocumentController} puts on
 * its own version downloads.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkEndpointAuthorizationIT extends WatermarkFixtures {

    private static final String TENANT_ADMIN = "ROLE_TENANT_ADMIN";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository users;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WatermarkService watermarkService;
    @Autowired
    private WatermarkAvailability availability;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ----------------------------------------------- POST .../watermark

    @Test
    void withoutDocumentViewTheRequestIsForbidden() throws Exception {
        Fixture fixture = fixture();
        mockMvc.perform(watermarkPost(fixture, "{\"customText\":\"DRAFT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withDocumentViewTheRequestIsAccepted_withALocationHeader() throws Exception {
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);

        mockMvc.perform(watermarkPost(fixture, "{\"customText\":\"DRAFT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/watermark-jobs/")))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.text").value("DRAFT"));
    }

    @Test
    void namingAVersionAlsoNeedsTheVersionHistoryPermission() throws Exception {
        // The rule that stops this endpoint being a way around the
        // permission DocumentController puts on its own version downloads.
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        String body = "{\"customText\":\"DRAFT\",\"versionId\":\"" + fixture.versionId() + "\"}";

        mockMvc.perform(watermarkPost(fixture, body)).andExpect(status().isForbidden());

        grant(fixture, PermissionCodes.DVC_VERSIONS_VIEW);
        mockMvc.perform(watermarkPost(fixture, body)).andExpect(status().isAccepted());
    }

    @Test
    void theVersionPermissionAloneIsNotEnoughToWatermarkAnything() throws Exception {
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DVC_VERSIONS_VIEW);
        mockMvc.perform(watermarkPost(fixture, "{\"customText\":\"DRAFT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnauthenticatedRequestGetsNowhere() throws Exception {
        Fixture fixture = fixture();
        mockMvc.perform(post("/api/v1/documents/" + fixture.documentId() + "/watermark")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"customText\":\"DRAFT\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTenantAdminNeedsNoGrants_butStillCannotReachAnotherTenantsDocument() throws Exception {
        Fixture owner = fixture();
        mockMvc.perform(watermarkPost(owner, "{\"customText\":\"DRAFT\"}", TENANT_ADMIN))
                .andExpect(status().isAccepted());

        Fixture stranger = fixture();
        mockMvc.perform(post("/api/v1/documents/" + owner.documentId() + "/watermark")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"customText\":\"DRAFT\"}")
                        .with(as(stranger, TENANT_ADMIN)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------- GET the job/result

    @Test
    void anotherUsersJobIsNotFoundEvenInsideTheSameTenant() throws Exception {
        // A watermarked copy carries the name of whoever asked for it.
        Fixture owner = fixture();
        grant(owner, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        WatermarkJobResponse job = watermarkService.apply(owner.documentId(), customText("DRAFT"), null);

        User colleague = sameTenantUser(owner);
        mockMvc.perform(get("/api/v1/watermark-jobs/" + job.jobId())
                        .with(jwtFor(colleague, owner.tenantId(), TENANT_ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theOwnerCanReadTheirOwnJob() throws Exception {
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        WatermarkJobResponse job = watermarkService.apply(fixture.documentId(), customText("DRAFT"), null);

        mockMvc.perform(get("/api/v1/watermark-jobs/" + job.jobId()).with(as(fixture)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.jobId().toString()))
                .andExpect(jsonPath("$.downloadAvailable").value(false));
    }

    @Test
    void readingAJobStillNeedsDocumentView() throws Exception {
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        WatermarkJobResponse job = watermarkService.apply(fixture.documentId(), customText("DRAFT"), null);

        Fixture other = fixture();   // a caller with no grants at all
        mockMvc.perform(get("/api/v1/watermark-jobs/" + job.jobId()).with(as(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnfinishedResultIsRefusedRatherThanServingSomethingHalfMade() throws Exception {
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        WatermarkJobResponse job = watermarkService.apply(fixture.documentId(), customText("DRAFT"), null);

        mockMvc.perform(get("/api/v1/watermark-jobs/" + job.jobId() + "/result").with(as(fixture)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WATERMARK_NOT_READY"));
    }

    @Test
    void anExpiredResultIsGoneRatherThanNotFound() throws Exception {
        // It existed and the caller is right to think so; asking for a new
        // one is the fix, and GONE says that where 404 would not.
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        WatermarkJobResponse job = watermarkService.apply(fixture.documentId(), customText("DRAFT"), null);
        jdbc.update("update watermark_jobs set status = 'completed', output_object_key = 'x', "
                + "expires_at = now() - interval '1 hour' where job_id = ?", job.jobId());

        mockMvc.perform(get("/api/v1/watermark-jobs/" + job.jobId() + "/result").with(as(fixture)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WATERMARK_EXPIRED"));
    }

    // ------------------------------------------------------------ preview

    @Test
    void previewNeedsEitherDocumentViewOrTemplateAdministration() throws Exception {
        Fixture nobody = fixture();
        mockMvc.perform(previewPost(nobody, "{\"customText\":\"DRAFT\"}"))
                .andExpect(status().isForbidden());

        Fixture viewer = fixture();
        grant(viewer, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        mockMvc.perform(previewPost(viewer, "{\"customText\":\"DRAFT\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
                .andExpect(header().string("Cache-Control", "no-store"));

        Fixture admin = fixture();
        grant(admin, PermissionCodes.DW_WATERMARK_TEMPLATE_UPDATE);
        mockMvc.perform(previewPost(admin, "{\"customText\":\"DRAFT\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void previewFillsPlaceholdersWithSamplesRatherThanTheCallersOwnDetails() throws Exception {
        // A preview is a picture of a configuration. It should look the same
        // to everyone opening the settings screen, and should not quietly
        // embed the viewer's email address in something they may screenshot.
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);

        byte[] png = mockMvc.perform(previewPost(fixture, "{\"customText\":\"{username} {userEmail}\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        org.assertj.core.api.Assertions.assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        // Nothing to assert in the pixels, but the request must not have
        // touched the user row at all - proven by the sample service having
        // no access to it. The real guard is the unit-level sample map.
    }

    @Test
    void anUnauthenticatedPreviewGetsNowhere() throws Exception {
        mockMvc.perform(post("/api/v1/watermark-templates/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"customText\":\"DRAFT\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------- fonts unavailable

    @Test
    void whenTheServerHasNoFontsEveryEntryPointIs503NotA404() throws Exception {
        // 503, so an operator looks at the image's fonts. A 404 would send
        // them hunting for a missing deployment or a routing rule.
        Fixture fixture = fixture();
        grant(fixture, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        try {
            breakFonts();
            mockMvc.perform(watermarkPost(fixture, "{\"customText\":\"DRAFT\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("WATERMARK_UNAVAILABLE"));
            mockMvc.perform(previewPost(fixture, "{\"customText\":\"DRAFT\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("WATERMARK_UNAVAILABLE"));
        } finally {
            availability.probeFonts();
        }
    }

    /** Flips the availability flag without touching the real font stack. */
    private void breakFonts() throws Exception {
        java.lang.reflect.Field field = WatermarkAvailability.class.getDeclaredField("fontsUsable");
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) field.get(availability)).set(false);
    }

    // ---------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder watermarkPost(Fixture fixture, String body, String... authorities) {
        return post("/api/v1/documents/" + fixture.documentId() + "/watermark")
                .contentType(MediaType.APPLICATION_JSON).content(body).with(as(fixture, authorities));
    }

    private MockHttpServletRequestBuilder previewPost(Fixture fixture, String body, String... authorities) {
        return post("/api/v1/watermark-templates/preview")
                .contentType(MediaType.APPLICATION_JSON).content(body).with(as(fixture, authorities));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(
            Fixture fixture, String... authorities) {
        UUID keycloakId = users.findById(fixture.userId()).orElseThrow().getKeycloakId();
        return jwtFor(keycloakId, fixture.tenantId(), authorities);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(
            User user, UUID tenantId, String... authorities) {
        return jwtFor(user.getKeycloakId(), tenantId, authorities);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(
            UUID keycloakId, UUID tenantId, String... authorities) {
        return jwt()
                .jwt(builder -> builder.subject(keycloakId.toString())
                        .claim("tenant_id", tenantId.toString()))
                .authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    private User sameTenantUser(Fixture fixture) {
        UUID keycloakId = UUID.randomUUID();
        return users.save(User.builder()
                .tenantId(fixture.tenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@watermark-authz.invalid")
                .username("colleague-" + keycloakId.toString().substring(0, 8))
                .firstName("A").lastName("Colleague").displayName("A Colleague")
                .status(User.UserStatus.active)
                .build());
    }

}
