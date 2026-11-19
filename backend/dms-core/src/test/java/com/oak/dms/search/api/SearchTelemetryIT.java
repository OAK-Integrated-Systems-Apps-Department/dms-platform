package com.oak.dms.search.api;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The preview telemetry call: login required, a closed enum, about 30 a minute per person, a counter and nothing else. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchTelemetryIT extends SearchApiSupport {

    private static final String URL = "/api/v1/search/telemetry/preview";

    @Autowired
    private MeterRegistry registry;

    private double count(String reason) {
        return registry.get("dms.search.preview.failures").tag("reason", reason).counter().count();
    }

    @Test
    void aClosedListOfReasonsIsCounted() throws Exception {
        double before = count("failed");
        call(post(URL), fx.jwtFor(alice), Map.of("reason", "failed"), 204);
        assertThat(count("failed")).isEqualTo(before + 1);
        for (String reason : new String[]{"unsupported", "timeout", "policy_blocked"}) {
            call(post(URL), fx.jwtFor(alice), Map.of("reason", reason), 204);
        }
    }

    @Test
    void anythingOutsideTheListIsRefused_andNothingIsCounted() throws Exception {
        double before = count("failed");
        call(post(URL), fx.jwtFor(alice), Map.of("reason", "document 123 failed"), 400);
        call(post(URL), fx.jwtFor(alice), Map.of("reason", ""), 400);
        call(post(URL), fx.jwtFor(alice), Map.of("other", "x"), 400);
        assertThat(count("failed")).isEqualTo(before);
        // no tag or series was created from the input
        assertThat(registry.find("dms.search.preview.failures").counters()).hasSize(4);
    }

    @Test
    void aLoginAndTheSearchPermissionAreRequired() throws Exception {
        mvc.perform(post(URL).contentType("application/json").content("{\"reason\":\"failed\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        call(post(URL), fx.jwtFor(carol), Map.of("reason", "failed"), 403);
    }

    @Test
    void aboutThirtyCallsAMinutePerPerson_theRestAre429_andAnotherPersonIsUnaffected() throws Exception {
        User busy = fx.user(tenantId, "Busy");
        fx.role(tenantId, "Telemetry", new String[]{"dsr.search-documents"}, busy);
        double before = count("timeout");
        for (int i = 0; i < 30; i++) {
            call(post(URL), fx.jwtFor(busy), Map.of("reason", "timeout"), 204);
        }
        call(post(URL), fx.jwtFor(busy), Map.of("reason", "timeout"), 429);
        call(post(URL), fx.jwtFor(busy), Map.of("reason", "timeout"), 429);
        assertThat(count("timeout")).as("only accepted calls count").isEqualTo(before + 30);
        call(post(URL), fx.jwtFor(bob), Map.of("reason", "timeout"), 204);
    }
}
