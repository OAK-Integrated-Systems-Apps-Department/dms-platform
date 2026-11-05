package com.oak.dms.editing;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Where the online editor opens is each user's own choice (V129): default inside the DMS page, saved per user, validated. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class UserPreferencesIT extends WorkflowApiSupport {

    private static final String URL = "/api/v1/users/me/preferences";

    private User alice;
    private User bob;

    @BeforeEach
    void world() throws Exception {
        UUID tenantId = fx.tenant();
        alice = fx.user(tenantId, "Alice");
        bob = fx.user(tenantId, "Bob");
    }

    @Test
    void withNothingChosen_theEditorOpensInsideTheDmsPage() throws Exception {
        assertThat(get200(URL, alice).get("editorLayout").asString()).isEqualTo("inline");
    }

    @Test
    void aChoiceIsSaved_andComesBackNextTime() throws Exception {
        JsonNode saved = call(put(URL), fx.jwtFor(alice), Map.of("editorLayout", "fullpage"), 200);
        assertThat(saved.get("editorLayout").asString()).isEqualTo("fullpage");
        assertThat(get200(URL, alice).get("editorLayout").asString()).isEqualTo("fullpage");

        call(put(URL), fx.jwtFor(alice), Map.of("editorLayout", "inline"), 200);
        assertThat(get200(URL, alice).get("editorLayout").asString()).isEqualTo("inline");
        assertThat(fx.count("select count(*) from user_preferences where user_id = ?", alice.getUserId())).isEqualTo(1);
    }

    @Test
    void oneUsersChoice_neverChangesAnothers() throws Exception {
        call(put(URL), fx.jwtFor(alice), Map.of("editorLayout", "fullpage"), 200);
        assertThat(get200(URL, bob).get("editorLayout").asString()).isEqualTo("inline");
    }

    @Test
    void anythingButTheTwoLayouts_isRefused_andNothingIsStored() throws Exception {
        JsonNode error = call(put(URL), fx.jwtFor(alice), Map.of("editorLayout", "sideways"), 400);
        assertThat(error.toString()).contains("EDITOR_LAYOUT_INVALID");
        call(put(URL), fx.jwtFor(alice), Map.of(), 400);
        assertThat(get200(URL, alice).get("editorLayout").asString()).isEqualTo("inline");
    }
}
