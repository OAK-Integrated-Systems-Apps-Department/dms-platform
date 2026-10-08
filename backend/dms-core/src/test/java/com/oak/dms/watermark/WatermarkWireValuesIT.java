package com.oak.dms.watermark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hyphenated wire values survive the round trip through the REAL
 * controller {@code ObjectMapper}.
 * <p>
 * This project builds its own mapper in {@code JacksonLegacyConfig} with
 * {@code findAndRegisterModules()} rather than taking Spring Boot's
 * auto-configured one, so a serialisation rule that works in a unit test
 * with {@code new ObjectMapper()} is not evidence that it works in a
 * request. {@code header-footer} and {@code first-page-only} aren't valid
 * Java identifiers, so they only work because of {@code @JsonValue} /
 * {@code @JsonCreator} on the enums - and if those were ever dropped, the
 * API would start silently accepting {@code HEADER_FOOTER} and writing that
 * into {@code request_snapshot} instead, where it would violate the V68
 * CHECK constraint on the way to the templates table.
 */
@SpringBootTest(properties = "dms.watermark.enabled=true")
class WatermarkWireValuesIT extends WatermarkFixtures {

    private static final String PATH = "/api/v1/watermark-templates";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;

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
    void theHyphenatedValuesParseThroughTheRealControllerMapperAndComeBackUnchanged() throws Exception {
        mockMvc.perform(request("""
                        {"label":"Wire values","defaultText":"CONFIDENTIAL",
                         "placement":"header-footer","pageScope":"first-page-only"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placement").value("header-footer"))
                .andExpect(jsonPath("$.pageScope").value("first-page-only"));
    }

    @Test
    void everyPlacementAndScopeValueIsAcceptedOverTheWire() throws Exception {
        int suffix = 0;
        for (WatermarkPlacement placement : WatermarkPlacement.values()) {
            for (WatermarkPageScope scope : WatermarkPageScope.values()) {
                mockMvc.perform(request("""
                                {"label":"Combo %d","defaultText":"X","placement":"%s","pageScope":"%s"}"""
                                .formatted(suffix++, placement.getValue(), scope.getValue())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.placement").value(placement.getValue()))
                        .andExpect(jsonPath("$.pageScope").value(scope.getValue()));
            }
        }
    }

    @Test
    void theJavaConstantNameIsRejectedOverTheWire() throws Exception {
        // One spelling per value. Accepting both would put two different
        // strings for one concept into request_snapshot.
        mockMvc.perform(request("""
                        {"label":"Constant name","defaultText":"X","placement":"HEADER_FOOTER"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownPlacementIsA400RatherThanASilentDefault() throws Exception {
        mockMvc.perform(request("""
                        {"label":"Unknown","defaultText":"X","placement":"diagonal"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSnapshotTheWorkerReadsRoundTripsThroughTheSameMapper() throws Exception {
        // WatermarkSpec is written to watermark_jobs.request_snapshot by the
        // request thread and read back by the worker. If the two disagreed
        // about how a placement is spelled, every job would draw the default.
        WatermarkSpec spec = new WatermarkSpec("CONFIDENTIAL - jdoe", 40, 25, -45, "#808080",
                WatermarkPlacement.HEADER_FOOTER, WatermarkPageScope.FIRST_PAGE_ONLY);

        String json = objectMapper.writeValueAsString(spec);
        assertThat(json).contains("\"header-footer\"").contains("\"first-page-only\"");
        assertThat(objectMapper.readValue(json, WatermarkSpec.class)).isEqualTo(spec);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String body) {
        UUID keycloakId = UUID.randomUUID();
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Wire values tenant " + keycloakId)
                .domain("wire-values-" + keycloakId + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        trackTenant(tenant.getTenantId());
        userRepository.save(User.builder()
                .tenantId(tenant.getTenantId())
                .keycloakId(keycloakId)
                .email(keycloakId + "@wire-values.invalid")
                .username(keycloakId.toString())
                .firstName("Wire")
                .lastName("Values")
                .displayName("Wire Values")
                .status(User.UserStatus.active)
                .build());

        return post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt()
                        .jwt(builder -> builder.subject(keycloakId.toString())
                                .claim("tenant_id", tenant.getTenantId().toString()))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_TENANT_ADMIN")));
    }
}
