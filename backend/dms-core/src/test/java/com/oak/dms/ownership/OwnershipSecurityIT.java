package com.oak.dms.ownership;

import com.oak.dms.department.entity.Department;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * O11: the permission matrix (every endpoint against every DOT permission on its own, an admin, a manager of the
 * document's department and a manager of another one) and tenant isolation (another tenant always sees 404, never 403).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipSecurityIT extends OwnershipApiSupport {

    @MockitoBean
    KeycloakUserService keycloak;

    private UUID tenantId;
    private UUID type;
    private User admin;
    private User owner;
    private User successor;
    private Department finance;
    private Department legal;
    private UUID doc;
    private UUID batch;

    /** One endpoint of the module: how to call it and which permission codes must all be held. */
    private record Endpoint(String name, String method, Function<World, String> path, Function<World, Object> body,
                            String[] required, boolean scoped) {
    }

    private record World(UUID doc, UUID owner, UUID successor, UUID department, UUID batch) {
    }

    private World world;
    private final List<Endpoint> endpoints = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Contract");
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        successor = fx.user(tenantId, "Successor");
        finance = fx.department(tenantId, "Finance", owner);
        legal = fx.department(tenantId, "Legal", successor);
        when(keycloak.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        when(keycloak.getPlatformRealmRoles(admin.getKeycloakId())).thenReturn(List.of("TENANT_ADMIN"));
        fx.role(tenantId, "Viewers", new String[]{VIEW}, owner, successor);
        doc = fx.document(tenantId, type, owner, "Matrix doc");
        // a batch to read: transfer another document as an admin
        UUID seed = fx.document(tenantId, type, owner, "Seed doc");
        batch = UUID.fromString(call(MockMvcRequestBuilders.post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin),
                selected(List.of(seed), owner, "reason", "manual"), 200).get("batchId").asString());
        // (seed was already owned by owner: a no-op batch is still a batch row)
        world = new World(doc, owner.getUserId(), successor.getUserId(), finance.getDepartmentId(), batch);

        endpoints.add(new Endpoint("single transfer", "POST", w -> "/api/v1/documents/" + w.doc() + "/ownership/transfer",
                w -> body("newOwnerUserId", w.successor()), new String[]{INITIATE}, true));
        endpoints.add(new Endpoint("single preview", "POST", w -> "/api/v1/documents/" + w.doc() + "/ownership/transfer/preview",
                w -> body("newOwnerUserId", w.successor()), new String[]{INITIATE}, true));
        endpoints.add(new Endpoint("owned list", "GET", w -> "/api/v1/ownership/documents?userId=" + w.owner(),
                w -> null, new String[]{SELECT}, true));
        endpoints.add(new Endpoint("pending list", "GET", w -> "/api/v1/ownership/pending", w -> null, new String[]{SELECT}, true));
        endpoints.add(new Endpoint("bulk transfer", "POST", w -> "/api/v1/ownership/transfers",
                w -> selected(List.of(w.doc()), userById(w.successor()), "grantAccess", false), new String[]{EXECUTE}, true));
        endpoints.add(new Endpoint("bulk preview", "POST", w -> "/api/v1/ownership/transfers/preview",
                w -> selected(List.of(w.doc()), userById(w.successor())), new String[]{EXECUTE}, true));
        endpoints.add(new Endpoint("offboarding preview", "GET", w -> "/api/v1/users/" + w.owner() + "/offboarding-preview",
                w -> null, new String[]{SELECT}, true));
        endpoints.add(new Endpoint("offboard", "POST", w -> "/api/v1/users/" + w.owner() + "/offboard",
                w -> body("reason", "x", "mode", "transfer", "newOwnerUserId", w.successor()), new String[]{EXECUTE, DEACTIVATE}, true));
        endpoints.add(new Endpoint("history", "GET", w -> "/api/v1/documents/" + w.doc() + "/ownership-history",
                w -> null, new String[]{HISTORY}, false));
        endpoints.add(new Endpoint("history export", "GET", w -> "/api/v1/ownership/history/export", w -> null,
                new String[]{EXPORT}, false));
        endpoints.add(new Endpoint("owner info", "GET", w -> "/api/v1/documents/" + w.doc() + "/ownership", w -> null,
                new String[]{VIEW}, false));
    }

    private User userById(UUID id) {
        return successor.getUserId().equals(id) ? successor : owner;
    }

    private MockHttpServletResponse hit(Endpoint endpoint, User as, boolean tenantAdmin, World w) throws Exception {
        MockHttpServletRequestBuilder request = "POST".equals(endpoint.method())
                ? MockMvcRequestBuilders.post(endpoint.path().apply(w)) : MockMvcRequestBuilders.get(endpoint.path().apply(w));
        Object payload = endpoint.body().apply(w);
        if (payload != null) {
            request.contentType("application/json").content(json.writeValueAsString(payload));
        }
        return mvc.perform(request.with(tenantAdmin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as))).andReturn().getResponse();
    }

    private static String codeOf(MockHttpServletResponse response) throws Exception {
        String content = response.getContentAsString();
        return content.contains("OWNERSHIP_SCOPE_DENIED") ? "OWNERSHIP_SCOPE_DENIED" : "";
    }

    /** Denied by the permission check itself: 403 that is not the module's own scope refusal. */
    private boolean deniedByPermission(MockHttpServletResponse response) throws Exception {
        return response.getStatus() == 403 && !"OWNERSHIP_SCOPE_DENIED".equals(codeOf(response));
    }

    @Test
    void everyEndpointRefusesSomeoneWithoutThePermission() throws Exception {
        User nobody = fx.user(tenantId, "Nobody");
        User approver = fx.user(tenantId, "ApproveOnly");
        fx.role(tenantId, "Approve only", new String[]{APPROVE}, approver);
        for (Endpoint endpoint : endpoints) {
            if (endpoint.name().equals("owner info")) {
                continue;     // needs only docm.documents.view: covered below
            }
            assertThat(deniedByPermission(hit(endpoint, nobody, false, world))).as(endpoint.name() + " as nobody").isTrue();
            assertThat(deniedByPermission(hit(endpoint, approver, false, world))).as(endpoint.name() + " with approve-transfer only").isTrue();
        }
        assertThat(hit(endpoints.get(endpoints.size() - 1), nobody, false, world).getStatus()).isEqualTo(403);
        assertThat(ownerOf(doc)).isEqualTo(owner.getUserId().toString());
    }

    @Test
    void eachPermissionOnItsOwnOpensOnlyItsOwnEndpoints() throws Exception {
        String[] codes = {INITIATE, SELECT, EXECUTE, HISTORY, EXPORT, DEACTIVATE};
        for (String code : codes) {
            User holder = fx.user(tenantId, "Only " + code.substring(code.lastIndexOf('.') + 1));
            fx.role(tenantId, "Role " + code, new String[]{code}, holder);
            for (Endpoint endpoint : endpoints) {
                MockHttpServletResponse response = hit(endpoint, holder, false, world);
                boolean allowedByPermission = List.of(endpoint.required()).stream().allMatch(code::equals);
                if (allowedByPermission) {
                    assertThat(deniedByPermission(response)).as(endpoint.name() + " with " + code).isFalse();
                } else {
                    assertThat(deniedByPermission(response) || endpoint.name().equals("owner info")).as(endpoint.name() + " with " + code).isTrue();
                }
            }
        }
        assertThat(ownerOf(doc)).isEqualTo(owner.getUserId().toString());
        assertThat(historyRows(tenantId, doc)).isZero();
    }

    @Test
    void aPlainDotHolderWhoHeadsNothingIsRefusedByScopeOnEveryScopedEndpoint() throws Exception {
        User plain = fx.user(tenantId, "Plain");
        fx.role(tenantId, "All DOT", new String[]{INITIATE, SELECT, EXECUTE, HISTORY, EXPORT, DEACTIVATE, VIEW}, plain);
        for (Endpoint endpoint : endpoints) {
            MockHttpServletResponse response = hit(endpoint, plain, false, world);
            if (endpoint.scoped()) {
                assertThat(response.getStatus()).as(endpoint.name()).isEqualTo(403);
                assertThat(codeOf(response)).as(endpoint.name()).isEqualTo("OWNERSHIP_SCOPE_DENIED");
            } else {
                assertThat(response.getStatus()).as(endpoint.name()).isEqualTo(200);
            }
        }
        assertThat(ownerOf(doc)).isEqualTo(owner.getUserId().toString());
    }

    @Test
    void aManagerActsInsideTheirDepartmentOnly() throws Exception {
        User manager = fx.user(tenantId, "Manager");
        makeHead(finance.getDepartmentId(), manager);
        User elsewhere = fx.user(tenantId, "ElsewhereHead");
        makeHead(legal.getDepartmentId(), elsewhere);
        for (User u : new User[]{manager, elsewhere}) {
            fx.role(tenantId, "All DOT " + u.getFirstName(), new String[]{INITIATE, SELECT, EXECUTE, HISTORY, EXPORT, DEACTIVATE, VIEW}, u);
        }
        for (Endpoint endpoint : endpoints) {
            if (!endpoint.scoped() || endpoint.name().equals("pending list")) {
                continue;
            }
            if (endpoint.name().equals("bulk transfer") || endpoint.name().equals("bulk preview")) {
                continue;   // selection-based: an out-of-scope document is skipped, not refused (see the manager tests in OwnershipBulkIT)
            }
            assertThat(hit(endpoint, elsewhere, false, world).getStatus()).as(endpoint.name() + " other department's head").isEqualTo(403);
        }
        // the owner's own department head passes the scope on the read endpoints
        assertThat(hit(endpoints.get(2), manager, false, world).getStatus()).isEqualTo(200);   // owned list
        assertThat(hit(endpoints.get(6), manager, false, world).getStatus()).isEqualTo(200);   // offboarding preview
        assertThat(hit(endpoints.get(1), manager, false, world).getStatus()).isEqualTo(200);   // single preview
        assertThat(hit(endpoints.get(0), manager, false, world).getStatus()).isEqualTo(200);   // single transfer
        assertThat(ownerOf(doc)).isEqualTo(successor.getUserId().toString());
    }

    @Test
    void aTenantAdminPassesEveryEndpoint() throws Exception {
        for (Endpoint endpoint : endpoints) {
            if (endpoint.name().equals("offboard") || endpoint.name().equals("single transfer") || endpoint.name().equals("bulk transfer")) {
                continue;   // these change state; exercised in their own ITs
            }
            assertThat(hit(endpoint, admin, true, world).getStatus()).as(endpoint.name()).isEqualTo(200);
        }
    }

    @Test
    void anotherTenantSeesNotFoundOnEveryEndpoint() throws Exception {
        UUID otherTenant = fx.tenant();
        User otherAdmin = fx.user(otherTenant, "OtherAdmin");
        User otherTarget = fx.user(otherTenant, "OtherTarget");
        when(keycloak.getPlatformRealmRoles(otherAdmin.getKeycloakId())).thenReturn(List.of("TENANT_ADMIN"));
        for (Endpoint endpoint : endpoints) {
            MockHttpServletResponse response = hit(endpoint, otherAdmin, true, world);
            switch (endpoint.name()) {
                case "history export" -> {
                    assertThat(response.getStatus()).isEqualTo(200);
                    assertThat(response.getContentAsString().split("\r\n")).hasSize(1);
                }
                case "pending list" -> assertThat(response.getStatus()).isEqualTo(200);
                case "bulk transfer", "bulk preview" -> {
                    // a selection naming another tenant's document is just "not found", and nothing moves
                    assertThat(response.getStatus()).isIn(404, 422);
                }
                default -> assertThat(response.getStatus()).as(endpoint.name()).isEqualTo(404);
            }
        }
        // the foreign admin naming our document in their own selection moves nothing
        var summary = call(MockMvcRequestBuilders.post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(otherAdmin),
                selected(List.of(doc), otherTarget), 200);
        assertThat(summary.get("transferredCount").asInt()).isZero();
        assertThat(summary.get("skipped").get(0).get("reason").asString()).isEqualTo("not_found");
        call(MockMvcRequestBuilders.get("/api/v1/ownership/batches/" + batch), fx.tenantAdminJwtFor(otherAdmin), null, 404);
        assertThat(ownerOf(doc)).isEqualTo(owner.getUserId().toString());
        assertThat(historyRows(tenantId, doc)).isZero();
    }

    @Test
    void hiddenDocumentsNeverLeakTitleTypeOrIdToAManager() throws Exception {
        User manager = fx.user(tenantId, "Manager");
        makeHead(finance.getDepartmentId(), manager);
        fx.role(tenantId, "Mgr", new String[]{INITIATE, SELECT, EXECUTE, HISTORY, VIEW}, manager);
        UUID secret = fx.document(tenantId, type, owner, "Hidden salary data");
        restrictTo(tenantId, secret, "edit", owner);
        String[] paths = {"/api/v1/ownership/documents?userId=" + owner.getUserId(), "/api/v1/ownership/pending",
                "/api/v1/users/" + owner.getUserId() + "/offboarding-preview"};
        for (String path : paths) {
            MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get(path).with(fx.jwtFor(manager))).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).as(path).doesNotContain("Hidden salary data").doesNotContain(secret.toString());
        }
        for (String path : new String[]{"/api/v1/documents/" + secret + "/ownership", "/api/v1/documents/" + secret + "/ownership-history"}) {
            MockHttpServletResponse response = mvc.perform(MockMvcRequestBuilders.get(path).with(fx.jwtFor(manager))).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isEqualTo(404);
            assertThat(response.getContentAsString()).doesNotContain("Hidden salary data");
        }
        MockHttpServletResponse single = mvc.perform(MockMvcRequestBuilders.post("/api/v1/documents/" + secret + "/ownership/transfer")
                .contentType("application/json").content(json.writeValueAsString(body("newOwnerUserId", successor.getUserId())))
                .with(fx.jwtFor(manager))).andReturn().getResponse();
        assertThat(single.getStatus()).isEqualTo(404);
        JsonNode list = call(MockMvcRequestBuilders.get("/api/v1/ownership/documents?userId=" + owner.getUserId()), fx.jwtFor(manager), null, 200);
        assertThat(list.get("hiddenCount").asInt()).isEqualTo(1);
    }
}
