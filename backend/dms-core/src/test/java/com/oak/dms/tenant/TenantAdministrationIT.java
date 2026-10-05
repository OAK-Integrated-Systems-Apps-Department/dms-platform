package com.oak.dms.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Organisations screen's API: deactivated organisations listed, reactivated - but never after their keys are gone. */
@SpringBootTest
class TenantAdministrationIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TenantRepository tenantRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpServletRequestBuilder asPlatformAdmin(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
    }

    private Tenant tenant() {
        String id = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder().tenantName("Admin IT " + id).domain("admin-it-" + id + ".invalid")
                .subscriptionTier("standard").status(Tenant.TenantStatus.active).build());
    }

    @Test
    void aDeactivatedOrganisation_isListed_andCanBeReactivated() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(asPlatformAdmin(delete("/api/v1/tenants/" + org.getTenantId())))
                .andExpect(status().isNoContent());

        String listed = mockMvc.perform(asPlatformAdmin(get("/api/v1/tenants").param("includeDeactivated", "true")
                        .param("size", "1000")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listed).contains(org.getTenantId().toString()).contains("\"deactivated\":true");
        String activeOnly = mockMvc.perform(asPlatformAdmin(get("/api/v1/tenants").param("size", "1000")))
                .andReturn().getResponse().getContentAsString();
        assertThat(activeOnly).doesNotContain(org.getTenantId().toString());

        mockMvc.perform(asPlatformAdmin(post("/api/v1/tenants/" + org.getTenantId() + "/reactivate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivated").value(false))
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void anOrganisationWhoseKeysWereDestroyed_staysGone() throws Exception {
        Tenant org = tenant();
        org.setDeleted(true);
        org.setDeletedAt(OffsetDateTime.now());
        org.setKeysShreddedAt(OffsetDateTime.now());
        tenantRepository.save(org);

        mockMvc.perform(asPlatformAdmin(post("/api/v1/tenants/" + org.getTenantId() + "/reactivate")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_KEYS_SHREDDED"));
    }

    @Test
    void onlyPlatformAdmins() throws Exception {
        Tenant org = tenant();
        mockMvc.perform(post("/api/v1/tenants/" + org.getTenantId() + "/reactivate")
                        .with(jwt().jwt(b -> b.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }
}
