package com.oak.dms.ocrworker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the thing {@link OcrJobTokenHealthIndicatorTest} cannot: that the
 * "core" health group (application.yml) - what the container HEALTHCHECK
 * and deploy.sh actually gate releases on, see backend/Dockerfile - stays
 * UP when {@code ocrJobToken} is DOWN, while root {@code /actuator/health}
 * still reports the problem for anyone checking by hand.
 * <p>
 * dms.ocr.enabled=true with no job-token secret is forced here via
 * {@code @DynamicPropertySource} specifically to put {@code ocrJobToken}
 * into its DOWN state - the one real way to end up with a health
 * indicator actually failing in this suite without a separate profile.
 */
@SpringBootTest(properties = "dms.ocr.enabled=true")
class OcrJobTokenHealthGroupIT {

    @Autowired
    private WebApplicationContext context;

    @DynamicPropertySource
    static void noJobTokenSecret(DynamicPropertyRegistry registry) {
        registry.add("dms.ocr.job-token-secret", () -> "");
    }

    @Test
    void theCoreGroupStaysUp_whileRootReportsTheOcrProblem() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        mockMvc.perform(get("/actuator/health/core"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
