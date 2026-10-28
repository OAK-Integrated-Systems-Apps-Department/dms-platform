package com.oak.dms.relationship;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The relationship types admin API (R3): seed visibility, create/update/delete rules, permissions, tenant isolation, audit. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class RelationshipTypeApiIT extends RelationshipApiSupport {

    private UUID tenantId;
    private User admin;
    private User linker;
    private User configurer;
    private User updater;
    private User deleter;
    private User nobody;

    @BeforeEach
    void world() {
        tenantId = tenantWithTypes();
        admin = fx.user(tenantId, "Admin");
        linker = fx.user(tenantId, "Linker");
        configurer = fx.user(tenantId, "Configurer");
        updater = fx.user(tenantId, "Updater");
        deleter = fx.user(tenantId, "Deleter");
        nobody = fx.user(tenantId, "Nobody");
        fx.role(tenantId, "Linker", new String[]{CREATE, VIEW}, linker);
        fx.role(tenantId, "Configurer", new String[]{TYPE_CREATE}, configurer);
        fx.role(tenantId, "Updater", new String[]{TYPE_UPDATE}, updater);
        fx.role(tenantId, "Deleter", new String[]{TYPE_DELETE}, deleter);
    }

    private JsonNode byCode(JsonNode list, String code) {
        for (JsonNode t : list) {
            if (code.equals(t.get("code").asString())) {
                return t;
            }
        }
        throw new AssertionError("No type " + code + " in " + list);
    }

    @Test
    void adminSeesTheTenTypesWithUsage() throws Exception {
        JsonNode list = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/relationship-types?includeDisabled=true"), fx.tenantAdminJwtFor(admin), null, 200);
        assertThat(list).hasSize(10);
        JsonNode supersedes = byCode(list, "SUPERSEDES");
        assertThat(supersedes.get("label").asString()).isEqualTo("Supersedes");
        assertThat(supersedes.get("inverseLabel").asString()).isEqualTo("Superseded by");
        assertThat(supersedes.get("preventsCycles").asBoolean()).isTrue();
        assertThat(supersedes.get("system").asBoolean()).isTrue();
        assertThat(supersedes.get("usageCount").asLong()).isZero();
        assertThat(byCode(list, "RELATED").get("directionality").asString()).isEqualTo("symmetric");
    }

    @Test
    void readingNeedsLinkOrConfigurePermission_andDisabledOnesNeedConfigure() throws Exception {
        types(nobody, false, 403);
        assertThat(types(linker, false, 200)).hasSize(10);
        types(linker, true, 403);
        assertThat(types(configurer, true, 200)).hasSize(10);
    }

    @Test
    void createCustomType_rules() throws Exception {
        types(linker, false, 200);
        postJson("/api/v1/relationship-types", linker, body("label", "X", "directionality", "directional"), 403);

        JsonNode created = postJson("/api/v1/relationship-types", configurer,
                body("label", "Quote -> Order", "inverseLabel", "Order from", "directionality", "directional"), 201);
        assertThat(created.get("code").asString()).isEqualTo("QUOTE_ORDER");
        assertThat(created.get("system").asBoolean()).isFalse();
        assertThat(created.get("enabled").asBoolean()).isTrue();
        assertThat(auditCount(tenantId, "RELATIONSHIP_TYPE_CREATED")).isEqualTo(1);

        // Same label again, and a built-in label, are refused.
        assertThat(postJson("/api/v1/relationship-types", configurer,
                body("label", "quote -> order", "directionality", "directional"), 409).get("code").asString())
                .isEqualTo("RELATIONSHIP_TYPE_CODE_TAKEN");
        assertThat(postJson("/api/v1/relationship-types", configurer,
                body("label", "Related Document", "directionality", "symmetric"), 409).get("code").asString())
                .isEqualTo("RELATIONSHIP_TYPE_CODE_TAKEN");
        // A two-way type can't prevent loops; the inverse is the label.
        postJson("/api/v1/relationship-types", configurer,
                body("label", "Twin", "directionality", "symmetric", "preventsCycles", true), 422);
        JsonNode twin = postJson("/api/v1/relationship-types", configurer,
                body("label", "Twin", "inverseLabel", "ignored", "directionality", "symmetric"), 201);
        assertThat(twin.get("inverseLabel").asString()).isEqualTo("Twin");
        postJson("/api/v1/relationship-types", configurer, body("label", " ", "directionality", "symmetric"), 400);
    }

    @Test
    void systemTypes_canBeRenamedAndDisabled_notReshapedOrDeleted() throws Exception {
        UUID supersedes = typeId(tenantId, "SUPERSEDES");

        JsonNode renamed = putAs("/api/v1/relationship-types/" + supersedes, updater,
                body("label", "Overrides", "inverseLabel", "Overridden by", "displayOrder", 5), 200);
        assertThat(renamed.get("label").asString()).isEqualTo("Overrides");
        assertThat(renamed.get("code").asString()).isEqualTo("SUPERSEDES");
        assertThat(auditCount(tenantId, "RELATIONSHIP_TYPE_UPDATED")).isEqualTo(1);

        assertThat(putAs("/api/v1/relationship-types/" + supersedes, updater, body("preventsCycles", false), 422)
                .get("code").asString()).isEqualTo("RELATIONSHIP_TYPE_SYSTEM_FIXED");
        assertThat(putAs("/api/v1/relationship-types/" + supersedes, updater, body("directionality", "symmetric"), 422)
                .get("code").asString()).isEqualTo("RELATIONSHIP_TYPE_SYSTEM_FIXED");

        JsonNode disabled = putAs("/api/v1/relationship-types/" + supersedes, updater, body("enabled", false), 200);
        assertThat(disabled.get("enabled").asBoolean()).isFalse();
        assertThat(auditCount(tenantId, "RELATIONSHIP_TYPE_DISABLED")).isEqualTo(1);
        assertThat(types(linker, false, 200)).hasSize(9);
        assertThat(types(updater, true, 200)).hasSize(10);

        putAs("/api/v1/relationship-types/" + supersedes, updater, body("enabled", true), 200);
        assertThat(auditCount(tenantId, "RELATIONSHIP_TYPE_ENABLED")).isEqualTo(1);

        assertThat(deleteAs("/api/v1/relationship-types/" + supersedes, deleter, 409).get("code").asString())
                .isEqualTo("RELATIONSHIP_TYPE_SYSTEM");
        // Update needs update, delete needs delete.
        putAs("/api/v1/relationship-types/" + supersedes, deleter, body("label", "x"), 403);
        deleteAs("/api/v1/relationship-types/" + supersedes, updater, 403);
    }

    @Test
    void customType_deletableOnlyWhileUnused() throws Exception {
        UUID unused = id(postJson("/api/v1/relationship-types", configurer,
                body("label", "Unused", "directionality", "directional"), 201), "relationshipTypeId");
        UUID used = id(postJson("/api/v1/relationship-types", configurer,
                body("label", "Used", "directionality", "directional"), 201), "relationshipTypeId");

        UUID contract = fx.documentType(tenantId, "Contract");
        UUID a = fx.document(tenantId, contract, admin, "A");
        UUID b = fx.document(tenantId, contract, admin, "B");
        fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id,"
                + " target_document_id) values (?, ?, ?, ?)", tenantId, used, a, b);

        // In use: usage count shows, direction frozen, delete refused, disable allowed.
        assertThat(byCode(types(updater, true, 200), "USED").get("usageCount").asLong()).isEqualTo(1);
        assertThat(putAs("/api/v1/relationship-types/" + used, updater, body("directionality", "symmetric"), 409)
                .get("code").asString()).isEqualTo("RELATIONSHIP_TYPE_IN_USE");
        assertThat(deleteAs("/api/v1/relationship-types/" + used, deleter, 409).get("code").asString())
                .isEqualTo("RELATIONSHIP_TYPE_IN_USE");
        putAs("/api/v1/relationship-types/" + used, updater, body("enabled", false), 200);
        assertThat(activeLinks(tenantId)).as("disabling keeps the link").isEqualTo(1);

        // Removed links still count as use: the history keeps pointing at the type.
        fx.jdbc().update("update document_relationships set removed_at = now(), removed_reason = 'user_removed'"
                + " where relationship_type_id = ?", used);
        deleteAs("/api/v1/relationship-types/" + used, deleter, 409);

        deleteAs("/api/v1/relationship-types/" + unused, deleter, 204);
        assertThat(auditCount(tenantId, "RELATIONSHIP_TYPE_DELETED")).isEqualTo(1);
        assertThat(types(updater, true, 200)).hasSize(11);
        // Its label can be used again.
        postJson("/api/v1/relationship-types", configurer, body("label", "Unused", "directionality", "directional"), 201);
    }

    @Test
    void customType_canTurnOnLoopPrevention_onlyWhenNoLoopExists() throws Exception {
        UUID type = id(postJson("/api/v1/relationship-types", configurer,
                body("label", "Chain", "directionality", "directional"), 201), "relationshipTypeId");
        UUID contract = fx.documentType(tenantId, "Contract");
        UUID a = fx.document(tenantId, contract, admin, "A");
        UUID b = fx.document(tenantId, contract, admin, "B");
        fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id,"
                + " target_document_id) values (?, ?, ?, ?)", tenantId, type, a, b);
        JsonNode on = putAs("/api/v1/relationship-types/" + type, updater, body("preventsCycles", true), 200);
        assertThat(on.get("preventsCycles").asBoolean()).isTrue();
        putAs("/api/v1/relationship-types/" + type, updater, body("preventsCycles", false), 200);

        fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id,"
                + " target_document_id) values (?, ?, ?, ?)", tenantId, type, b, a);
        assertThat(putAs("/api/v1/relationship-types/" + type, updater, body("preventsCycles", true), 409)
                .get("code").asString()).isEqualTo("RELATIONSHIP_TYPE_HAS_CYCLE");
    }

    @Test
    void anotherTenantGets404_neverTheTypeNorA403() throws Exception {
        UUID otherTenant = tenantWithTypes();
        User otherAdmin = fx.user(otherTenant, "OtherAdmin");
        UUID supersedes = typeId(tenantId, "SUPERSEDES");
        UUID custom = id(postJson("/api/v1/relationship-types", configurer,
                body("label", "Mine", "directionality", "directional"), 201), "relationshipTypeId");

        for (UUID target : new UUID[]{supersedes, custom}) {
            call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/relationship-types/" + target),
                    fx.tenantAdminJwtFor(otherAdmin), body("label", "Hijack"), 404);
            call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/relationship-types/" + target),
                    fx.tenantAdminJwtFor(otherAdmin), null, 404);
        }
        JsonNode theirs = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/relationship-types?includeDisabled=true"), fx.tenantAdminJwtFor(otherAdmin), null, 200);
        assertThat(theirs).hasSize(10);
        assertThat(byCode(theirs, "SUPERSEDES").get("relationshipTypeId").asString()).isNotEqualTo(supersedes.toString());
    }
}
