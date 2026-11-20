package com.oak.dms.search.api;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The permission matrix of every search endpoint (plan P2.7): who gets 401, 403 or success. One table, one assertion per
 * cell, so a change to any {@code @PreAuthorize} shows up as a named failure.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class SearchPermissionMatrixIT extends SearchApiSupport {

    private enum Who { NOBODY_LOGGED_IN, NO_PERMISSIONS, VIEW_ONLY, SEARCH_ONLY, PRESET_MANAGER, ARCHIVIST, TENANT_ADMIN, OTHER_ADMIN }

    private record Row(String name, Supplier<MockHttpServletRequestBuilder> request, Object body, Map<Who, Integer> expected) {
    }

    private static Map<Who, Integer> expect(int none, int noPerms, int viewOnly, int searchOnly, int manager, int archivist, int admin) {
        Map<Who, Integer> m = new LinkedHashMap<>();
        m.put(Who.NOBODY_LOGGED_IN, none);
        m.put(Who.NO_PERMISSIONS, noPerms);
        m.put(Who.VIEW_ONLY, viewOnly);
        m.put(Who.SEARCH_ONLY, searchOnly);
        m.put(Who.PRESET_MANAGER, manager);
        m.put(Who.ARCHIVIST, archivist);
        m.put(Who.TENANT_ADMIN, admin);
        m.put(Who.OTHER_ADMIN, admin);
        return m;
    }

    private RequestPostProcessor as(Who who, Map<Who, User> users) {
        return switch (who) {
            case NOBODY_LOGGED_IN -> null;
            case TENANT_ADMIN, OTHER_ADMIN -> fx.tenantAdminJwtFor(users.get(who));
            default -> fx.jwtFor(users.get(who));
        };
    }

    @Test
    void everyEndpointAgainstEveryKindOfPerson() throws Exception {
        String create = "dsr.manage-saved-search-presets.create";
        String update = "dsr.manage-saved-search-presets.update";
        String delete = "dsr.manage-saved-search-presets.delete";
        Map<Who, User> users = new LinkedHashMap<>();
        users.put(Who.NO_PERMISSIONS, fx.user(tenantId, "Nobody"));
        users.put(Who.VIEW_ONLY, carol);
        users.put(Who.SEARCH_ONLY, bob);
        User manager = fx.user(tenantId, "Manager");
        fx.role(tenantId, "Manager", new String[]{"dsr.search-documents", "docm.documents.view", create, update, delete}, manager);
        users.put(Who.PRESET_MANAGER, manager);
        users.put(Who.ARCHIVIST, archivist);
        users.put(Who.TENANT_ADMIN, tenantAdmin);
        UUID otherTenant = fx.tenant();
        User otherAdmin = fx.user(otherTenant, "OtherAdmin");
        users.put(Who.OTHER_ADMIN, otherAdmin);

        // a preset of the manager's own, to point update/delete/get at (everyone else gets 403 or 404 for it)
        UUID preset = UUID.randomUUID();
        fx.jdbc().update("insert into saved_searches (preset_id, tenant_id, user_id, name, criteria) values (?, ?, ?, 'Matrix', '{\"q\":\"x\"}'::jsonb)",
                preset, tenantId, manager.getUserId());
        String base = "/api/v1/search/presets";
        List<Row> rows = List.of(
                new Row("GET /search", () -> get("/api/v1/search").queryParam("q", "x"), null, expect(401, 403, 403, 200, 200, 200, 200)),
                new Row("GET /search/suggest", () -> get("/api/v1/search/suggest").queryParam("q", "ab"), null, expect(401, 403, 403, 200, 200, 200, 200)),
                new Row("GET /search/filters", () -> get("/api/v1/search/filters"), null, expect(401, 403, 403, 200, 200, 200, 200)),
                // archived states additionally need crm.view-archive: refused for that filter only
                new Row("GET /search?status=archived", () -> get("/api/v1/search").queryParam("q", "x").queryParam("status", "archived"), null,
                        expect(401, 403, 403, 403, 403, 200, 200)),
                new Row("GET /search/presets", () -> get(base), null, expect(401, 403, 403, 200, 200, 200, 200)),
                new Row("POST /search/presets", () -> post(base), Map.of("name", "m-" + UUID.randomUUID(), "criteria", Map.of("q", "x")),
                        expect(401, 403, 403, 403, 201, 403, 201)),
                new Row("POST /search/telemetry/preview", () -> post("/api/v1/search/telemetry/preview"), Map.of("reason", "failed"),
                        expect(401, 403, 403, 204, 204, 204, 204)),
                new Row("GET /admin/search/status", () -> get("/api/v1/admin/search/status"), null, expect(401, 403, 403, 403, 403, 403, 200)),
                new Row("GET /admin/search/presets/limit", () -> get("/api/v1/admin/search/presets/limit"), null, expect(401, 403, 403, 403, 403, 403, 200)),
                new Row("PUT /admin/search/presets/limit", () -> put("/api/v1/admin/search/presets/limit"), Map.of("maxPerUser", 40),
                        expect(401, 403, 403, 403, 403, 403, 200)),
                new Row("POST /admin/search/reindex", () -> post("/api/v1/admin/search/reindex"), null, expect(401, 403, 403, 403, 403, 403, 202)),
                new Row("POST /admin/search/reconcile", () -> post("/api/v1/admin/search/reconcile"), null, expect(401, 403, 403, 403, 403, 403, 202)),
                // someone else's preset is 404 for everybody who may touch presets at all
                new Row("GET /search/presets/{other's}", () -> get(base + "/" + preset), null, expect(401, 403, 403, 404, 200, 404, 404)),
                new Row("PUT /search/presets/{other's}", () -> put(base + "/" + preset), Map.of("name", "renamed"), expect(401, 403, 403, 403, 200, 403, 404)),
                new Row("DELETE /search/presets/{other's}", () -> delete(base + "/" + preset), null, expect(401, 403, 403, 403, 204, 403, 404)));

        List<String> wrong = new java.util.ArrayList<>();
        for (Row row : rows) {
            for (Who who : Who.values()) {
                // the admin endpoints that mutate are rate limited per tenant: ask each tenant once
                MockHttpServletRequestBuilder request = row.request().get();
                RequestPostProcessor auth = as(who, users);
                if (auth != null) {
                    request.with(auth);
                }
                if (row.body() != null) {
                    request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(row.body()));
                }
                int status = mvc.perform(request).andReturn().getResponse().getStatus();
                int expected = row.expected().get(who);
                if (status != expected) {
                    wrong.add(row.name() + " as " + who + ": expected " + expected + " but was " + status);
                }
            }
        }
        assertThat(wrong).as("permission matrix").isEmpty();
    }
}
