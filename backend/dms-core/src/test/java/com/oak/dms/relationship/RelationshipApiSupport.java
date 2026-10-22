package com.oak.dms.relationship;

import com.oak.dms.relationship.seed.RelationshipTypeSeeder;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Shared set-up and HTTP helpers for the relationship ITs. Test classes add {@code @Import(WorkflowFixtures.class)}. */
abstract class RelationshipApiSupport extends WorkflowApiSupport {

    static final String CREATE = "drm.manage-document-relationships.create";
    static final String REMOVE = "drm.manage-document-relationships.remove";
    static final String TYPE_CREATE = "drm.configure-relationship-types.create-type";
    static final String TYPE_UPDATE = "drm.configure-relationship-types.update-type";
    static final String TYPE_DELETE = "drm.configure-relationship-types.delete-type";
    static final String VIEW = "docm.documents.view";

    @Autowired
    protected RelationshipTypeSeeder typeSeeder;

    /** A fresh tenant with the ten system types (the fixtures create tenants without going through provisioning). */
    protected UUID tenantWithTypes() {
        UUID tenantId = fx.tenant();
        typeSeeder.install(tenantId);
        return tenantId;
    }

    protected UUID typeId(UUID tenantId, String code) {
        return fx.jdbc().queryForObject("select relationship_type_id from relationship_types where tenant_id = ?"
                + " and code = ? and not is_deleted", UUID.class, tenantId, code);
    }

    /** Gives the document its own access list: only {@code user} at {@code level} (view|edit|manage). */
    protected UUID restrictTo(UUID tenantId, UUID documentId, User user, String level) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", documentId);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id,"
                + " access_level) values (?, ?, 'user', ?, ?)", tenantId, documentId, user.getUserId(), level);
        return documentId;
    }

    protected void grantAccess(UUID tenantId, UUID documentId, User user, String level) {
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id,"
                + " access_level) values (?, ?, 'user', ?, ?)", tenantId, documentId, user.getUserId(), level);
    }

    protected static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    protected JsonNode createLink(User as, UUID typeId, UUID source, UUID target, int expectedStatus) throws Exception {
        return postAs("/api/v1/document-relationships", as,
                body("relationshipTypeId", typeId, "sourceDocumentId", source, "targetDocumentId", target), expectedStatus);
    }

    protected JsonNode panel(User as, UUID documentId) throws Exception {
        return get200("/api/v1/documents/" + documentId + "/relationships", as);
    }

    protected long activeLinks(UUID tenantId) {
        return fx.count("select count(*) from document_relationships where tenant_id = ? and removed_at is null", tenantId);
    }

    protected long auditCount(UUID tenantId, String action) {
        return fx.count("select count(*) from audit_events where tenant_id = ? and action = ?", tenantId, action);
    }

    protected JsonNode types(User as, boolean includeDisabled, int expectedStatus) throws Exception {
        return call(get("/api/v1/relationship-types" + (includeDisabled ? "?includeDisabled=true" : "")),
                fx.jwtFor(as), null, expectedStatus);
    }

    protected JsonNode putAs(String path, User as, Object body, int expectedStatus) throws Exception {
        return call(put(path), fx.jwtFor(as), body, expectedStatus);
    }

    protected JsonNode patchAs(String path, User as, Object body, int expectedStatus) throws Exception {
        return call(patch(path), fx.jwtFor(as), body, expectedStatus);
    }

    protected JsonNode deleteAs(String path, User as, int expectedStatus) throws Exception {
        return call(delete(path), fx.jwtFor(as), null, expectedStatus);
    }

    protected JsonNode postJson(String path, User as, Object body, int expectedStatus) throws Exception {
        return call(post(path), fx.jwtFor(as), body, expectedStatus);
    }
}
