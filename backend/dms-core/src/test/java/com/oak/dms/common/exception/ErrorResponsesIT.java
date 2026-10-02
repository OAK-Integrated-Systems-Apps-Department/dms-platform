package com.oak.dms.common.exception;

import com.oak.dms.tenant.Tenant;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Client mistakes the framework raises before any controller code runs are
 * answered 4xx with a plain message - they used to fall through to the
 * catch-all and come back 500 "Unhandled exception" (GlobalExceptionHandler).
 * A signed-in user with no permissions is enough: each of these fails while
 * the request is being read, before any permission check.
 */
@SpringBootTest
class ErrorResponsesIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private final UUID keycloakId = UUID.randomUUID();
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .tenantName("Error responses test " + keycloakId)
                .domain("error-responses-" + keycloakId + ".invalid")
                .subscriptionTier("standard")
                .status(Tenant.TenantStatus.active)
                .build());
        tenantId = tenant.getTenantId();
        userRepository.save(User.builder()
                .tenantId(tenantId)
                .keycloakId(keycloakId)
                .email(keycloakId + "@error-responses.invalid")
                .username(keycloakId.toString())
                .firstName("Error")
                .lastName("Responses")
                .displayName("Error Responses")
                .status(User.UserStatus.active)
                .build());
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(b -> b.subject(keycloakId.toString()).claim("tenant_id", tenantId.toString()))
                .authorities());
    }

    @Test
    void aMalformedIdInThePath_is400_namingTheParameter() throws Exception {
        mockMvc.perform(signedIn(get("/api/v1/documents/not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'id' must be a valid ID."));
    }

    @Test
    void anUnparseableOrMissingRequiredParameter_is400() throws Exception {
        mockMvc.perform(signedIn(get("/api/v1/import-templates/fields").param("documentTypeId", "undefined")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'documentTypeId' must be a valid ID."));
        mockMvc.perform(signedIn(get("/api/v1/import-templates/fields")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'documentTypeId' is required."));
    }

    @Test
    void aBodyThatIsntJson_is400() throws Exception {
        mockMvc.perform(signedIn(put("/api/v1/encryption/rotation-policy")
                        .contentType(MediaType.APPLICATION_JSON).content("not json")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request body is missing or isn't valid."));
    }

    @Test
    void aPathNothingMaps_is404_andAWrongMethod_is405() throws Exception {
        mockMvc.perform(signedIn(get("/api/v1/exports/preview")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("There is nothing at this address."));
        mockMvc.perform(signedIn(patch("/api/v1/encryption/status")))
                .andExpect(status().isMethodNotAllowed());
    }
}
